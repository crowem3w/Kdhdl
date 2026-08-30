package org.example.syncora.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 7 (`ESN_RRL_Agent_Task_Prompts.md` Prompt 8, "Guardrail Hardening
 * (pre-live)"): the defense-in-depth safety layers that sit **outside and
 * independent of** [PolicyEngine], per `docs/agent-design-contract.md` §2's
 * "third, orchestrator-level layer, independent of both of the above [the
 * policy's own risk view and the exchange-side stop], per the same
 * principle: a bug in one layer must not be able to remove the other two."
 *
 * Nothing in this file imports [org.example.syncora.bitget.RiskSettingsStore],
 * [org.example.syncora.bitget.StopLossGuard], or
 * [org.example.syncora.bitget.StopLossOrder] - same reasoning
 * [AgentOrchestrator]'s own class doc gives for "zero live or paper
 * orders": the exchange-side stop (design doc §2's floor) is placed and
 * maintained entirely independently of the agent, and this guardrail layer
 * is *additive on top of* that stop, never a substitute for it and never
 * wired to touch it. `AgentGuardrailIndependenceTest` asserts this
 * absence-of-coupling by reflection so it stays true as this file grows,
 * not just at the moment it was written.
 */

/**
 * Prompt 8's "single control that halts the [AgentOrchestrator] and
 * flattens the current position, reachable even if the UI thread is busy
 * or unresponsive."
 *
 * Deliberately just two [java.util.concurrent.atomic] fields - no
 * [kotlinx.coroutines.CoroutineScope], no suspend function, no dispatcher
 * hop anywhere in this class. That is what "reachable even if the UI
 * thread is busy or unresponsive" means in practice: [trigger] is a plain,
 * synchronous, wait-free call that returns immediately on whatever thread
 * calls it (a UI click handler, a background watchdog, a test), and
 * [isTriggered] is a plain volatile read any other thread can poll without
 * ever blocking on the UI thread, an Android `Looper`, or anything else
 * that could itself be the thing that's busy or unresponsive.
 *
 * This class only carries the *signal*; it does not itself flatten
 * anything. The actual flattening action lives in whatever reads
 * [isTriggered] - [GuardrailSupervisor.evaluateDecision] on the next bar,
 * and [HardenedAgentLiveSession.engageKillSwitch] *immediately*,
 * synchronously, on the calling thread - see that method's doc for why
 * "halts ... and flattens" doesn't have to wait for the next bar close.
 */
class AgentKillSwitch {
    private val triggered = AtomicBoolean(false)
    private val reasonRef = AtomicReference<String?>(null)

    /** True once [trigger] has been called; never resets itself - only [reset] clears it. */
    fun isTriggered(): Boolean = triggered.get()

    /** The reason passed to the [trigger] call that first engaged the switch, or null if never triggered. */
    fun reason(): String? = reasonRef.get()

    /**
     * Engages the kill switch. Idempotent and safe to call from any
     * thread, any number of times, concurrently - the *first* caller's
     * [reason] wins (via `compareAndSet`, not overwritten by a later
     * call), since the first thing that tripped it is almost always the
     * most diagnostically useful one, and [triggered] itself only ever
     * moves one way (false -> true) until an explicit [reset].
     */
    fun trigger(reason: String) {
        reasonRef.compareAndSet(null, reason)
        triggered.set(true)
    }

    /**
     * Clears the switch back to a healthy state - an explicit, deliberate
     * operator action (never called automatically by anything in this
     * file), matching Prompt 9's "gated behind an explicit, deliberate
     * user action" framing for anything that resumes trading after a halt.
     */
    fun reset() {
        triggered.set(false)
        reasonRef.set(null)
    }
}

/**
 * Prompt 8's "hard position-size and notional caps enforced at the
 * orchestrator level (so a policy bug cannot bypass them)".
 *
 * [PolicyEngine.step]'s own `tanh` already guarantees `f_t ∈ [-1, 1]]` -
 * see that class's doc - but that is a *soft*, self-imposed bound coming
 * from inside the very component this cap exists to guard against. This
 * class is a second, independent ceiling, evaluated entirely outside
 * [PolicyEngine], that a bug anywhere in the policy or readout (a
 * corrupted weight, a runaway forecast feeding into it, ...) cannot widen,
 * disable, or bypass - the same "a bug in one layer must not be able to
 * remove the other two" principle `docs/agent-design-contract.md` §2
 * states for this exact layer.
 *
 * @param maxPositionFraction Hard ceiling on `|f_t|`, independent of and typically tighter than [PolicyEngine]'s own `[-1, 1]` bound - e.g. `0.5` caps the agent at half of whatever [PositionOrderEmitter.maxPositionSizeBaseCoin] represents, regardless of what the policy would otherwise choose. Must be in `(0, 1]`.
 * @param maxNotionalBaseCoin Hard ceiling on the position's notional value (`size x price`, in the same base-coin-scaled units [PositionOrderEmitter.maxPositionSizeBaseCoin] uses), independent of price - defaults to unbounded (fraction cap only) so a caller that only wants a fraction ceiling doesn't have to compute a notional one. Must be `> 0` if finite.
 */
data class PositionCaps(
    val maxPositionFraction: Float = 1f,
    val maxNotionalBaseCoin: Double = Double.POSITIVE_INFINITY,
) {
    init {
        require(maxPositionFraction > 0f && maxPositionFraction <= 1f) {
            "maxPositionFraction must be in (0, 1], was $maxPositionFraction"
        }
        require(maxNotionalBaseCoin > 0.0) {
            "maxNotionalBaseCoin must be > 0, was $maxNotionalBaseCoin"
        }
    }

    /**
     * Clamps [targetPosition] to satisfy both [maxPositionFraction] and
     * [maxNotionalBaseCoin] at once, independent of anything [PolicyEngine]
     * computed. [referencePrice] and [positionSizeScaleBaseCoin] (the exact
     * same [PositionOrderEmitter.maxPositionSizeBaseCoin] scale the order
     * path will use to size the resulting order) convert the notional
     * ceiling into an equivalent fraction ceiling, so both bounds reduce to
     * a single `coerceIn` on the fraction itself - the value that actually
     * reaches [PositionOrderEmitter.onTargetPosition].
     *
     * Never widens [targetPosition] - a policy output already inside both
     * caps passes through with its exact sign and magnitude unchanged.
     *
     * @param targetPosition `f_t`, already known finite and in `[-1, 1]` by the time this is called - see [GuardrailSupervisor.evaluateDecision], which checks that *before* calling this.
     * @param referencePrice The bar's reference (mid) price - non-positive or non-finite values are treated as "can't evaluate the notional cap right now" and only [maxPositionFraction] is applied, never as licence to skip capping entirely.
     * @param positionSizeScaleBaseCoin [PositionOrderEmitter.maxPositionSizeBaseCoin] - non-positive or non-finite values are treated the same as an unusable [referencePrice], above.
     */
    fun clamp(targetPosition: Float, referencePrice: Double, positionSizeScaleBaseCoin: Double): Float {
        var boundFraction = maxPositionFraction.toDouble()
        if (
            maxNotionalBaseCoin.isFinite() &&
            referencePrice > 0.0 && referencePrice.isFinite() &&
            positionSizeScaleBaseCoin > 0.0 && positionSizeScaleBaseCoin.isFinite()
        ) {
            val notionalImpliedFraction = maxNotionalBaseCoin / (positionSizeScaleBaseCoin * referencePrice)
            boundFraction = minOf(boundFraction, notionalImpliedFraction)
        }
        val bound = boundFraction.coerceIn(0.0, 1.0).toFloat()
        return targetPosition.coerceIn(-bound, bound)
    }
}

/**
 * What [GuardrailSupervisor] decided a live bar should result in - the
 * closed set of outcomes Prompt 8 requires: "the orchestrator's response is
 * either 'no order sent' or 'flatten the position' - never a garbage or
 * unhandled order reaching the exchange path." [Trade] is the fourth,
 * healthy-path outcome (a real, capped target position); the other three
 * are exactly the two Prompt 8 names, split out by cause for logging/audit.
 */
sealed class GuardedAction {
    /** Healthy path: [position] (already capped by [PositionCaps]) is safe to hand to [PositionOrderEmitter.onTargetPosition]. */
    data class Trade(val position: Float) : GuardedAction()

    /** "No order sent" - this bar is skipped entirely; [PositionOrderEmitter] is never called. */
    data class NoOrder(val reason: String) : GuardedAction()

    /** "Flatten the position" - [PositionOrderEmitter.onTargetPosition] is called with `0f`, regardless of what (if anything) the policy computed. */
    data class Flatten(val reason: String) : GuardedAction()
}

/**
 * Prompt 8's failure-mode gates, evaluated by [HardenedAgentLiveSession]
 * around every live bar. Pure and synchronous - like every engine since
 * Phase 1, this class makes no network call, no coroutine suspension, and
 * no Android framework call, so it can be unit-tested directly against
 * fixture inputs (`GuardrailSupervisorTest`) with no live feed, no
 * orchestrator, and no dispatcher involved.
 *
 * @param caps Prompt 8's hard position/notional caps - applied only on the healthy [GuardedAction.Trade] path; a bar that fails any other check never reaches [PositionCaps.clamp].
 * @param killSwitch Prompt 8's kill switch - [evaluateDecision] both *reads* it (an already-engaged switch always forces [GuardedAction.Flatten]) and *writes* it (several failure modes engage it themselves, escalating a single bad bar into "stop trading until an operator explicitly [AgentKillSwitch.reset] this", not just a one-bar skip) - see each check's own comment for which failure modes escalate and which don't.
 * @param maxBarStalenessMultiplier How many multiples of the expected bar interval a kline/feed is allowed to lag before it's treated as stale/dropped - see [checkFeedFreshness]/[checkFeedDropout]. Must be `> 1.0` (anything `<= 1.0` would flag every bar, including a perfectly healthy one, as stale).
 * @param maxCovarianceMagnitude The ceiling [evaluateDecision] compares [readoutCovarianceMagnitude] against - see [ReadoutTrainer.covarianceMagnitude]'s doc for why this, not [AgentOrchestrator.isStable], is what actually catches "RLS divergence (e.g. covariance blow-up)".
 */
class GuardrailSupervisor(
    private val caps: PositionCaps,
    private val killSwitch: AgentKillSwitch,
    private val maxBarStalenessMultiplier: Double = DEFAULT_MAX_BAR_STALENESS_MULTIPLIER,
    private val maxCovarianceMagnitude: Float = DEFAULT_MAX_COVARIANCE_MAGNITUDE,
) {
    companion object {
        /** A kline/feed lagging more than 3x the expected bar interval is treated as stale/dropped out - generous enough to absorb ordinary jitter, tight enough that "several bars behind" is caught well before it could matter. */
        const val DEFAULT_MAX_BAR_STALENESS_MULTIPLIER = 3.0

        /** [ReadoutTrainer]'s covariance is initialized to `DEFAULT_INITIAL_COVARIANCE_SCALE * I` (100). A healthy run stays within a few orders of magnitude of that; four orders above it (1e6) is deep into "this is not converging, it's diverging" territory well before individual entries risk overflowing to infinity. */
        const val DEFAULT_MAX_COVARIANCE_MAGNITUDE = 1.0e6f

        private const val POSITION_BOUND_EPS = 1e-4f
    }

    init {
        require(maxBarStalenessMultiplier > 1.0) {
            "maxBarStalenessMultiplier must be > 1.0, was $maxBarStalenessMultiplier"
        }
        require(maxCovarianceMagnitude > 0f) {
            "maxCovarianceMagnitude must be > 0, was $maxCovarianceMagnitude"
        }
    }

    /**
     * Prompt 8's "stale klines" failure mode: the bar's own [klineStartTime]
     * is far enough behind [nowMs] that trading on it would mean acting on
     * data the market has already moved past. Returns a [GuardedAction] to
     * apply (always [GuardedAction.NoOrder] here - stale data isn't a
     * reason to change an existing position, just a reason not to act on
     * this particular bar) or `null` if the bar is fresh enough to proceed
     * to the full inference chain.
     *
     * Does *not* touch [killSwitch] - one stale bar in an otherwise-healthy
     * feed is routine (a slow tick, a brief hiccup) and shouldn't halt an
     * entire session; see [checkFeedDropout] for the escalating case
     * (staleness that persists long enough to look like the feed died).
     */
    fun checkFeedFreshness(nowMs: Long, klineStartTime: Long, expectedBarIntervalMs: Long): GuardedAction? {
        require(expectedBarIntervalMs > 0) { "expectedBarIntervalMs must be > 0, was $expectedBarIntervalMs" }
        val ageMs = nowMs - klineStartTime
        val maxAgeMs = (expectedBarIntervalMs * maxBarStalenessMultiplier).toLong()
        if (ageMs <= maxAgeMs) return null
        return GuardedAction.NoOrder(
            "stale kline: bar is ${ageMs}ms old, exceeds ${maxBarStalenessMultiplier}x the expected " +
                "${expectedBarIntervalMs}ms bar interval (${maxAgeMs}ms)",
        )
    }

    /**
     * Prompt 8's "market-data feed dropout" failure mode: no bar-close
     * event has reached this session at all for far longer than the feed
     * should ever go quiet, meaning the feed itself - not just one bar - is
     * suspect. Unlike [checkFeedFreshness], this *does* escalate: a
     * dropout this long could mean any open position has been unmonitored
     * and unmanaged for a while, so the safe response is
     * [GuardedAction.Flatten], not merely skipping one bar.
     *
     * @param lastBarCloseReceivedAtMs Wall-clock time this session last actually received a bar-close event, or `null` if none has arrived yet this session (cold start - not a dropout, since there is nothing to have dropped out from).
     */
    fun checkFeedDropout(nowMs: Long, lastBarCloseReceivedAtMs: Long?, expectedBarIntervalMs: Long): GuardedAction? {
        require(expectedBarIntervalMs > 0) { "expectedBarIntervalMs must be > 0, was $expectedBarIntervalMs" }
        if (lastBarCloseReceivedAtMs == null) return null
        val sinceMs = nowMs - lastBarCloseReceivedAtMs
        val maxGapMs = (expectedBarIntervalMs * maxBarStalenessMultiplier).toLong()
        if (sinceMs <= maxGapMs) return null
        val reason = "market-data feed dropout: no bar-close received for ${sinceMs}ms, exceeds " +
            "${maxBarStalenessMultiplier}x the expected ${expectedBarIntervalMs}ms bar interval (${maxGapMs}ms)"
        killSwitch.trigger(reason)
        return GuardedAction.Flatten(reason)
    }

    /**
     * Prompt 8's post-decision gate: given one bar's already-computed
     * [AgentOrchestrator.DecisionLog] plus this bar's read of
     * [AgentOrchestrator.isStable]/[AgentOrchestrator.readoutCovarianceMagnitude],
     * decides the bar's [GuardedAction] - the healthy [GuardedAction.Trade]
     * path (through [PositionCaps.clamp]) or one of the two safe fallbacks,
     * covering Prompt 8's remaining two named failure modes ("RLS
     * divergence", "NaN output from PolicyEngine") plus the general case of
     * an already-engaged [killSwitch].
     *
     * Checked in this order, first match wins:
     * 1. [killSwitch] already engaged (by this call, a prior
     *    [checkFeedDropout], or [HardenedAgentLiveSession.engageKillSwitch])
     *    -> [GuardedAction.Flatten], unconditionally - a triggered kill
     *    switch overrides everything else, including a perfectly healthy
     *    decision.
     * 2. [AgentOrchestrator.DecisionLog.position] non-finite (Prompt 8's
     *    "NaN output from PolicyEngine") -> engages [killSwitch] and
     *    returns [GuardedAction.Flatten]. Escalates (not just a one-bar
     *    skip) because a NaN position means the policy's internal state is
     *    already corrupted - the *next* bar's output can't be trusted
     *    either without an operator first investigating and [AgentKillSwitch.reset]-ing.
     * 3. [AgentOrchestrator.DecisionLog.position] finite but outside
     *    `[-1, 1]` (defense-in-depth against [PolicyEngine]'s own `tanh`
     *    guarantee somehow not holding) -> same escalation as case 2.
     * 4. `!orchestratorStable` ([AgentOrchestrator.isStable] false - a
     *    non-finite weight or trace *inside* [ReadoutTrainer]/[PolicyEngine]
     *    that hasn't yet produced a non-finite `f_t` this particular bar)
     *    -> same escalation as case 2.
     * 5. `readoutCovarianceMagnitude` non-finite or `>` [maxCovarianceMagnitude]
     *    (Prompt 8's "RLS divergence (e.g. covariance blow-up)") -> same
     *    escalation as case 2 - a diverged covariance means every future
     *    [ReadoutTrainer.predict] this session makes is suspect, not just
     *    this bar's.
     * 6. Everything above passed -> [GuardedAction.Trade] with
     *    [PositionCaps.clamp] applied to [AgentOrchestrator.DecisionLog.position].
     */
    fun evaluateDecision(
        decision: AgentOrchestrator.DecisionLog,
        orchestratorStable: Boolean,
        readoutCovarianceMagnitude: Float,
        referencePrice: Double,
        positionSizeScaleBaseCoin: Double,
    ): GuardedAction {
        if (killSwitch.isTriggered()) {
            return GuardedAction.Flatten("kill switch engaged: ${killSwitch.reason() ?: "unknown reason"}")
        }

        val position = decision.position
        if (!position.isFinite()) {
            val reason = "PolicyEngine produced a non-finite position ($position) at bar ${decision.barIndex}"
            killSwitch.trigger(reason)
            return GuardedAction.Flatten(reason)
        }
        if (position < -1f - POSITION_BOUND_EPS || position > 1f + POSITION_BOUND_EPS) {
            val reason = "PolicyEngine produced an out-of-bounds position ($position) at bar ${decision.barIndex}"
            killSwitch.trigger(reason)
            return GuardedAction.Flatten(reason)
        }
        if (!orchestratorStable) {
            val reason = "readout/policy internal state is non-finite at bar ${decision.barIndex}"
            killSwitch.trigger(reason)
            return GuardedAction.Flatten(reason)
        }
        if (!readoutCovarianceMagnitude.isFinite() || readoutCovarianceMagnitude > maxCovarianceMagnitude) {
            val reason = "RLS covariance magnitude ($readoutCovarianceMagnitude) exceeds ceiling " +
                "($maxCovarianceMagnitude) at bar ${decision.barIndex}"
            killSwitch.trigger(reason)
            return GuardedAction.Flatten(reason)
        }

        val clamped = caps.clamp(position, referencePrice, positionSizeScaleBaseCoin)
        return GuardedAction.Trade(clamped)
    }
}
