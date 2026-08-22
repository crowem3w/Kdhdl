package org.example.test.agent.sim

/**
 * The regime taxonomy design doc §3.2 trains the meta-RL policy across
 * ("bull, bear, high/low volatility, crisis") and §4.2 stratifies the
 * replay buffer by ("liquidation cascades, exchange outages, stablecoin
 * depegs" folding into [CRISIS] here). This is the ground-truth label the
 * live regime/change-point detector (§3.4) only ever gets to *infer* -
 * the synthetic generator gets to know it exactly, which is what makes it
 * useful as labeled training data in the first place.
 */
enum class MarketRegime {
    BULL,
    BEAR,
    HIGH_VOL,
    LOW_VOL,
    CRISIS,
}

/**
 * The parameters [RegimeSwitchingPriceProcess] draws one regime's returns
 * from - a regime-switching Merton jump-diffusion with GARCH(1,1) volatility
 * clustering, per design doc §4.1's Tier-1 source ("jump-diffusion,
 * regime-switching GARCH, Hawkes processes"). All rate fields are
 * annualized so they read the same way regardless of bar duration; the
 * process converts them to per-bar figures itself.
 *
 * @param driftAnnualized Annualized log-return drift (mu). Positive for
 *   [MarketRegime.BULL], negative for [MarketRegime.BEAR]/[MarketRegime.CRISIS].
 * @param longRunVolAnnualized The GARCH(1,1) long-run variance target
 *   (annualized vol). Realized vol drifts toward this but clusters around
 *   local shocks rather than snapping to it every bar.
 * @param garchAlpha Weight on the previous bar's squared shock - how much a
 *   single large move immediately raises the *next* bar's variance. Higher
 *   in [MarketRegime.CRISIS] (shocks are contagious).
 * @param garchBeta Weight on the previous bar's variance itself - how
 *   "sticky"/persistent volatility is once elevated. `garchAlpha + garchBeta`
 *   must stay below 1 for the process to be mean-reverting (stationary).
 * @param jumpIntensityPerBar Poisson arrival rate of discontinuous jumps
 *   per bar (the "force rare/extreme events" half of §4.1) - e.g. a
 *   liquidation cascade or a depeg gap, not just fat continuous returns.
 * @param jumpMeanLog Mean log-size of a jump when one fires. Strongly
 *   negative for [MarketRegime.CRISIS] (crashes are more violent and more
 *   frequent than rallies), mildly positive for [MarketRegime.BULL].
 * @param jumpStdLog Dispersion of jump size in log-space.
 * @param meanSojournBars Expected number of bars the process stays in this
 *   regime before [RegimeTransitionMatrix] rolls a switch - how "regime"
 *   this regime feels, versus flickering every bar.
 * @param hawkesBaselineIntensity Background trade-arrival rate for
 *   [HawkesTradeClock] while in this regime (trades/second, before
 *   self-excitation). Crisis/high-vol regimes trade far more often.
 * @param hawkesExcitation How much each trade print temporarily boosts the
 *   arrival rate of the *next* trade (self-excitation strength) - this is
 *   what produces liquidation-cascade-style bursts rather than a smooth
 *   Poisson trickle.
 * @param hawkesDecayPerSec How fast that excitation decays back to baseline.
 */
data class RegimeParams(
    val driftAnnualized: Double,
    val longRunVolAnnualized: Double,
    val garchAlpha: Double,
    val garchBeta: Double,
    val jumpIntensityPerBar: Double,
    val jumpMeanLog: Double,
    val jumpStdLog: Double,
    val meanSojournBars: Double,
    val hawkesBaselineIntensity: Double,
    val hawkesExcitation: Double,
    val hawkesDecayPerSec: Double,
) {
    init {
        require(garchAlpha + garchBeta < 1.0) {
            "garchAlpha + garchBeta must be < 1 for a stationary (mean-reverting) variance process, got ${garchAlpha + garchBeta}"
        }
        require(longRunVolAnnualized > 0.0) { "longRunVolAnnualized must be positive" }
        require(meanSojournBars > 0.0) { "meanSojournBars must be positive" }
    }
}

/**
 * Default, hand-tuned parameter set per regime. These are not fit to real
 * Bitget history (no historical dataset is assumed available, per design
 * doc §6) - they're a plausible-shaped starting curriculum: wider,
 * asymmetric jumps and thinner sojourns in [MarketRegime.CRISIS], calm
 * persistence in [MarketRegime.LOW_VOL]. Callers doing model-based
 * bootstrapping (§6.4) are expected to refit these against the live stream
 * once one exists, rather than trusting these numbers as ground truth.
 */
