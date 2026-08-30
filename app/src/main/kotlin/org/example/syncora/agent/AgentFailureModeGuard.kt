package org.example.syncora.agent

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Prompt 8d's failure-mode hardening (`ESN_RRL_Agent_Task_Prompts.md`,
 * building on 8a's caps, 8b's exchange-stop-independence verification, and
 * 8c's kill switch - all left untouched by this class): the *fourth* line
 * of defense, sitting at the exact same seam [AgentKillSwitch] and
 * [PositionOrderEmitter] already occupy, between [AgentOrchestrator] and
 * order emission, whose entire job is making sure that a market-data feed
 * dropout, stale klines, RLS covariance blow-up, or a NaN [PolicyEngine]
 * output can never reach the order path as a garbage order - and never as
 * an unhandled exception either, which is just as unsafe in practice.
 *
 * ### Why this is a distinct layer from [AgentKillSwitch]
 * [AgentKillSwitch] answers "can an operator or watchdog halt the agent
 * even if the UI is stuck". This class answers a different question: "can
 * the agent's *own inputs and internal numerics* go bad in a way nothing
 * upstream would catch". Nothing here depends on an operator noticing
 * anything - every check runs on every bar (or, for feed dropout, on every
 * [checkFeedHealth] tick), unconditionally.
 *
 * ### The four failure modes, and why each gets its own check
 * 1. **Market-data feed dropout** - no bar has closed for longer than
 *    [maxFeedGapMs]. Unlike the other three, this cannot be detected from
 *    *inside* [onLiveBarClose], because if the feed has genuinely gone
 *    silent, [onLiveBarClose] is never called at all - there is no bar to
 *    hand it. [checkFeedHealth] is the counterpart: a caller wires it to a
 *    periodic ticker (independent of bar arrival, the same "not gated on
 *    the thing that might be broken" shape [AgentKillSwitch.engage] uses
 *    for the UI thread) so a silent feed is still caught.
 * 2. **Stale klines** - a bar *did* arrive, but its own close time is
 *    already far in the past relative to wall clock, meaning whatever
 *    produced it is behind, buffering, or serving cached data - trading on
 *    it would be trading on a snapshot of the market that no longer
 *    exists. Caught in [onLiveBarClose] *before* the bar is ever handed to
 *    [AgentOrchestrator.processLiveBar] - a stale bar must not even be
 *    allowed to update the reservoir/readout/policy state, since Phase
 *    5-6's online training assumes every bar it sees is a genuine,
 *    current close.
 * 3. **RLS divergence (covariance blow-up)** - classic RLS "covariance
 *    windup": with a forgetting factor `< 1`, a stretch of
 *    under-exciting/degenerate regressor input lets `P` grow essentially
 *    unbounded ([ReadoutTrainer.update]'s downdate only ever *shrinks* `P`
 *    in the direction the current regressor points; a regressor that
 *    stays near zero for long enough leaves every other direction
 *    unchecked while `P` is divided by `lambda < 1` every single step).
 *    [ReadoutTrainer.isStable] alone only catches non-finite entries, which
 *    is *after* the numbers are already useless - this check catches the
 *    blow-up itself, via [maxCovarianceMagnitude], while it is still
 *    finite but has already left any regime the RLS math was designed for.
 * 4. **NaN output from [PolicyEngine]** - `f_t` itself is non-finite.
 *    [PolicyEngine.step]'s `tanh` guarantees `f_t ∈ [-1, 1]` only when its
 *    inputs are finite; a corrupted weight or trace (upstream of whatever
 *    let it go non-finite in the first place) breaks that guarantee.
 *    Critically, [PositionOrderEmitter.onTargetPosition]'s own
 *    `require(targetPosition in -1f..1f)` does **not** safely handle this
 *    case on its own: `NaN in -1f..1f` is `false` (every comparison
 *    against `NaN` is), so that `require` *throws* rather than degrading
 *    gracefully - an uncaught [IllegalArgumentException] on whatever
 *    thread is driving live bars is exactly the "unhandled" outcome the
 *    exit criterion rules out. This class's whole point on this failure
 *    mode is checking [AgentOrchestrator.DecisionLog.position] for
 *    finiteness *before* it is ever passed to [PositionOrderEmitter], so
 *    that `require` is never reached with a bad value in the first place.
 *
 * ### The two allowed outcomes, and only these two
 * Every path through [onLiveBarClose] and [checkFeedHealth] ends in
 * exactly one of [Outcome.Processed] (bar handled normally, an order may
 * or may not have been emitted per [PositionOrderEmitter]'s own no-op
 * rules), [Outcome.NoOrderSent] (the bar - or, for a dropout, the current
 * moment - was rejected before ever reaching [orderEmitter]), or
 * [Outcome.Flattened] ([orderEmitter] was called with a target position of
 * exactly `0f`, per [PositionOrderEmitter]'s own "target implied by `f_t`
 * collapses to ~0" flatten case - same mechanism [AgentKillSwitch.engage]
 * uses). There is no fourth path, and no path that lets a checked
 * failure's raw, untrusted value reach [orderEmitter].
 *
 * ### Latching: transient vs. terminal failure modes
 * Feed dropout and stale klines are *transient* - the feed can recover, a
 * fresher bar can arrive - so neither latches this guard shut; the very
 * next healthy bar is processed normally. RLS divergence and a NaN policy
 * output are different: both mean the *state itself* the orchestrator
 * would keep building on ([ReadoutTrainer]'s `W_out`/`P`, [PolicyEngine]'s
 * weights/traces) is already corrupted, so every subsequent bar computed
 * from that same state is untrustworthy too, not just the one that first
 * revealed it. [isHalted] latches permanently the first time either of
 * those two is detected - mirroring [AgentKillSwitch]'s own one-way-trip
 * design - and every [onLiveBarClose] call after that point short-circuits
 * to [Outcome.NoOrderSent] without touching [orchestrator] again. Recovery
 * requires a fresh [AgentOrchestrator] (e.g. restored from a
 * pre-divergence [AgentCheckpoint]), not a resume call on this instance -
 * [resetForTesting] exists solely so one test class can exercise multiple
 * scenarios against one guard instance, the same carve-out
 * [AgentKillSwitch.resetForTesting] documents.
 *
 * @param orchestrator The Phase 1-5 chain this guard fronts - same
 *   instance a caller would otherwise drive directly via
 *   [AgentOrchestrator.processLiveBar].
 * @param orderEmitter Where a bar's target position (or a flatten) is
 *   actually sent once every check has passed - see [PositionOrderEmitter]
 *   for the caps (Prompt 8a) that apply independently, downstream of this
 *   class.
 * @param clock Wall-clock source for staleness/dropout comparisons -
 *   defaults to [System.currentTimeMillis], overridable so a test can
 *   drive time deterministically without `Thread.sleep`.
 * @param maxKlineStalenessMs A bar whose `kline.startTime` is more than
 *   this far behind [clock] is rejected as stale klines (failure mode 2)
 *   before it reaches [orchestrator] at all.
 * @param maxFeedGapMs If [checkFeedHealth] is called more than this long
 *   after the last bar [onLiveBarClose] actually processed, that is
 *   treated as a market-data feed dropout (failure mode 1).
 * @param maxCovarianceMagnitude The largest `|entry|` [ReadoutTrainer]'s
 *   RLS covariance matrix is allowed to reach before this guard treats it
 *   as diverged (failure mode 3) - see class doc's "covariance windup"
 *   explanation for why this can't simply be "still finite, so it's fine".
 */
