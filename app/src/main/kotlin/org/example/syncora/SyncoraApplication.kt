package org.example.syncora

import android.app.Application
import org.example.syncora.agent.AgentKillSwitchController
import org.example.syncora.agent.DecisionLoopScheduler
import org.example.syncora.agent.ExperienceLogStore
import org.example.syncora.agent.ModelRollbackController
import org.example.syncora.agent.TrainingRunHistoryStore
import org.example.syncora.agent.TrainingRunStore
import org.example.syncora.bitget.BitgetEnvironment
import org.example.syncora.bitget.BitgetFeeRateClient
import org.example.syncora.bitget.BitgetFundingRateClient
import org.example.syncora.bitget.BitgetLiveCredentialsStore
import org.example.syncora.bitget.BitgetTradeSocket
import org.example.syncora.bitget.BitgetTradingRestClient
import org.example.syncora.bitget.DepthPipeline
import org.example.syncora.bitget.FileKlineCacheStore
import org.example.syncora.bitget.LiveTradingRepository
import org.example.syncora.bitget.LocalPaperTradingStore
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.bitget.RiskSettingsStore
import org.example.syncora.bitget.StateVectorBuilder
import org.example.syncora.bitget.StopLossGuard
import org.example.syncora.bitget.Timeframe
import org.example.syncora.bitget.TradingChartPipeline
import org.example.syncora.ml.PolicyInferenceEngine
import org.example.syncora.ml.PolicyModelStore
import org.example.syncora.risk.PreTradeSafetyGate
import org.example.syncora.risk.RiskLimitsStore
import org.example.syncora.risk.VolatilityCircuitBreaker
import org.example.syncora.risk.VolatilityIndexClient
import org.example.syncora.work.TrainingScheduler

/**
 * Holds the market-data pipelines at application scope instead of activity scope.
 *
 * Keeping the pipelines here instead of inside MainActivity means they survive
 * configuration changes and brief activity recreation without dropping the live stream.
 *
 * Start/stop control of these pipelines now belongs to
 * [org.example.syncora.service.MarketDataForegroundService], not to any
 * Activity - [ensureMarketDataStarted]/[stopMarketData] are called from the
 * service's onCreate()/onDestroy() so the pipelines run for as long as the
 * service does, independent of whether an Activity is on screen. Both
 * methods stay idempotent so a service restart (e.g. after the OS recreates
 * a killed START_STICKY service) is always safe to call into.
 */
class SyncoraApplication : Application() {

    val pipeline: TradingChartPipeline by lazy {
        TradingChartPipeline(
            instId = "BTCUSDT",
            instType = "USDT-FUTURES",
            initialTimeframe = Timeframe.DEFAULT,
            bufferCapacity = 1000,
            cacheStore = FileKlineCacheStore(
                applicationContext,
                cacheKey = "BTCUSDT_USDT-FUTURES_${Timeframe.DEFAULT.wsChannel}",
            ),
        )
    }

