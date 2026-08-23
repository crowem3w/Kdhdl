package org.example.syncora.agent

import org.example.syncora.bitget.FundingSchedule
import java.util.concurrent.TimeUnit

/**
 * A single transition within an assembled rollout window: the raw fields
 * pulled from [ExperienceLogStore] via [ResolvedExperience], plus the GAE
 * outputs [RolloutWindowBuilder] computes over the window it belongs to.
 *
 * [advantage] and [valueTarget] default to `0.0` because they don't exist
 * until the whole window's backward GAE pass has run - step `t`'s advantage
 * depends on every step after it in the same window - so a freshly
 * constructed [RolloutStep] is always immediately overwritten by
 * [RolloutWindowBuilder.buildWindow] before it's handed back to a caller.
 */
data class RolloutStep(
    val source: ResolvedExperience,
    val advantage: Double = 0.0,
    /** GAE's TD(λ) return target, `advantage + V(s_t)` - what the critic's next training pass regresses toward. */
    val valueTarget: Double = 0.0,
) {
    val timestampMs: Long get() = source.timestampMs
    val state: DoubleArray get() = source.state
    val action: Double get() = source.action
    val logProb: Double get() = source.logProb
    val valueEstimate: Double get() = source.valueEstimate
    val reward: Double get() = source.reward
}

/**
 * One fixed-length rollout window (design doc §3.6: "chop the continuous
 * log into fixed-length rollout windows ... and bootstrap the value
 * estimate at each cut using the critic's V(s_T), rather than treating the
 * cut as an episode end").
 *
 * [steps] is ordered oldest-first, and every step's [RolloutStep.advantage]
 * / [RolloutStep.valueTarget] are already filled in - a caller assembling a
 * PPO minibatch buffer can read them straight off, no further pass needed.
 */
data class RolloutWindow(
    val symbol: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val steps: List<RolloutStep>,
    /** The critic's `V(s_T)` used to bootstrap this window's final-step advantage - see class doc. */
    val bootstrapValue: Double,
)

/**
 * Implements design doc §3.3 step 1-2 / §3.6: pulls every fully-resolved
 * experience row logged since the last successful training run, chunks it
 * into fixed one-day windows aligned to Bitget's 8-hour funding settlement
 * grid, and runs Generalized Advantage Estimation (GAE-λ) over each window
 * independently - bootstrapping the terminal value at each window boundary
 * from the critic instead of treating the cut as an episode termination.
 *
 * **Why "one-day" is automatically funding-cycle-aligned.**
 * [FundingSchedule] settles every 8 hours on a grid anchored at UTC epoch
 * zero (00:00 / 08:00 / 16:00 UTC). A day is exactly three funding
 * intervals, and epoch zero is itself a settlement instant, so *any*
 * UTC-midnight-aligned day boundary already sits exactly on a funding
 * settlement - no separate alignment step is needed beyond flooring each
 * timestamp to a multiple of [windowLengthMs], the same
 * `ts - floorMod(ts, interval)` trick [FundingSchedule.previousSettlement]
 * uses. [init] asserts [windowLengthMs] is a whole multiple of
 * [FundingSchedule.INTERVAL_MS] so this alignment can't silently drift if
 * the window length is ever reconfigured.
 *
 * **Why GAE needs a critic call at all, given the rows already store `V`.**
 * Every [ResolvedExperience] already carries `V(s_t)` - the value the
 * critic assigned *at decision time* - which covers every step in a window
 * except the last one. That last step's bootstrap target is `V(s_T)` for
 * the state *after* the window's final logged transition
 * ([ResolvedExperience.nextState] of the last row), and that state was
 * never itself a decision point, so it was never logged with a value
 * estimate. [criticValueFn] is how the caller supplies one fresh forward
 * pass through the (candidate) critic for exactly that one state per
 * window; every other value in the recursion reuses what's already stored,
 * exactly as the task requires ("computes GAE ... using the stored reward
 * and value estimates, bootstrapping the value at each window boundary via
 * the critic's V(s_T)").
 *
 * **Non-episodic semantics.** A window boundary is a truncation, not a
 * termination: [criticValueFn]'s output is used exactly where a classic
 * episodic GAE implementation would substitute `0` for a true terminal
 * state. That's what lets credit assignment cross the cut - e.g. for a
 * position opened near the end of one window whose funding cost only
 * resolves into the next one - while still keeping each window a bounded,
 * independently-processed chunk for the PPO minibatch epochs that consume
 * it (§3.3 step 3).
 */