class AgentFailureModeGuard(
    private val orchestrator: AgentOrchestrator,
    private val orderEmitter: PositionOrderEmitter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxKlineStalenessMs: Long = DEFAULT_MAX_KLINE_STALENESS_MS,
    private val maxFeedGapMs: Long = DEFAULT_MAX_FEED_GAP_MS,
    private val maxCovarianceMagnitude: Float = DEFAULT_MAX_COVARIANCE_MAGNITUDE,
) {
    companion object {
        const val DEFAULT_MAX_KLINE_STALENESS_MS = 5 * 60_000L // 5 minutes
        const val DEFAULT_MAX_FEED_GAP_MS = 5 * 60_000L // 5 minutes
        const val DEFAULT_MAX_COVARIANCE_MAGNITUDE = 1.0e6f
        private const val TAG = "AgentFailureModeGuard"
    }

    /** What happened to one [onLiveBarClose]/[checkFeedHealth] call - see class doc. */
    sealed class Outcome {
        /** The bar was processed normally through [AgentOrchestrator.processLiveBar] and handed to [orderEmitter] - no failure mode was triggered. */
        data class Processed(val decision: AgentOrchestrator.DecisionLog) : Outcome()

        /** Rejected before [orderEmitter] was ever called - "no order sent", per the exit criterion. [reason] is free-text, logged only. */
        data class NoOrderSent(val reason: String) : Outcome()

        /** [orderEmitter] was called with a target position of `0f` - a flatten, per the exit criterion. [reason] is free-text, logged only. */
        data class Flattened(val reason: String) : Outcome()
    }

    private val haltedFlag = AtomicBoolean(false)
    private val haltReasonRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

    @Volatile
    private var lastBarProcessedAtMs: Long? = null

    /** `true` once RLS divergence or a NaN policy output has been detected - see class doc's "Latching" section. Never becomes `false` again outside of [resetForTesting]. */
    val isHalted: Boolean get() = haltedFlag.get()

    /** Free-text reason [isHalted] became `true`, or `null` if it never has. */
    val haltReason: String? get() = haltReasonRef.get()

    /**
     * Failure mode 1 (market-data feed dropout): call this periodically,
     * independent of bar arrival - see class doc for why detecting a
     * *silent* feed cannot be done from inside [onLiveBarClose] alone. A
     * no-op ([Outcome.NoOrderSent] with an explanatory reason) if no bar
     * has ever been processed yet (nothing to have gone stale relative
     * to) or if [isHalted].
     */
    fun checkFeedHealth(nowMs: Long = clock()): Outcome {
        if (isHalted) return Outcome.NoOrderSent("guard is halted: ${haltReason ?: "unknown"}")

        val lastMs = lastBarProcessedAtMs
            ?: return Outcome.NoOrderSent("no bar processed yet - feed health check is a no-op until the first bar arrives")

        val gapMs = nowMs - lastMs
        if (gapMs <= maxFeedGapMs) {
            return Outcome.NoOrderSent("feed healthy: ${gapMs}ms since the last processed bar, within ${maxFeedGapMs}ms")
        }

        return flatten("market-data feed dropout: ${gapMs}ms since the last processed bar exceeds $maxFeedGapMs" + "ms")
    }

    /**
     * Drives one live bar-close through every one of this guard's checks,
     * in order: [isHalted] short-circuit, then failure mode 2 (stale
     * klines, checked *before* [orchestrator] sees the bar at all), then
     * [AgentOrchestrator.processLiveBar] itself, then failure mode 4 (NaN
     * policy output) and failure mode 3 (RLS divergence) against that
     * call's result - only once every check has passed is
     * [orderEmitter] ever invoked, and only ever with either the bar's
     * genuine, validated target position or an explicit `0f` flatten.
     *
     * @param liveBarClose One bar-close event - same shape [AgentOrchestrator.processLiveBar] itself takes.
     * @param state This session's [AgentOrchestrator.LiveInferenceState] - same contract as [AgentOrchestrator.processLiveBar]'s own parameter: construct once per session, reuse across every call.
     * @param fundingRateAt Same contract as [AgentOrchestrator.processLiveBar]'s parameter of the same name.
     * @param feeRate Same contract as [AgentOrchestrator.processLiveBar]'s parameter of the same name.
     */
    fun onLiveBarClose(
        liveBarClose: AgentOrchestrator.LiveBarClose,
        state: AgentOrchestrator.LiveInferenceState,
        fundingRateAt: (nowMs: Long) -> Double = { 0.0 },
        feeRate: Double = 0.0,
    ): Outcome {
        if (isHalted) return Outcome.NoOrderSent("guard is halted: ${haltReason ?: "unknown"}")

        val nowMs = clock()

        // Failure mode 2: stale klines - rejected before the orchestrator
        // ever sees this bar, so a stale close cannot pollute the
        // reservoir/readout/policy's online-learned state.
        val klineAgeMs = nowMs - liveBarClose.kline.startTime
        if (klineAgeMs > maxKlineStalenessMs) {
            return Outcome.NoOrderSent(
                "stale klines: bar close is ${klineAgeMs}ms old (startTime=${liveBarClose.kline.startTime}), exceeds ${maxKlineStalenessMs}ms",
            )
        }

        val decision = orchestrator.processLiveBar(
            liveBarClose = liveBarClose,
            state = state,
            fundingRateAt = fundingRateAt,
            feeRate = feeRate,
        )
        lastBarProcessedAtMs = nowMs

        // Failure mode 4: NaN (or otherwise non-finite) policy output -
        // checked before this value is ever handed to orderEmitter, whose
        // own bounds check does not degrade gracefully for NaN (see class
        // doc).
        if (!decision.position.isFinite()) {
            return halt("PolicyEngine emitted a non-finite position (${decision.position}) on bar ${decision.barIndex}")
        }

        // Failure mode 3: RLS divergence (covariance blow-up) - checked
        // against the trainer's state as of this same bar.
        val covariance = orchestrator.currentCheckpoint().readout.covariance
        for (entry in covariance) {
            if (!entry.isFinite()) {
                return halt("RLS covariance diverged to a non-finite entry on bar ${decision.barIndex}")
            }
        }
        val maxMagnitude = covariance.maxOfOrNull { abs(it) } ?: 0f
        if (maxMagnitude > maxCovarianceMagnitude) {
            return halt(
                "RLS covariance blow-up on bar ${decision.barIndex}: max |entry| $maxMagnitude exceeds $maxCovarianceMagnitude",
            )
        }

        orderEmitter.onTargetPosition(decision.position)
        return Outcome.Processed(decision)
    }

    /** Common flatten path for both [checkFeedHealth] (transient) and [halt] (terminal) - see class doc's two-outcome contract. */
    private fun flatten(reason: String): Outcome.Flattened {
        Log.w(TAG, "failure mode triggered - flattening position: $reason")
        orderEmitter.onTargetPosition(0f)
        return Outcome.Flattened(reason)
    }

    /** Latches [isHalted] (failure modes 3 and 4 only - see class doc's "Latching" section) and flattens. */
    private fun halt(reason: String): Outcome.Flattened {
        haltReasonRef.set(reason)
        haltedFlag.set(true)
        Log.e(TAG, "guard halted (terminal): $reason")
        return flatten(reason)
    }

    /**
     * Test-only escape hatch back to un-halted - see class doc's
     * "Latching" section. Never called from production code.
     */
    fun resetForTesting() {
        haltedFlag.set(false)
        haltReasonRef.set(null)
        lastBarProcessedAtMs = null
    }
}