    val depthPipeline: DepthPipeline by lazy {
        DepthPipeline(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    // Public trade-print stream, used only by the paper trading engine to
    // estimate queue position for resting limit orders (see
    // QueuePositionTracker) - public market data, no trading permissions.
    val tradeSocket: BitgetTradeSocket by lazy {
        BitgetTradeSocket(instId = "BTCUSDT", instType = "USDT-FUTURES")
    }

    val paperTradingStore: LocalPaperTradingStore by lazy {
        LocalPaperTradingStore(applicationContext)
    }

    val liveCredentialsStore: BitgetLiveCredentialsStore by lazy {
        BitgetLiveCredentialsStore(applicationContext)
    }

    // Public, unauthenticated market data - shared by PaperTradingRepository's
    // funding-accrual job and StateVectorBuilder's F_t reading (design doc
    // §3.1) so both draw from the same client/cache instead of each opening
    // their own connection.
    val fundingRateClient: BitgetFundingRateClient by lazy { BitgetFundingRateClient() }

    // Fully local paper trading: no trading permissions, no exchange
    // matching engine, no Demo API Key - every position, fill, and balance
    // is simulated entirely on-device. It reads a few things off the
    // network, all public/read-only: the live mark price and L2 order book
    // off the same market-data pipelines the chart itself uses (so
    // simulated fills are priced by walking real depth - see
    // OrderBookWalker - instead of a flat assumed slippage), the public
    // trade-print stream (used only to estimate queue position for resting
    // limit orders - see QueuePositionTracker), and Bitget's real
    // maker/taker fee rates (see BitgetFeeRateClient) so simulated fills
    // are charged what a live order actually costs instead of a guessed
    // flat fee. If a Live Trading API key happens to be saved, its actual
    // account-tier rate is used for the fee refresh instead of the public
    // standard tier - still read-only, still optional. It also pulls
    // Bitget's real funding rate (see BitgetFundingRateClient) and accrues
    // it against open positions on the exchange's actual settlement
    // schedule (design doc §7) instead of ignoring it.
    val paperTradingRepository: PaperTradingRepository by lazy {
        PaperTradingRepository(
            store = paperTradingStore,
            symbol = "BTCUSDT",
            markPriceProvider = { pipeline.klines.value.lastOrNull()?.close },
            feeRateClient = BitgetFeeRateClient(),
            feeRateCredentialsProvider = { liveCredentialsStore.load() },
            fundingRateClient = fundingRateClient,
            depthSnapshotProvider = { depthPipeline.depth.value },
            tradeFlow = tradeSocket.trades,
        )
    }

    val riskSettingsStore: RiskSettingsStore by lazy {
        RiskSettingsStore(applicationContext)
    }

    // Design doc §5's hard limits - a separate store from riskSettingsStore
    // because these are a ceiling the policy can never move past, not a
    // toggle it (or the user, from the auto-trading kill switch) controls.
    val riskLimitsStore: RiskLimitsStore by lazy {
        RiskLimitsStore(applicationContext)
    }

    // Deribit's DVOL (BTC volatility index) - the specific, chosen
    // provider for the design doc §5 volatility circuit breaker. A
    // different venue than Bitget entirely, on purpose - see
    // VolatilityIndexClient's kdoc.
    val volatilityIndexClient: VolatilityIndexClient by lazy { VolatilityIndexClient() }

    // Runs its own independent poll loop (started/stopped alongside market
    // data, see ensureMarketDataStarted/stopMarketData below) - not driven
    // by, and not able to be bypassed by, decisionLoopScheduler's decision
    // cadence or the policy's own output.
    val volatilityCircuitBreaker: VolatilityCircuitBreaker by lazy {
        VolatilityCircuitBreaker(
            client = volatilityIndexClient,
            thresholdProvider = { riskLimitsStore.volatilityHaltThreshold },
        )
    }

    // The independent, policy-output-agnostic checks from design doc §5,
    // run before every order any live order-placing path transmits - see
    // PreTradeSafetyGate's kdoc. Shared by decisionLoopScheduler (automated)
    // and liveTradingRepository (manual) so both are held to the same bar.
    val preTradeSafetyGate: PreTradeSafetyGate by lazy {
        PreTradeSafetyGate(
            tradingClient = liveTradingClient,
            volatilityCircuitBreaker = volatilityCircuitBreaker,
            riskLimitsStore = riskLimitsStore,
        )
    }

    val liveTradingRepository: LiveTradingRepository by lazy {
        LiveTradingRepository(
            credentialsStore = liveCredentialsStore,
            symbol = "BTCUSDT",
            safetyGate = preTradeSafetyGate,
            markPriceProvider = { pipeline.klines.value.lastOrNull()?.close },
        )
    }

    // The exchange-side dead-man's-switch (design doc §2.2/§5): keeps a
    // resting stop-loss on Bitget's own book for any open live position, so
    // the position stays protected even if this process (and the foreground
    // service) gets killed. Wired up in [ensureMarketDataStarted] alongside
    // liveTradingRepository, since it has nothing useful to watch until that
    // repository is polling positions.
    val stopLossGuard: StopLossGuard by lazy {
        StopLossGuard(credentialsStore = liveCredentialsStore, riskSettingsStore = riskSettingsStore)
    }

    // Assembles the design doc's §3.1 MDP state S_t = [b_t, h_t, p_t, f_t,
    // q_t, F_t] for BTCUSDT on demand - see StateVectorBuilder.snapshot(),
    // the function meant to be called at each decision boundary (§3.6).
    // Reuses the already-running liveTradingRepository/pipeline/funding
    // client rather than opening any new connections of its own.
    val stateVectorBuilder: StateVectorBuilder by lazy {
        StateVectorBuilder(
            liveTradingRepository = liveTradingRepository,
            chartPipeline = pipeline,
            fundingRateClient = fundingRateClient,
            symbol = "BTCUSDT",
        )
    }

    // Design doc §3.3's "current frozen TFLite policy": file-management
    // (policyModelStore) and inference (policyInferenceEngine) are kept as
    // separate lazies so a future WorkManager training job can depend on
    // just the former (to stage/promote a candidate) without pulling in
    // TFLite's Interpreter, matching how liveTradingRepository/pipeline are
    // already split from the things that consume them.
    val policyModelStore: PolicyModelStore by lazy {
        PolicyModelStore(applicationContext)
    }

    val policyInferenceEngine: PolicyInferenceEngine by lazy {
        PolicyInferenceEngine(context = applicationContext, modelStore = policyModelStore)
    }

    // Design doc §3.6's append-only two-phase-reward log and §3.3's
    // cross-run promotion watermark. Written by decisionLoopScheduler every
    // tick (see DecisionLoopScheduler's "Experience logging" kdoc section)
    // and read by [org.example.syncora.work.PolicyTrainingWorker].
    val experienceLogStore: ExperienceLogStore by lazy {
        ExperienceLogStore(applicationContext)
    }

    val trainingRunStore: TrainingRunStore by lazy {
        TrainingRunStore(applicationContext)
    }

    // Trend counterpart to trainingRunStore's single latest-run snapshot - see
    // TrainingRunHistoryStore's kdoc for why the Agent tab needs both.
    val trainingRunHistoryStore: TrainingRunHistoryStore by lazy {
        TrainingRunHistoryStore(applicationContext)
    }

    // The manual counterpart to PolicyTrainingWorker's automatic
    // rollback-on-load-failure: a human-triggered path to revert to the
    // previous live model if a promoted candidate turns out to underperform
    // once it's actually trading live, which isn't something any automatic
    // check can detect at promotion time. See ModelRollbackController's kdoc.
    val modelRollbackController: ModelRollbackController by lazy {
        ModelRollbackController(
            policyModelStore = policyModelStore,
            policyInferenceEngine = policyInferenceEngine,
            trainingRunStore = trainingRunStore,
        )
    }

    // Independent client/credentials read from the same encrypted store as
    // liveTradingRepository, same pattern StopLossGuard already uses - the
    // decision loop's order dispatch doesn't need liveTradingRepository's
    // polling loop to be the thing driving it, only read access to the
    // account state it already exposes via stateVectorBuilder.
    private val liveTradingClient: BitgetTradingRestClient by lazy {
        BitgetTradingRestClient(
            environment = { BitgetEnvironment.LIVE },
            credentialsProvider = { liveCredentialsStore.load() },
        )
    }

    // Design doc §3.6's live decision loop: fires once per kline close,
    // runs stateVectorBuilder's snapshot through policyInferenceEngine,
    // applies bounded exploration noise, and dispatches the resulting
    // target-position delta through liveTradingClient. Gated behind
    // riskSettingsStore.autoTradingEnabled - see DecisionLoopScheduler's
    // kdoc for why that check doesn't block inference/telemetry, only
    // the order-placement step.
    val decisionLoopScheduler: DecisionLoopScheduler by lazy {
        DecisionLoopScheduler(
            chartPipeline = pipeline,
            stateVectorBuilder = stateVectorBuilder,
            policyInferenceEngine = policyInferenceEngine,
            tradingClient = liveTradingClient,
            riskSettingsStore = riskSettingsStore,
            safetyGate = preTradeSafetyGate,
            volatilityCircuitBreaker = volatilityCircuitBreaker,
            experienceLogStore = experienceLogStore,
            policyModelStore = policyModelStore,
        )
    }

    // The Agent tab's manual override: halts decisionLoopScheduler and flattens any open
    // live position on demand, independent of (and in addition to) stopLossGuard's always-on
    // exchange-side dead-man's-switch. See AgentKillSwitchController's kdoc.
    val agentKillSwitchController: AgentKillSwitchController by lazy {
        AgentKillSwitchController(
            decisionLoopScheduler = decisionLoopScheduler,
            riskSettingsStore = riskSettingsStore,
            liveTradingRepository = liveTradingRepository,
        )
    }

    /**
     * Schedules the §3.3/§3.6/§4 daily batch-training job (see
     * [TrainingScheduler]) at process start, independent of whether the
     * foreground service or any Activity is ever created - `WorkManager`
     * itself survives process death, so this only needs to run once per
     * install to take effect, but [TrainingScheduler.schedule]'s `KEEP`
     * policy makes calling it on every app start harmless.
     */
    override fun onCreate() {
        super.onCreate()
        TrainingScheduler.schedule(this)
    }

    private var marketDataStarted = false

    /**
     * Idempotent: safe to call repeatedly. Called from
     * [org.example.syncora.service.MarketDataForegroundService]'s onCreate()
     * rather than from any Activity lifecycle callback, so backgrounding the
     * app no longer stops market data, live-position polling, or the
     * stop-loss guard.
     */
    fun ensureMarketDataStarted() {
        if (marketDataStarted) return
        marketDataStarted = true
        pipeline.start()
        depthPipeline.start()
        tradeSocket.connect()
        liveTradingRepository.start()
        stopLossGuard.start(liveTradingRepository.positions)
        volatilityCircuitBreaker.start()
        policyInferenceEngine.ensureLoaded()
        decisionLoopScheduler.start()
    }

    /** Call only when the foreground service itself is being torn down, not on activity backgrounding. */
    fun stopMarketData() {
        marketDataStarted = false
        pipeline.stop()
        depthPipeline.stop()
        tradeSocket.disconnect()
        liveTradingRepository.stop()
        stopLossGuard.stop()
        volatilityCircuitBreaker.stop()
        decisionLoopScheduler.stop()
    }
}
