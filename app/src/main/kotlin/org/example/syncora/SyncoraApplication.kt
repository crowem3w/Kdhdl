package org.example.syncora

import android.app.Application
import org.example.syncora.bitget.BitgetFeeRateClient
import org.example.syncora.bitget.BitgetFundingRateClient
import org.example.syncora.bitget.BitgetLiveCredentialsStore
import org.example.syncora.bitget.BitgetTradeSocket
import org.example.syncora.bitget.DepthPipeline
import org.example.syncora.bitget.DirectRLPositionPipeline
import org.example.syncora.bitget.DirectRLReadout
import org.example.syncora.bitget.EchoStateReservoir
import org.example.syncora.bitget.FeatureAugmentationPipeline
import org.example.syncora.bitget.FileKlineCacheStore
import org.example.syncora.bitget.LiveTradingRepository
import org.example.syncora.bitget.LocalPaperTradingStore
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.bitget.RLFeatureVectorPipeline
import org.example.syncora.bitget.RiskSettingsStore
import org.example.syncora.bitget.StopLossGuard
import org.example.syncora.bitget.Timeframe
import org.example.syncora.bitget.TradingChartPipeline

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
            fundingRateClient = BitgetFundingRateClient(),
            depthSnapshotProvider = { depthPipeline.depth.value },
            tradeFlow = tradeSocket.trades,
        )
    }

    val liveTradingRepository: LiveTradingRepository by lazy {
        LiveTradingRepository(credentialsStore = liveCredentialsStore, symbol = "BTCUSDT")
    }

    // Supplies u_t, Δp_t, δ_t, κ_t, and ŷ_t (design doc §9 /
    // recurrent-reinforcement-learning-crypto-agent.md Appendix A) off the
    // same live pipelines above - no separate data path, so the RL agent's
    // inputs never disagree with what the chart/paper-trading engine see.
    // Defaults to reading position/funding state off the paper trading
    // engine; point [positionProvider]/[equityProvider]/[fundingRateProvider]
    // at liveTradingRepository instead once live trading is the source of
    // truth for a given deployment.
    val rlFeaturePipeline: RLFeatureVectorPipeline by lazy {
        RLFeatureVectorPipeline(
            chartPipeline = pipeline,
            depthPipeline = depthPipeline,
            tradeSocket = tradeSocket,
            fundingRateProvider = { paperTradingRepository.currentFunding.value },
            positionProvider = { paperTradingRepository.positions.value },
            equityProvider = { paperTradingRepository.balance.value?.equity },
            // Same real taker rate (see BitgetFeeRateClient) the paper
            // trading engine simulates fills against - paper §3.3's cost
            // model is book-walk slippage *plus* a taker exchange fee, not
            // slippage alone.
            feeRateProvider = { paperTradingRepository.feeRates.value.takerRate },
        )
    }

    // The source (ESN reservoir) and target (direct-RL readout) halves of
    // the paper's transfer-learning pipeline (§3.2) - see
    // recurrent-reinforcement-learning-crypto-agent.md. nInput/nBack take
    // their defaults from RLFeatureVectorPipeline.FEATURE_COUNT / its own
    // nBack=10 default respectively, so these three stay in lockstep
    // without hand-duplicating the sizes here.
    val rlReservoir: EchoStateReservoir by lazy { EchoStateReservoir() }

    val rlFeatureAugmentationPipeline: FeatureAugmentationPipeline by lazy {
        FeatureAugmentationPipeline(source = rlFeaturePipeline, reservoir = rlReservoir)
    }

    val rlReadout: DirectRLReadout by lazy { DirectRLReadout(reservoir = rlReservoir) }

    // Emits one DirectRLDecision (targetPosition/gatedPosition/utility/etc,
    // §3.2.2 Eq. 6-12) per tick of live market data. Deliberately *not*
    // wired to place any order itself - see [rlPositionPipeline]'s own
    // docs and [DirectRLDecision.gatedPosition] - turning this into trades
    // is a separate, explicit product decision, not something starting
    // market data should silently begin doing. For now this only computes
    // and exposes the agent's decision stream (e.g. for logging/future UI)
    // while online training runs continuously against live data.
    val rlPositionPipeline: DirectRLPositionPipeline by lazy {
        DirectRLPositionPipeline(source = rlFeatureAugmentationPipeline, readout = rlReadout)
    }

    val riskSettingsStore: RiskSettingsStore by lazy {
        RiskSettingsStore(applicationContext)
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
        rlFeaturePipeline.start()
        // Order matters only in that each of these subscribes to the
        // previous one's StateFlow rather than driving it directly, so
        // starting them in dependency order isn't strictly required - but
        // doing so anyway means the reservoir/readout don't sit spun up
        // with nothing feeding them even for the first tick.
        rlFeatureAugmentationPipeline.start()
        rlPositionPipeline.start()
    }

    /** Call only when the foreground service itself is being torn down, not on activity backgrounding. */
    fun stopMarketData() {
        marketDataStarted = false
        pipeline.stop()
        depthPipeline.stop()
        tradeSocket.disconnect()
        liveTradingRepository.stop()
        stopLossGuard.stop()
        rlPositionPipeline.stop()
        rlFeatureAugmentationPipeline.stop()
        rlFeaturePipeline.stop()
    }
}
