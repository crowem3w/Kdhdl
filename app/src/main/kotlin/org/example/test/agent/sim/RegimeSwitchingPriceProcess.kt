package org.example.test.agent.sim

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One simulated bar's worth of output from [RegimeSwitchingPriceProcess]:
 * the realized log-return plus enough process internals (the *true*
 * instantaneous vol and whether a jump fired) for [SyntheticMarketDataGenerator]
 * to shape realistic intra-bar OHLC/trades/depth, and for curriculum code
 * (design doc §4.2) to label and stratify by regime and by "was this bar a
 * forced extreme event".
 */
data class RegimeStep(
    val barIndex: Int,
    val regime: MarketRegime,
    val logReturn: Double,
    /** Instantaneous per-bar vol (not annualized) the GARCH state implies for *this* bar - i.e. how wide to draw intrabar noise. */
    val instantVolPerBar: Double,
    val jumpFired: Boolean,
    val jumpLogSize: Double,
    val price: Double,
)

/**
 * Design doc §4.1 Tier 1 ("Synthetic regime generator (jump-diffusion,
 * regime-switching GARCH, Hawkes processes) — Cheap, infinite data; force
 * rare/extreme events") and §3.2's meta-RL curriculum ("trained across many
 * simulated regimes... so the agent learns how to adapt quickly").
 *
 * Composes three pieces per bar:
 *  1. **Regime-switching**: a semi-Markov chain over [MarketRegime] - each
 *     regime has an expected sojourn length ([RegimeParams.meanSojournBars]);
 *     when it expires, [RegimeTransitionMatrix] draws the next regime.
 *  2. **GARCH(1,1) volatility clustering**: `sigma^2_t = omega + alpha *
 *     eps^2_{t-1} + beta * sigma^2_{t-1}`, so a large move raises near-term
 *     variance rather than every bar drawing i.i.d. from the regime's
 *     long-run vol - real markets cluster volatility, and an agent that
 *     never sees that clustering in training won't have learned to react
 *     to it live.
 *  3. **Merton jump-diffusion**: on top of the continuous GARCH-vol
 *     Gaussian component, a Poisson-gated log-normal jump can fire each
 *     bar - the mechanism for "force rare/extreme events" (liquidation
 *     cascades, gap moves) that a pure diffusion can't produce no matter
 *     how wide its variance gets.
 *
 * Not thread-safe - one instance advances one price path. Deterministic
 * given [seed], so a training run can replay or diff two curricula exactly.
 */
class RegimeSwitchingPriceProcess(
    private val startPrice: Double,
    private val barsPerYear: Double = 60.0 * 24.0 * 365.0,
    private val regimeParams: Map<MarketRegime, RegimeParams> = RegimeLibrary.DEFAULT,
    startRegime: MarketRegime = MarketRegime.LOW_VOL,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)

    private var currentRegime: MarketRegime = startRegime
    private var barsInRegime: Int = 0
    private var sojournTarget: Int = drawSojourn(startRegime)

    private var price: Double = startPrice
    private var variancePerBar: Double = perBarVarianceFloor(startRegime)
    private var barIndex: Int = 0

    /** Advances the process by one bar and returns the realized step. */
    fun nextStep(): RegimeStep {
        maybeSwitchRegime()
        val params = regimeParams.getValue(currentRegime)

        // GARCH(1,1): today's variance is a blend of the long-run target,
        // yesterday's realized shock, and yesterday's variance - this is
        // what makes vol *cluster* instead of resetting every bar.
        val longRunVariancePerBar = perBarVarianceFloor(currentRegime)
        val omega = longRunVariancePerBar * (1.0 - params.garchAlpha - params.garchBeta)
        val prevShockSq = lastShockSquared
        variancePerBar = (omega + params.garchAlpha * prevShockSq + params.garchBeta * variancePerBar)
            .coerceAtLeast(longRunVariancePerBar * 0.05)
        val vol = sqrt(variancePerBar)

        val driftPerBar = params.driftAnnualized / barsPerYear
        val gaussianShock = rng.nextGaussian()
        val diffusionReturn = driftPerBar - 0.5 * variancePerBar + vol * gaussianShock

        val jumpFired = rng.nextDouble() < params.jumpIntensityPerBar
        val jumpLogSize = if (jumpFired) {
            params.jumpMeanLog + params.jumpStdLog * rng.nextGaussian()
        } else {
            0.0
        }

        val totalLogReturn = diffusionReturn + jumpLogSize
        lastShockSquared = (totalLogReturn - driftPerBar).let { it * it }

        price *= exp(totalLogReturn)
        barsInRegime += 1
        val step = RegimeStep(
            barIndex = barIndex,
            regime = currentRegime,
            logReturn = totalLogReturn,
            instantVolPerBar = vol,
            jumpFired = jumpFired,
            jumpLogSize = jumpLogSize,
            price = price,
        )
        barIndex += 1
        return step
    }

    /** Generates [count] consecutive bars in one call. */
    fun nextSteps(count: Int): List<RegimeStep> = List(count) { nextStep() }

    private var lastShockSquared: Double = 0.0

    private fun maybeSwitchRegime() {
        if (barsInRegime < sojournTarget) return
        currentRegime = RegimeTransitionMatrix.next(currentRegime, rng)
        barsInRegime = 0
        sojournTarget = drawSojourn(currentRegime)
    }

    /** Sojourn length drawn from an exponential with the regime's mean - semi-Markov, not a fixed dwell time, so regime boundaries don't fall on a predictable clock the agent could overfit to. */
    private fun drawSojourn(regime: MarketRegime): Int {
        val mean = regimeParams.getValue(regime).meanSojournBars
        val u = rng.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
        return (-ln(u) * mean).toInt().coerceAtLeast(1)
    }

    private fun perBarVarianceFloor(regime: MarketRegime): Double {
        val annualizedVol = regimeParams.getValue(regime).longRunVolAnnualized
        return (annualizedVol * annualizedVol) / barsPerYear
    }
}
