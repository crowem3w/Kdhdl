package org.example.syncora.agent

import org.example.syncora.bitget.FundingSchedule
import kotlin.math.abs
import kotlin.math.pow

/**
 * Computes the per-bar reward `r_t` the RRL agent optimises, and the
 * differential Sharpe ratio `dsr_t` derived from the reward stream - see
 * `docs/agent-design-contract.md` §1 (reward definition) and §3 (funding
 * sign convention), which this class must reproduce exactly and is not
 * free to approximate or simplify.
 *
 * This is Phase 4: it consumes Phase 1-3's outputs only insofar as the
 * *position* they eventually drive (`f_t`, from [PolicyEngine] in Phase 5)
 * is one of its inputs - [RewardEngine] itself has no dependency on
 * [FeatureAssembler], [ReservoirEngine], or [ReadoutTrainer]. It is the
 * thing Phase 5 will be trained against, not something trained itself.
 *
 * ### Reward formula (contract §1)
 * ```
 * r_t = Δp_t · f_{t-1} − δ_t · |Δf_t| − φ_t · |Δf_t| − κ_t · f_t
 * ```
 * | term | meaning |
 * |---|---|
 * | `Δp_t · f_{t-1}` | mark-to-market P&L on the position already held into this bar |
 * | `δ_t · &#124;Δf_t&#124;` | spread-crossing cost, only on a position change ([RewardInputs.halfSpread]) |
 * | `φ_t · &#124;Δf_t&#124;` | exchange taker/maker fee, a separate additive cost from `δ_t` per contract §1 ("`δ_t` and exchange fees are two different costs and both apply") |
 * | `κ_t · f_t` | funding cost/benefit of holding `f_t` through this bar (see below) |
 *
 * `φ_t` (the per-unit fee cost for this bar) is `feeRate · midPrice`, mirroring
 * [org.example.syncora.bitget.PaperTradingRepository]'s `estimateFee` (`size *
 * price * rate`) with `size` in the same `f_t`-normalized units `Δp_t` and `δ_t`
 * already use, so this term is dimensionally consistent with the rest of `r_t`.
 *
 * ### Funding accrual (contract §3)
 * `κ_t = fundingRate_t · (barDurationMs / fundingIntervalMs)`: the settlement
 * rate for whichever funding period this bar falls in, pro-rated by how much
 * of that period this one bar covers. Since Bitget's funding rate is fixed
 * for the whole interval between settlements (it only changes *at* a
 * settlement), a constant position held across every bar of one full
 * interval accrues `Σ κ_t = fundingRate_t · Σ barDurationMs / fundingIntervalMs
 * = fundingRate_t` (bar durations for a full interval sum to
 * `fundingIntervalMs`) - exactly the fractional (per-unit-of-max-notional)
 * form of [org.example.syncora.bitget.PaperTradingRepository.settleFunding]'s
 * `amount = notional * fundingRate * direction`, since `notional =
 * maxNotional * |f_t|` and `direction = sign(f_t)` collapse to `maxNotional *
 * fundingRate * f_t`. Dividing through by `maxNotional` (i.e. working in
 * `f_t`-normalized units, as `r_t` does throughout) leaves exactly
 * `fundingRate_t · f_t` accrued over the interval - no drift between this
 * engine's accrual and what the paper account is actually charged, as
 * contract §3 requires. This equivalence assumes the position is unchanged
 * across the interval; contract §3 does not claim otherwise; it only accrues
 * whatever `κ_t · f_t` this bar's position implies.
 *
 * ### Differential Sharpe ratio
 * [RewardEngine] tracks exponentially-weighted first and second moments of
 * the reward stream, `a_t` (mean) and `b_t` (second moment, so `b_t -
 * a_t²` is the variance), and exposes:
 * ```
 * dsr_t = (b_{t-1}·Δa_t − 0.5·a_{t-1}·Δb_t) / (b_{t-1} − a_{t-1}²)^(3/2)
 * ```
 * with `Δa_t = r_t − a_{t-1}`, `Δb_t = r_t² − b_{t-1}`, and moments updated
 * as `a_t = a_{t-1} + η·Δa_t`, `b_t = b_{t-1} + η·Δb_t` - the online
 * differential Sharpe ratio of Moody, Wu, Liao & Saffell (1998), used as the
 * utility signal by the source paper (Borrageiro, Firoozye & Barucca 2022).
 * The denominator is the (variance)^(3/2) term `(b_{t-1} − a_{t-1}²)^(3/2)`:
 * `b_{t-1}` (second moment) minus `a_{t-1}²` (mean squared) is the standard
 * decomposition of variance, which is what a Sharpe-ratio-style denominator
 * must be. `dsr_t` is undefined for the very first reward observed (no prior
 * moments exist yet); by convention (Moody et al.) it is reported as `0.0`
 * for that first step, and `a_0`/`b_0` are seeded directly from that first
 * reward (`a_0 = r_0`, `b_0 = r_0²`) rather than from an EMA update against
 * an artificial zero prior, so the moments are unbiased from the very next
 * step onward.
 */