class RolloutWindowBuilder(
    private val experienceLogStore: ExperienceLogStore,
    /** Forward pass through the critic for a single state - see class doc's "why a critic call at all" section. */
    private val criticValueFn: (DoubleArray) -> Double,
    private val windowLengthMs: Long = TimeUnit.DAYS.toMillis(1),
    private val gamma: Double = 0.99,
    private val lambda: Double = 0.95,
) {
    init {
        require(windowLengthMs > 0) { "windowLengthMs must be positive, was $windowLengthMs" }
        require(windowLengthMs % FundingSchedule.INTERVAL_MS == 0L) {
            "windowLengthMs ($windowLengthMs) must be a whole multiple of the funding settlement " +
                "interval (${FundingSchedule.INTERVAL_MS}) or window boundaries drift off the settlement grid"
        }
        require(gamma in 0.0..1.0) { "gamma must be in [0, 1], was $gamma" }
        require(lambda in 0.0..1.0) { "lambda must be in [0, 1], was $lambda" }
    }

    /**
     * Pulls every [RewardStatus.RESOLVED] row logged at or after [sinceMs]
     * - normally the timestamp of the last successful promotion (design
     * doc §3.3 step 1: "pull all resolved rows logged since the last
     * successful promotion") - and returns it as fully GAE-annotated
     * [RolloutWindow]s, oldest window first.
     *
     * Rows are grouped by symbol as well as by window, so a future
     * multi-asset build (design doc §6) can't have one symbol's transitions
     * silently bootstrap or discount against another's. A window with zero
     * resolved rows never appears in the result - there's simply no entry
     * for it, rather than a placeholder with empty [RolloutWindow.steps].
     */
    fun build(sinceMs: Long): List<RolloutWindow> {
        val rows = experienceLogStore.resolvedRowsSince(sinceMs)
        if (rows.isEmpty()) return emptyList()

        return rows
            .groupBy { row -> row.symbol to windowStartFor(row.timestampMs) }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (key, windowRows) -> buildWindow(symbol = key.first, windowStartMs = key.second, rows = windowRows) }
    }

    /**
     * Assembles one window's [RolloutStep]s and runs the backward GAE-λ
     * recursion over them:
     *
     * ```
     * δ_i  = r_i + γ·V(s_{i+1}) - V(s_i)
     * A_i  = δ_i + γ·λ·A_{i+1}
     * ```
     *
     * where `V(s_{i+1})` for every step but the last reuses the *next*
     * row's already-stored `V(s_t)` (their states coincide, since
     * [ResolvedExperience.nextState] of row `i` is logged at the same
     * decision boundary as row `i+1`'s own `state`), and for the final
     * step is [bootstrapValue] - the fresh critic call at the window's cut.
     */
    private fun buildWindow(symbol: String, windowStartMs: Long, rows: List<ResolvedExperience>): RolloutWindow {
        val ordered = rows.sortedBy { it.timestampMs }
        val bootstrapValue = criticValueFn(ordered.last().nextState)

        val steps = MutableList(ordered.size) { RolloutStep(ordered[it]) }

        var runningGae = 0.0
        for (i in ordered.indices.reversed()) {
            val row = ordered[i]
            val nextValue = if (i == ordered.lastIndex) bootstrapValue else ordered[i + 1].valueEstimate
            val delta = row.reward + gamma * nextValue - row.valueEstimate
            runningGae = delta + gamma * lambda * runningGae
            steps[i] = steps[i].copy(advantage = runningGae, valueTarget = runningGae + row.valueEstimate)
        }

        return RolloutWindow(
            symbol = symbol,
            windowStartMs = windowStartMs,
            windowEndMs = windowStartMs + windowLengthMs,
            steps = steps,
            bootstrapValue = bootstrapValue,
        )
    }

    /**
     * Floors [timestampMs] to the start of its containing window - the
     * same `ts - floorMod(ts, interval)` shape as
     * [FundingSchedule.previousSettlement], which is exactly why this stays
     * on the funding grid (see class doc).
     */
    private fun windowStartFor(timestampMs: Long): Long = timestampMs - Math.floorMod(timestampMs, windowLengthMs)
}
