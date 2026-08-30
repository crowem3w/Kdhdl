package org.example.syncora.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Phase 7 (`ESN_RRL_Agent_Task_Prompts.md` Prompt 8): the hardened
 * counterpart to [AgentLiveSession], and the driver Phase 8 (live trading)
 * is built on. Every bar goes through [GuardrailSupervisor]'s gates
 * *before* [PositionOrderEmitter] is ever called, so a policy bug, a stale
 * feed, or a diverged readout can only ever result in "no order sent" or
 * "flatten the position" reaching [orderEmitter] - never [orchestrator]'s
 * raw, unchecked output.
 *
 * This is deliberately a *new* class rather than a modification of
 * [AgentLiveSession]: [AgentLiveSession] is Prompt 7d/7e/7f's
 * already-verified checkpoint-save/load/status-panel driver, and its
 * existing tests assert it calls [PositionOrderEmitter.onTargetPosition]
 * unconditionally with the orchestrator's raw output - correct behaviour
 * *for Phase 6*, whose whole job was proving the inference chain and order
 * path work, not hardening them. Changing that method's contract now would
 * both break those already-passing tests and blur the "outside and
 * independent of the learned policy" framing Prompt 8 asks for: the
 * guardrail layer is easiest to reason about, and easiest to unit-test in
 * isolation (`GuardrailSupervisorTest`), as its own class sitting in front
 * of the same building blocks [AgentLiveSession] already uses, not
 * threaded through that class's internals.
 *
 * ### Per-bar flow
 * 1. [GuardrailSupervisor.checkFeedDropout] - has *any* bar-close reached
 *    this session recently enough? If not, flatten and stop (this bar is
 *    not fed to [orchestrator] at all).
 * 2. [GuardrailSupervisor.checkFeedFreshness] - is *this* bar's own kline
 *    recent enough to act on? If not, skip it (no order, not fed to
 *    [orchestrator] either - stale data shouldn't update online-learned
 *    state any more than it should place an order).
 * 3. An already-engaged [killSwitch] - checked here too (not only inside
 *    [GuardrailSupervisor.evaluateDecision]) so a kill switch engaged
 *    between bars flattens on the very next bar without needing
 *    [orchestrator] to run at all.
 * 4. [AgentOrchestrator.processLiveBar] - the full Phase 1-5 chain, exactly
 *    as [AgentLiveSession] runs it.
 * 5. [GuardrailSupervisor.evaluateDecision] - Prompt 8's remaining checks
 *    (NaN/out-of-bounds position, internal instability, RLS covariance
 *    blow-up) plus [PositionCaps] clamping on the healthy path.
 * 6. The resulting [GuardedAction] is applied to [orderEmitter] (see
 *    [applyAction]) and, for audit, condensed the same way
 *    [AgentLiveSession] does onto [decisionLog] whenever [orchestrator]
 *    actually ran this bar.
 *
 * @param orchestrator The Phase 1-5 chain - same role as [AgentLiveSession]'s constructor parameter of the same name.
 * @param orderEmitter Prompt 7c's order path - never called with anything but a [PositionCaps]-clamped fraction or `0f` (flatten).
 * @param guardrailSupervisor Prompt 8's gates - see class doc.
 * @param killSwitch The same [AgentKillSwitch] instance [guardrailSupervisor] reads/writes - kept here too so [engageKillSwitch] can flatten immediately without waiting for [guardrailSupervisor] to see it on a future bar.
 * @param expectedBarIntervalMs This session's bar interval (see [org.example.syncora.bitget.Granularity]) - drives both of [guardrailSupervisor]'s staleness checks.
 * @param positionSizeScaleBaseCoin Must equal [orderEmitter]'s own `maxPositionSizeBaseCoin` - passed separately (rather than this class reading it off [orderEmitter] implicitly) so the caller's wiring makes the shared assumption explicit; a mismatch here would silently miscompute [PositionCaps]'s notional ceiling.
 * @param checkpointStore Where [stop] persists the checkpoint - same contract as [AgentLiveSession.stop]/[AgentLiveSession.checkpointStore].
 * @param scope Where the async checkpoint write in [stop] runs - same contract as [AgentLiveSession]'s constructor parameter of the same name.
 * @param nowMs Wall-clock supplier - overridable so tests can drive feed-dropout/staleness checks deterministically without `Thread.sleep`.
 */