class RewardEngine(
    private val adaptationRate: Double = DEFAULT_ADAPTATION_RATE,
) {
    companion object {
        /** `η` in the moment EMA update - Moody et al.'s typical small adaptation rate. */
        const val DEFAULT_ADAPTATION_RATE = 0.01

        // Guards the dsr denominator against a not-yet-positive variance
        // estimate (only possible in the first couple of steps, or if the
        // reward stream is literally constant) rather than dividing by ~0.
        private const val VARIANCE_EPS = 1e-12
    }

    init {
        require(adaptationRate > 0.0 && adaptationRate <= 1.0) {
            "adaptationRate must be in (0, 1], was $adaptationRate"
        }
    }

    // Exponentially-weighted first (a) and second (b) moments of the reward
    // stream. Both start at 0.0 but the very first update() seeds them from
    // the first observed reward directly rather than EMA-ing against this
    // placeholder - see hasSeenFirstReward.
    private var a: Double = 0.0
    private var b: Double = 0.0
    private var hasSeenFirstReward: Boolean = false

    /**
     * Computes `r_t` and its components from [inputs], per contract §1/§3.
     * Pure and stateless: does not touch the differential Sharpe moments, so
     * it is independently testable against hand-calculated fixtures (see
     * `RewardEngineTest`) without needing a whole reward *stream* set up
     * first. Call [updateMoments] with the returned [RewardComponents.reward]
     * to fold it into the dsr moments.
     */
    fun computeReward(inputs: RewardInputs): RewardComponents {
        require(inputs.fundingIntervalMs > 0) {
            "fundingIntervalMs must be > 0, was ${inputs.fundingIntervalMs}"
        }
        require(inputs.barDurationMs >= 0) {
            "barDurationMs must be >= 0, was ${inputs.barDurationMs}"
        }
        require(inputs.feeRate >= 0.0) { "feeRate must be >= 0, was ${inputs.feeRate}" }
        require(inputs.halfSpread >= 0.0) { "halfSpread must be >= 0, was ${inputs.halfSpread}" }

        val deltaP = inputs.midPrice - inputs.prevMidPrice
        val markToMarket = deltaP * inputs.prevPosition

        val deltaF = inputs.position - inputs.prevPosition
        val absDeltaF = abs(deltaF)
        val spreadCost = inputs.halfSpread * absDeltaF
        val feeCost = (inputs.feeRate * inputs.midPrice) * absDeltaF

        val intervalFraction = inputs.barDurationMs.toDouble() / inputs.fundingIntervalMs.toDouble()
        val kappa = inputs.fundingRate * intervalFraction
        val fundingCost = kappa * inputs.position

        val reward = markToMarket - spreadCost - feeCost - fundingCost

        return RewardComponents(
            markToMarket = markToMarket,
            spreadCost = spreadCost,
            feeCost = feeCost,
            fundingCost = fundingCost,
            reward = reward,
        )
    }

    /**
     * Folds observed reward [reward] into the running moments and returns
     * `dsr_t` computed from the *pre-update* moments `a_{t-1}`/`b_{t-1}`, per
     * the class doc. Stateful - call once per bar, in bar-close order, after
     * [computeReward] for that same bar. Returns `0.0` for the very first
     * call ([hasSeenFirstReward] false going in), per Moody et al.'s
     * convention, and seeds `a_0 = reward`, `b_0 = reward²` instead of an EMA
     * step so later dsr values aren't biased by an artificial zero prior.
     */
    fun updateMoments(reward: Double): Double {
        if (!hasSeenFirstReward) {
            a = reward
            b = reward * reward
            hasSeenFirstReward = true
            return 0.0
        }

        val aPrev = a
        val bPrev = b
        val deltaA = reward - aPrev
        val deltaB = reward * reward - bPrev

        val variance = bPrev - aPrev * aPrev
        val dsr = if (variance > VARIANCE_EPS) {
            (bPrev * deltaA - 0.5 * aPrev * deltaB) / variance.pow(1.5)
        } else {
            0.0
        }

        a = aPrev + adaptationRate * deltaA
        b = bPrev + adaptationRate * deltaB

        return dsr
    }

    /**
     * Convenience combining [computeReward] and [updateMoments] for the
     * common bar-close call site (Phase 5's [PolicyEngine] wiring): computes
     * `r_t`, folds it into the moments, and returns both.
     */
    fun step(inputs: RewardInputs): RewardStep {
        val components = computeReward(inputs)
        val dsr = updateMoments(components.reward)
        return RewardStep(components = components, dsr = dsr)
    }

    /** Current mean-reward moment `a_t` (post the last [updateMoments]/[step] call). */
    fun meanMoment(): Double = a

    /** Current second-moment `b_t` (post the last [updateMoments]/[step] call). */
    fun secondMoment(): Double = b

    /** True once at least one reward has been folded in via [updateMoments]/[step]. */
    fun hasObservedReward(): Boolean = hasSeenFirstReward
}