object RegimeLibrary {
    val DEFAULT: Map<MarketRegime, RegimeParams> = mapOf(
        MarketRegime.BULL to RegimeParams(
            driftAnnualized = 0.60,
            longRunVolAnnualized = 0.45,
            garchAlpha = 0.08,
            garchBeta = 0.85,
            jumpIntensityPerBar = 0.01,
            jumpMeanLog = 0.006,
            jumpStdLog = 0.01,
            meanSojournBars = 720.0,
            hawkesBaselineIntensity = 1.5,
            hawkesExcitation = 0.35,
            hawkesDecayPerSec = 0.7,
        ),
        MarketRegime.BEAR to RegimeParams(
            driftAnnualized = -0.45,
            longRunVolAnnualized = 0.60,
            garchAlpha = 0.10,
            garchBeta = 0.83,
            jumpIntensityPerBar = 0.015,
            jumpMeanLog = -0.008,
            jumpStdLog = 0.012,
            meanSojournBars = 600.0,
            hawkesBaselineIntensity = 1.8,
            hawkesExcitation = 0.40,
            hawkesDecayPerSec = 0.7,
        ),
        MarketRegime.HIGH_VOL to RegimeParams(
            driftAnnualized = 0.0,
            longRunVolAnnualized = 1.10,
            garchAlpha = 0.14,
            garchBeta = 0.80,
            jumpIntensityPerBar = 0.03,
            jumpMeanLog = 0.0,
            jumpStdLog = 0.02,
            meanSojournBars = 300.0,
            hawkesBaselineIntensity = 3.0,
            hawkesExcitation = 0.55,
            hawkesDecayPerSec = 0.9,
        ),
        MarketRegime.LOW_VOL to RegimeParams(
            driftAnnualized = 0.05,
            longRunVolAnnualized = 0.20,
            garchAlpha = 0.04,
            garchBeta = 0.90,
            jumpIntensityPerBar = 0.003,
            jumpMeanLog = 0.0,
            jumpStdLog = 0.006,
            meanSojournBars = 900.0,
            hawkesBaselineIntensity = 0.6,
            hawkesExcitation = 0.15,
            hawkesDecayPerSec = 0.5,
        ),
        MarketRegime.CRISIS to RegimeParams(
            driftAnnualized = -1.20,
            longRunVolAnnualized = 1.80,
            garchAlpha = 0.22,
            garchBeta = 0.74,
            jumpIntensityPerBar = 0.08,
            jumpMeanLog = -0.03,
            jumpStdLog = 0.04,
            meanSojournBars = 90.0,
            hawkesBaselineIntensity = 6.0,
            hawkesExcitation = 0.75,
            hawkesDecayPerSec = 1.2,
        ),
    )
}

/**
 * Where the regime-switching Markov chain jumps *to* once
 * [RegimeParams.meanSojournBars] expires, per source regime - encodes that
 * regimes don't transition uniformly at random (e.g. [MarketRegime.CRISIS]
 * usually resolves into [MarketRegime.HIGH_VOL] or [MarketRegime.BEAR], not
 * straight into [MarketRegime.LOW_VOL]). Rows are relative weights, not
 * required to sum to 1 - [RegimeTransitionMatrix.next] normalizes.
 */
object RegimeTransitionMatrix {
    private val weights: Map<MarketRegime, Map<MarketRegime, Double>> = mapOf(
        MarketRegime.BULL to mapOf(
            MarketRegime.LOW_VOL to 0.35, MarketRegime.HIGH_VOL to 0.35,
            MarketRegime.BEAR to 0.20, MarketRegime.CRISIS to 0.10,
        ),
        MarketRegime.BEAR to mapOf(
            MarketRegime.HIGH_VOL to 0.35, MarketRegime.CRISIS to 0.25,
            MarketRegime.LOW_VOL to 0.20, MarketRegime.BULL to 0.20,
        ),
        MarketRegime.HIGH_VOL to mapOf(
            MarketRegime.BULL to 0.25, MarketRegime.BEAR to 0.25,
            MarketRegime.CRISIS to 0.20, MarketRegime.LOW_VOL to 0.30,
        ),
        MarketRegime.LOW_VOL to mapOf(
            MarketRegime.BULL to 0.35, MarketRegime.HIGH_VOL to 0.30,
            MarketRegime.BEAR to 0.30, MarketRegime.CRISIS to 0.05,
        ),
        MarketRegime.CRISIS to mapOf(
            MarketRegime.HIGH_VOL to 0.50, MarketRegime.BEAR to 0.35,
            MarketRegime.LOW_VOL to 0.05, MarketRegime.BULL to 0.10,
        ),
    )

    /** Draws the next regime given the current one, excluding self-transitions (those are just "stay", handled by sojourn length). */
    fun next(current: MarketRegime, rng: kotlin.random.Random): MarketRegime {
        val row = weights.getValue(current)
        val total = row.values.sum()
        var draw = rng.nextDouble() * total
        for ((regime, weight) in row) {
            draw -= weight
            if (draw <= 0.0) return regime
        }
        return row.keys.first()
    }
}