class HardenedAgentLiveSession(
    private val orchestrator: AgentOrchestrator,
    private val orderEmitter: PositionOrderEmitter,
    private val guardrailSupervisor: GuardrailSupervisor,
    private val killSwitch: AgentKillSwitch,
    private val expectedBarIntervalMs: Long,
    private val positionSizeScaleBaseCoin: Double,
    private val checkpointStore: AgentCheckpointStore = NoopAgentCheckpointStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(expectedBarIntervalMs > 0) { "expectedBarIntervalMs must be > 0, was $expectedBarIntervalMs" }
    }

    /** Same role as [AgentLiveSession.liveState] - constructed once, reused for every [processLiveBar] call this session's lifetime. */
    private val liveState = AgentOrchestrator.LiveInferenceState()

    private var lastBarCloseReceivedAtMs: Long? = null

    private val mutableDecisionLog = MutableSharedFlow<AgentDecisionLogEntry>(
        replay = 0,
        extraBufferCapacity = DECISION_LOG_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Same role as [AgentLiveSession.decisionLog] - one entry per bar [orchestrator] actually processed (a bar skipped for staleness/dropout before reaching [orchestrator] has nothing to log here; see [lastGuardedAction] for that case). */
    val decisionLog: SharedFlow<AgentDecisionLogEntry> = mutableDecisionLog

    /** The [GuardedAction] this session most recently decided on, whether or not [orchestrator] ran this bar - lets a status panel/test distinguish "traded", "skipped (stale/dropout)", and "flattened (guardrail tripped)" even for bars that never produced a [decisionLog] entry. */
    var lastGuardedAction: GuardedAction? = null
        private set

    /**
     * Drives one live bar-close through the full guardrail-gated pipeline
     * - see class doc's "Per-bar flow". Returns the [GuardedAction] this
     * bar resolved to.
     *
     * @param referencePrice This bar's reference (mid) price, in the same terms [RewardEngine] uses - fed to [PositionCaps.clamp] for the notional cap. Ignored on any non-[GuardedAction.Trade] path.
     */
    fun processLiveBar(
        liveBarClose: AgentOrchestrator.LiveBarClose,
        referencePrice: Double,
        fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        feeRate: Double = 0.0,
    ): GuardedAction {
        val now = nowMs()

        val dropout = guardrailSupervisor.checkFeedDropout(now, lastBarCloseReceivedAtMs, expectedBarIntervalMs)
        if (dropout != null) {
            lastBarCloseReceivedAtMs = now
            return resolve(dropout)
        }

        val stale = guardrailSupervisor.checkFeedFreshness(now, liveBarClose.kline.startTime, expectedBarIntervalMs)
        if (stale != null) {
            lastBarCloseReceivedAtMs = now
            return resolve(stale)
        }

        lastBarCloseReceivedAtMs = now

        if (killSwitch.isTriggered()) {
            return resolve(GuardedAction.Flatten("kill switch engaged: ${killSwitch.reason() ?: "unknown reason"}"))
        }

        val decision = orchestrator.processLiveBar(
            liveBarClose = liveBarClose,
            state = liveState,
            fundingRateAt = fundingRateAt,
            feeRate = feeRate,
        )

        val action = guardrailSupervisor.evaluateDecision(
            decision = decision,
            orchestratorStable = orchestrator.isStable(),
            readoutCovarianceMagnitude = orchestrator.readoutCovarianceMagnitude(),
            referencePrice = referencePrice,
            positionSizeScaleBaseCoin = positionSizeScaleBaseCoin,
        )
        mutableDecisionLog.tryEmit(AgentDecisionLogEntry.fromDecisionLog(decision))
        return resolve(action)
    }

    /**
     * Prompt 8's "single control that halts the [AgentOrchestrator] and
     * flattens the current position, reachable even if the UI thread is
     * busy or unresponsive": engages [killSwitch] *and* flattens
     * immediately, synchronously, on the calling thread - not on the next
     * bar close (which might be minutes away) and not via [scope] (which
     * would make "flattened" happen on a different, unwitnessed thread).
     * [orderEmitter] itself is plain, non-suspending Kotlin (see that
     * class's doc), so this whole method does no dispatcher hop, no
     * `Looper` post, and no wait on anything that could itself be the
     * "busy or unresponsive" thread - a caller (a UI click handler, a
     * background watchdog, a test) gets a flattened position back before
     * this call returns.
     */
    fun engageKillSwitch(reason: String = "manual kill switch") {
        killSwitch.trigger(reason)
        orderEmitter.onTargetPosition(0f)
        lastGuardedAction = GuardedAction.Flatten(reason)
    }

    /** Applies [action] to [orderEmitter] (see [GuardedAction]'s own doc for which cases call [PositionOrderEmitter.onTargetPosition] and with what) and records it as [lastGuardedAction]. */
    private fun resolve(action: GuardedAction): GuardedAction {
        when (action) {
            is GuardedAction.Trade -> orderEmitter.onTargetPosition(action.position)
            is GuardedAction.Flatten -> orderEmitter.onTargetPosition(0f)
            is GuardedAction.NoOrder -> Unit // no call to orderEmitter at all
        }
        lastGuardedAction = action
        return action
    }

    /** Same contract as [AgentLiveSession.stop] - see that method's doc for the synchronous-capture/asynchronous-write split. */
    fun stop(): Job {
        val checkpoint = orchestrator.currentCheckpoint()
        return scope.launch { checkpointStore.save(checkpoint) }
    }

    private companion object {
        /** Same rationale as [AgentLiveSession.DECISION_LOG_BUFFER_CAPACITY]. */
        const val DECISION_LOG_BUFFER_CAPACITY = 256
    }
}