/**
 * Inputs to one [RewardEngine.computeReward] call, all as of the same bar
 * close `t`. [prevMidPrice]/[prevPosition] are the previous bar's values -
 * the caller (Phase 5/6's orchestrator) is expected to carry these forward
 * bar to bar, same shape as [FeatureAssembler.assemble]'s klines-history
 * convention.
 */
data class RewardInputs(
    /** Mid price `0.5*(bid+ask)` at the previous bar close. */
    val prevMidPrice: Double,
    /** Mid price `0.5*(bid+ask)` at this bar close. */
    val midPrice: Double,
    /** `0.5*(ask-bid)` at this bar close - the `δ_t` term, contract §1. */
    val halfSpread: Double,
    /** Position held going into this bar, `f_{t-1} ∈ [-1, 1]`. */
    val prevPosition: Double,
    /** Position for this bar, `f_t ∈ [-1, 1]`. */
    val position: Double,
    /** Maker/taker fee rate applied to a position change this bar (e.g. [org.example.syncora.bitget.FeeRates.takerRate]). */
    val feeRate: Double,
    /** The funding rate settled for whichever funding period this bar falls in. */
    val fundingRate: Double,
    /** Wall-clock duration this bar covers, used to pro-rate [fundingRate] into this bar's `κ_t` (contract §3). */
    val barDurationMs: Long,
    /** The funding settlement interval [fundingRate] applies over - [FundingSchedule.INTERVAL_MS] unless a fixture overrides it. */
    val fundingIntervalMs: Long = FundingSchedule.INTERVAL_MS,
)

/** The reward `r_t` broken into its four contract §1 terms, plus the total. */
data class RewardComponents(
    val markToMarket: Double,
    val spreadCost: Double,
    val feeCost: Double,
    val fundingCost: Double,
    val reward: Double,
)

/** One bar's [RewardEngine.step] output: the reward breakdown plus the resulting `dsr_t`. */
data class RewardStep(
    val components: RewardComponents,
    val dsr: Double,
)
