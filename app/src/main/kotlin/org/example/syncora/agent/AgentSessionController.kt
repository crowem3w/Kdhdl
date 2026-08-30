package org.example.syncora.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperTradingRepository

/**
 * The one place this app constructs and drives the full ESN/RRL agent
 * stack (`docs/agent-design-contract.md`, `ESN_RRL_Agent_Task_Prompts.md`
 * Prompts 6-8) against real market data, instead of a test harness's
 * synthetic bars. Owned at application scope (see [org.example.syncora.SyncoraApplication],
 * same reasoning as `pipeline`/`depthPipeline` there) so the agent's
 * reservoir/readout/policy state and its live session survive activity
 * recreation exactly like the market-data pipelines do.
 *
 * ### What this wires together
 * - [FeatureAssembler] / [ReservoirWeights.randomWeights] / [ReadoutTrainer] /
 *   [RewardEngine] / [PolicyEngine] - Phase 1-5, restored from
 *   [FileAgentCheckpointStore] via [restoreOrFreshOrchestrator] if a prior
 *   session's checkpoint exists, or freshly initialized otherwise (Prompt 7e).
 * - [PaperTradingOrderSink] - adapts [paperTradingRepository]'s existing
 *   suspend order path to [PaperOrderSink] (Prompt 7c), so the agent trades
 *   the exact same on-device paper account the manual Paper Trading UI does.
 * - [HardenedAgentLiveSession] - Phase 7/8's guardrail-gated driver
 *   (kill switch, feed-staleness/dropout checks, position/notional caps),
 *   not the un-hardened [AgentLiveSession] - this is the live-money-adjacent
 *   path, so it always runs behind the guardrail layer.
 * - [LiveBarCloseSubscriber] - Prompt 7a's bar-close detector, fed directly
 *   from [org.example.syncora.bitget.TradingChartPipeline.klines] and
 *   [org.example.syncora.bitget.DepthPipeline.depth], the same sources the
 *   chart itself renders from.
 *
 * ### Funding / fee inputs
 * [feeRate] is read from [paperTradingRepository]'s own live-refreshed
 * [PaperTradingRepository.feeRates] - the same rate simulated paper fills
 * are already charged - each bar, rather than assumed. Funding is not yet
 * threaded through here (`fundingRateAt` defaults to zero); wiring
 * `BitgetFundingRateClient`'s cached rate through is the same shape as
 * `feeRate` and can be added the same way once a caller needs κ_t to be
 * nonzero for a live run.
 *
 * @param context Used only to build [FileAgentCheckpointStore]'s on-disk path.
 * @param klines Live kline stream to drive bar closes from - [org.example.syncora.bitget.TradingChartPipeline.klines].
 * @param depthAt Synchronous depth snapshot supplier, called once per detected bar close - [org.example.syncora.bitget.DepthPipeline.depth]'s current value.
 * @param barIntervalMsProvider Current bar duration in ms - [org.example.syncora.bitget.TradingChartPipeline.barDurationMillis]'s current value, read once at construction. [HardenedAgentLiveSession.expectedBarIntervalMs] is fixed for a session's lifetime, so a chart timeframe switch after this controller is constructed does not retroactively change the guardrail's staleness window - a genuinely dynamic interval would need a new session, out of scope for this first wiring pass.
 * @param paperTradingRepository Where the agent's own paper orders land - a dedicated leveraged sizing/scale is applied via [maxPositionSizeBaseCoin], independent of whatever the manual Paper Trading UI's own inputs are set to.
 * @param maxPositionSizeBaseCoin Same contract as [PositionOrderEmitter.maxPositionSizeBaseCoin] - the base-coin size the agent's `f_t = ±1.0` corresponds to. Kept small by default since this account is shared with manual paper trading.
 * @param maxPositionFraction Same contract as [PositionCaps.maxPositionFraction] - Phase 7's independent ceiling on top of the policy's own `[-1, 1]` bound.
 * @param scope Where the live session's async checkpoint saves and this controller's own bar-collection loop run.
 */
