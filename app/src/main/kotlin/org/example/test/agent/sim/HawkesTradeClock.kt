package org.example.test.agent.sim

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * The Hawkes-process third of design doc §4.1's Tier-1 trio
 * ("jump-diffusion, regime-switching GARCH, **Hawkes processes**"). A
 * Poisson process spaces trades evenly at random; real tape - especially
 * during a liquidation cascade (§4.2, §4.5) - arrives in **self-exciting
 * bursts**: each print makes the next print more likely soon after, then
 * that excitation decays. This is what turns "high volatility" from a
 * wider-but-still-smooth trickle into the bursty, clustered order flow an
 * agent will actually see counter-trading it during a real cascade.
 *
 * Standard exponential-kernel Hawkes intensity:
 * `lambda(t) = baseline + excitation * sum_{t_i < t} exp(-decay * (t - t_i))`
 *
 * Simulated with Ogata's thinning algorithm: propose candidate arrivals
 * from an upper-bound-intensity Poisson process, accept each with
 * probability `lambda(t) / upperBound`, and refresh the upper bound after
 * every accepted event (since an accepted event raises intensity going
 * forward). No lookahead, no fixed grid - this generates exact arrival
 * timestamps in continuous time within `[0, durationSeconds]`.
 *
 * One instance simulates one bar's worth of trade arrivals; state does not
 * carry across bars (each call to [simulateArrivals] starts a fresh clock
 * at the given [baselineIntensity]), matching how [RegimeSwitchingPriceProcess]
 * hands over a possibly-different regime's Hawkes params each bar rather
 * than pretending excitation should bleed across a regime switch.
 */
object HawkesTradeClock {

    /**
     * Returns trade arrival offsets (seconds since bar start, ascending,
     * `< durationSeconds`) for one bar.
     *
     * @param durationSeconds Length of the bar being filled with trades.
     * @param baselineIntensity Background arrival rate (trades/sec) absent
     *   any self-excitation - [RegimeParams.hawkesBaselineIntensity].
     * @param excitation How much each arrival temporarily boosts intensity
     *   - [RegimeParams.hawkesExcitation].
     * @param decayPerSec How fast that boost decays - [RegimeParams.hawkesDecayPerSec].
     * @param extraKickAt Optional (offsetSeconds, intensityKick) pairs to
     *   inject on top of the ambient process - used to force a burst
     *   exactly when [RegimeStep.jumpFired], so a synthetic crash bar
     *   doesn't just have a big candle body but also the panicked flurry
     *   of prints a real cascade produces.
     * @param maxArrivals Hard cap so a pathological parameter combination
     *   (e.g. excitation close to/above decay, which is explosive) can't
     *   hang the caller - the simulation degrades to "very busy bar" rather
     *   than an unbounded loop.
     */
    fun simulateArrivals(
        durationSeconds: Double,
        baselineIntensity: Double,
        excitation: Double,
        decayPerSec: Double,
        rng: Random,
        extraKickAt: List<Pair<Double, Double>> = emptyList(),
        maxArrivals: Int = 20_000,
    ): List<Double> {
        require(durationSeconds > 0.0) { "durationSeconds must be positive" }
        if (baselineIntensity <= 0.0 && extraKickAt.isEmpty()) return emptyList()

        val arrivals = ArrayList<Double>()
        val kicks = extraKickAt.sortedBy { it.first }
        var kickIdx = 0
        var pendingKick = 0.0

        var t = 0.0
        // Track the exponential-kernel excitation contributed by each past
        // arrival as a single decaying accumulator (standard exp-kernel
        // Hawkes trick: sum of exp(-decay*(t-t_i)) satisfies its own
        // simple decay ODE, so we don't need to re-sum every past arrival).
        var excitationState = 0.0

        while (t < durationSeconds && arrivals.size < maxArrivals) {
            while (kickIdx < kicks.size && kicks[kickIdx].first <= t) {
                pendingKick += kicks[kickIdx].second
                kickIdx++
            }
            val currentIntensity = baselineIntensity + excitationState + pendingKick
            // Upper bound for thinning: current intensity plus a safety
            // margin, since intensity can only jump *up* at an accepted
            // arrival (never spontaneously between arrivals) - this bound
            // stays valid until the next accepted point.
            val upperBound = (currentIntensity + excitation).coerceAtLeast(1e-6)

            val u1 = rng.nextDouble().coerceIn(1e-12, 1.0)
            val waitSeconds = -ln(u1) / upperBound
            t += waitSeconds
            if (t >= durationSeconds) break

            excitationState *= exp(-decayPerSec * waitSeconds)
            while (kickIdx < kicks.size && kicks[kickIdx].first <= t) {
                pendingKick += kicks[kickIdx].second
                kickIdx++
            }

            val acceptanceIntensity = baselineIntensity + excitationState + pendingKick
            val acceptProb = (acceptanceIntensity / upperBound).coerceIn(0.0, 1.0)
            if (rng.nextDouble() < acceptProb) {
                arrivals.add(t)
                excitationState += excitation
            }
        }
        return arrivals
    }
}
