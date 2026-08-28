package org.example.syncora.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns one continuous live-trading session on top of Prompt 7b's
 * [AgentOrchestrator.processLiveBar] and Prompt 7c's [PositionOrderEmitter]:
 * every bar handed to [processLiveBar] runs the full Phase 1-5 chain and
 * emits whatever paper order that bar's target position implies. This is
 * the "Prompt 7c's order-emitting live loop" Prompt 7d's checkpoint save is
 * built on top of.
 *
 * ### Checkpoint save, on the app's lifecycle (Prompt 7d)
 * [stop] is this session's save trigger, and is meant to be called from the
 * same kind of place [org.example.syncora.bitget.TradingChartPipeline.stop]
 * already is - an `onStop`/`onDestroy`-style lifecycle callback when the app
 * backgrounds or the session otherwise ends. It follows that exact
 * established pattern rather than inventing a new one:
 * - The data needed for the save ([AgentOrchestrator.currentCheckpoint]) is
 *   captured *synchronously*, on the calling thread, the moment [stop] is
 *   invoked - mirroring [org.example.syncora.bitget.TradingChartPipeline.stop]
 *   snapshotting `buffer.snapshot()` synchronously before persisting it.
 *   This is what makes "the written checkpoint's contents matching in-memory
 *   state at the moment of the stop signal" (Prompt 7d) true regardless of
 *   how long the actual disk write takes or what runs concurrently after.
 * - The actual write is hopped off onto [scope] (`Dispatchers.Default` by
 *   default, matching every other `scope` field in this codebase -
 *   [org.example.syncora.bitget.TradingChartPipeline] and
 *   `PaperTradingRepository` both use the identical
 *   `CoroutineScope(SupervisorJob() + Dispatchers.Default)` shape), so a
 *   non-suspend Android lifecycle callback can call [stop] directly without
 *   blocking, the same way `TradingChartPipeline.stop()`'s
 *   `scope.launch { cacheStore.save(finalSnapshot) }` does for the kline
 *   cache.
 *
 * [stop] returns the [Job] it launched so a caller that *can* suspend (a
 * test, or an explicit "save and wait" shutdown path) can `.join()` it;
 * production Android lifecycle callers are free to ignore the return value,
 * exactly as they already do for
 * [org.example.syncora.bitget.TradingChartPipeline.stop]'s own internal
 * `scope.launch`.
 *
 * ### Checkpoint load, on app start (Prompt 7e)
 * [orchestrator] is always constructed already fresh-or-restored by the
 * caller before it ever reaches this class's primary constructor - this
 * class's own job is driving bars and triggering saves, never deciding what
 * state a session starts from. [Companion.start] is the "on app start"
 * counterpart to [stop]: it resolves that fresh-or-restored orchestrator via
 * [AgentCheckpointStore.restoreOrFreshOrchestrator] - restoring
 * [checkpointStore]'s most recent checkpoint if one exists, parses, and
 * matches this run's configuration, falling back cleanly to a fresh
 * orchestrator otherwise (see that function's doc for the three fallback
 * cases) - *before* constructing the [AgentLiveSession] that will drive it,
 * so the very first [processLiveBar] call already sees whatever state was
 * restored, never a fresh orchestrator silently standing in for one that
 * should have resumed. Callers that already have a suitably-restored
 * [AgentOrchestrator] in hand (e.g. most of this class's own tests) can
 * keep using the primary constructor directly - [Companion.start] is a
 * convenience, not the only path in.
 *
 * @param orchestrator The Phase 1-5 chain this session drives, one bar at a time. Owns its own [ReservoirEngine]/[ReadoutTrainer]/[PolicyEngine] internally - see [AgentOrchestrator.currentCheckpoint].
 * @param orderEmitter Prompt 7c's order path - every [processLiveBar] call feeds that bar's resulting target position into [PositionOrderEmitter.onTargetPosition].
 * @param checkpointStore Where [stop] persists the checkpoint - defaults to [NoopAgentCheckpointStore] so constructing a session in a context that doesn't care about persistence (e.g. a lightweight test) doesn't require wiring one up.
 * @param scope Where the async checkpoint write in [stop] runs - see class doc. Defaults to a fresh session-owned scope, same shape as every other long-lived `scope` field in this codebase.
 */
class AgentLiveSession(
    private val orchestrator: AgentOrchestrator,
    private val orderEmitter: PositionOrderEmitter,
    private val checkpointStore: AgentCheckpointStore = NoopAgentCheckpointStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** This session's [AgentOrchestrator.LiveInferenceState] - constructed once and reused for every [processLiveBar] call for the life of this session, per that class's own contract. */
    private val liveState = AgentOrchestrator.LiveInferenceState()

    /**
     * Drives one live bar-close through [AgentOrchestrator.processLiveBar]
     * and immediately feeds the resulting target position into
     * [orderEmitter] - Prompt 7c's live loop, end to end, one call.
     */
    fun processLiveBar(
        liveBarClose: AgentOrchestrator.LiveBarClose,
        fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        feeRate: Double = 0.0,
    ): AgentOrchestrator.DecisionLog {
        val decision = orchestrator.processLiveBar(
            liveBarClose = liveBarClose,
            state = liveState,
            fundingRateAt = fundingRateAt,
            feeRate = feeRate,
        )
        orderEmitter.onTargetPosition(decision.position)
        return decision
    }

    /**
     * Call on background/stop - see class doc for the synchronous-capture,
     * asynchronous-write split this follows. Safe to call more than once
     * (e.g. a stray extra lifecycle callback); each call captures and
     * persists whatever the orchestrator's state is *at that moment*, same
     * as repeated [org.example.syncora.bitget.TradingChartPipeline.stop]
     * calls would each just re-save the then-current buffer.
     */
    fun stop(): Job {
        val checkpoint = orchestrator.currentCheckpoint()
        return scope.launch { checkpointStore.save(checkpoint) }
    }

    companion object {
        /**
         * Prompt 7e's "on app start" entry point - see class doc's
         * "Checkpoint load" section. Restores (or freshly initializes, on
         * any of [AgentCheckpointStore.restoreOrFreshOrchestrator]'s
         * fallback cases) an [AgentOrchestrator] from [checkpointStore]
         * *before* this session is constructed, so [processLiveBar] never
         * runs against a not-yet-restored orchestrator.
         *
         * [featureAssembler], [reservoirWeights], and [rewardEngine] are
         * passed straight through to
         * [AgentCheckpointStore.restoreOrFreshOrchestrator] - see that
         * function's doc for why the checkpoint itself doesn't carry these.
         * [policyNHidden]/[policyNBack]/[policyLearningRate]/[policyWeightClip]
         * are the shape/hyperparameters a *fresh* [PolicyEngine] is built
         * with if there's nothing to restore - ignored once a checkpoint is
         * restored, whose own saved shape wins instead (same reasoning
         * [ReadoutCheckpoint.toTrainer] already documents for the readout).
         */
        suspend fun start(
            checkpointStore: AgentCheckpointStore,
            featureAssembler: FeatureAssembler,
            reservoirWeights: ReservoirWeights,
            rewardEngine: RewardEngine,
            orderEmitter: PositionOrderEmitter,
            policyNHidden: Int = reservoirWeights.nHidden,
            policyNBack: Int = PolicyEngine.DEFAULT_N_BACK,
            policyLearningRate: Float = PolicyEngine.DEFAULT_LEARNING_RATE,
            policyWeightClip: Float = PolicyEngine.DEFAULT_WEIGHT_CLIP,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): AgentLiveSession {
            val orchestrator = checkpointStore.restoreOrFreshOrchestrator(
                featureAssembler = featureAssembler,
                reservoirWeights = reservoirWeights,
                rewardEngine = rewardEngine,
                policyNHidden = policyNHidden,
                policyNBack = policyNBack,
                policyLearningRate = policyLearningRate,
                policyWeightClip = policyWeightClip,
            )
            return AgentLiveSession(
                orchestrator = orchestrator,
                orderEmitter = orderEmitter,
                checkpointStore = checkpointStore,
                scope = scope,
            )
        }
    }
}