class AgentSessionController(
    context: Context,
    private val klines: Flow<List<Kline>>,
    private val depthAt: () -> DepthSnapshot,
    private val barIntervalMsProvider: () -> Long,
    private val paperTradingRepository: PaperTradingRepository,
    private val maxPositionSizeBaseCoin: Double = DEFAULT_MAX_POSITION_SIZE_BASE_COIN,
    private val maxPositionFraction: Float = DEFAULT_MAX_POSITION_FRACTION,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    companion object {
        /** Deliberately small: this shares an account with the manual Paper Trading UI. */
        const val DEFAULT_MAX_POSITION_SIZE_BASE_COIN = 0.01
        const val DEFAULT_MAX_POSITION_FRACTION = 0.5f
        private const val CHECKPOINT_KEY = "BTCUSDT_esn_rrl"
    }

    private val checkpointStore = FileAgentCheckpointStore(context, CHECKPOINT_KEY)
    private val featureAssembler = FeatureAssembler(
        fundingRateProvider = { null }, // no cached funding rate feed wired in yet - see class doc
    )
    private val reservoirWeights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH)
    private val rewardEngine = RewardEngine()
    private val killSwitch = AgentKillSwitch()
    private val guardrailSupervisor = GuardrailSupervisor(
        caps = PositionCaps(maxPositionFraction = maxPositionFraction),
        killSwitch = killSwitch,
    )
    private val orderSink = PaperTradingOrderSink(paperTradingRepository, scope)
    private val orderEmitter = PositionOrderEmitter(
        orderSink = orderSink,
        currentPosition = { paperTradingRepository.positions.value.firstOrNull() },
        maxPositionSizeBaseCoin = maxPositionSizeBaseCoin,
    )

    /** Restored-or-fresh on construction, per Prompt 7e - a blocking (file-read-only) call kept off the hot path by only ever running once, at controller construction. */
    private val orchestrator: AgentOrchestrator = runBlocking {
        checkpointStore.restoreOrFreshOrchestrator(
            featureAssembler = featureAssembler,
            reservoirWeights = reservoirWeights,
            rewardEngine = rewardEngine,
        )
    }

    private val session = HardenedAgentLiveSession(
        orchestrator = orchestrator,
        orderEmitter = orderEmitter,
        guardrailSupervisor = guardrailSupervisor,
        killSwitch = killSwitch,
        expectedBarIntervalMs = barIntervalMsProvider().coerceAtLeast(1L),
        positionSizeScaleBaseCoin = maxPositionSizeBaseCoin,
        checkpointStore = checkpointStore,
        scope = scope,
    )

    /** Prompt 7f's decision-log stream - what [org.example.syncora.ui.AgentStatusLogPanel] subscribes to. */
    val decisionLog: SharedFlow<AgentDecisionLogEntry> = session.decisionLog

    private val subscriber = AgentOrchestrator.LiveBarCloseSubscriber()
    private var started = false

    /** Idempotent, same convention as [org.example.syncora.SyncoraApplication.ensureMarketDataStarted]. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            subscriber.collect(klines = klines, depthAt = depthAt) { liveBarClose ->
                val referencePrice = liveBarClose.kline.close
                session.processLiveBar(
                    liveBarClose = liveBarClose,
                    referencePrice = referencePrice,
                    fundingRateAt = { 0.0 },
                    feeRate = paperTradingRepository.feeRates.value.takerRate,
                )
            }
        }
    }

    /** Manual, immediate flatten - see [HardenedAgentLiveSession.engageKillSwitch]. */
    fun engageKillSwitch(reason: String = "manual kill switch") = session.engageKillSwitch(reason)

    /** Synchronously captures the current checkpoint and persists it asynchronously - call from an app-level stop/destroy path. */
    fun stop() = session.stop()
}
