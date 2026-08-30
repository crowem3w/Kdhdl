package org.example.syncora.agent

import org.example.syncora.bitget.FundingSchedule
import kotlin.math.abs
import kotlin.math.pow

/**
 * Computes the per-bar reward `r_t` [RrlAgent] is trained against, the
 * quadratic utility `υ_t` (eq. 6) derived from it, and the (monitoring-only)
 * differential Sharpe ratio `dsr_t` (see the reference architecture diagram
 * / `docs/agent-design-contract.md` §1, and Borrageiro, Firoozye & Barucca
 * 2022 eq. 6/7/8).
 *
 * This sits downstream of nothing built so far (it is a pure function of
 * price, position and cost/funding inputs the caller supplies - bar close
 * by bar close) and it is what [RrlAgent.update] performs the extended
 * Kalman filter step against, via [RewardBreakdown.quadraticUtilityGradientWrtReward],
 * not `r_t` directly. Nothing here decides a position; `f_t`/`f_{t-1}` are
 * inputs, always sourced from the agent, never computed by this class.
 *
 * ### Redesign note (quadratic utility replaces dsr as the training signal)
 * The previous version of this class computed only `dsr_t` and trained
 * `PolicyEngine` against it. The paper's own crypto-agent experiment does
 * not do that: `dsr_t` is the training signal for the *unrelated* Moody &
 * Saffell scheme the paper only discusses in its literature review
 * (§II-C1), not the scheme its own experiment runs. What actually drives
 * training (eq. 6, eq. 12, via [RrlAgent]'s EKF) is the quadratic utility
 * `υ_t = µ_t − (λ/2)σ_t²`. `dsr_t` is kept - it's a genuine monitoring
 * signal the paper's own experiment design bullet uses ("Monitor equation
 * 7, the expected net reward... trade freely if µt >= 0") - but it is no
 * longer what training is driven by.
 *
 * ### The reward
 * `docs/agent-design-contract.md` §1 fixes:
 * ```
 * r_t = Δp_t · f_{t-1} − δ_t · |Δf_t| − κ_t · f_t
 * ```
 * with `δ_t·|Δf_t|` (design doc §1) extended, per that same section, to
 * *also* carry Bitget's real maker/taker fee alongside the modeled
 * half-spread - "two different costs and both apply" - so the transaction
 * cost this class actually charges is
 * ```
 * cost_t = (δ_t + feeRate_t · p_t) · |Δf_t|,   δ_t = 0.5·(ask_t − bid_t)
 * ```
 * i.e. the fee is expressed the same way [ReservoirEngine]'s sibling
 * `PaperTradingRepository.estimateFee` already prices a fill - `notional x
 * rate`, with `|Δf_t|` standing in for the traded size, `p_t` for the fill
 * price - not folded into or dropped in favor of `δ_t`.
 *
 * ### Funding
 * `κ_t·f_t` (design doc §3) is charged as a continuous per-bar *accrual*,
 * not only on bars that happen to land on a
 * [FundingSchedule] settlement instant: every bar is charged its own
 * elapsed-time share of a full funding interval,
 * ```
 * fundingCost_t = f_t · p_t · fundingRate_t · (barSpanMs / FundingSchedule.INTERVAL_MS)
 * ```
 * so a bar spanning one *entire* 8h interval (`barSpanMs ==
 * FundingSchedule.INTERVAL_MS`) reduces exactly to `f_t · p_t ·
 * fundingRate_t` - the same `notional · fundingRate · sign(f_t)` amount
 * `PaperTradingRepository.settleFunding` posts to the paper wallet (its
 * `notional = |f_t| · p_t` and `direction = sign(f_t)` combine to exactly
 * `f_t · p_t`) - and a full replay's bar-by-bar accruals sum to that same
 * settlement total for a position held constant across the interval, per
 * design doc §3's "no drift between the two" requirement. No other formula
 * for funding P&L is used anywhere in this class.
 *
 * ### Quadratic utility
 * `a_t`/`b_t` are exponentially-weighted first/second moments of the
 * reward stream (Moody et al., also reproduced in the source paper's
 * eq. 5/eq. 2):
 * ```
 * a_t = a_{t-1} + τ(r_t − a_{t-1})
 * b_t = b_{t-1} + τ(r_t² − b_{t-1})
 * σ_t² = b_{t-1} − a_{t-1}²
 * υ_t = µ_t − (λ/2)σ_t²                          (eq. 6, µ_t == a_{t-1})
 * dυ_t/dr_t = (1 − τ)·[1 − λ·(r_t − µ_t)]         (eq. 12)
 * ```
 * `λ` is [riskAppetite]; `τ` is [sharpeAdaptationRate], reused as the same
 * EWMA decay for both the utility's moments and the monitoring-only dsr -
 * one reward-moments estimate, not two that could drift out of sync
 * (design doc §1).
 *
 * ### Differential Sharpe ratio (monitoring only)
 * ```
 * dsr_t = (b_{t-1}Δa_t − 0.5·a_{t-1}Δb_t) / (b_{t-1} − a_{t-1}²)^(3/2)
 * ```
 * The source paper's eq. 5 prints this denominator as `(a_{t-1} −
 * a_{t-1}²)^(3/2)`, but that is a transcription slip, not a different
 * formula: `a − a²` is negative for any negative average reward (a losing
 * strategy), which would raise a negative number to a fractional power and
 * produce `NaN` on the very first losing bar. `b_{t-1} − a_{t-1}²` is the
 * EWMA *variance* of the reward stream (always `>= 0`), matches the
 * paper's own gradient derivation two lines later (its eq. 2, `(b_T −
 * a_T²)^(3/2)`), and matches the standard Moody/Saffell differential
 * Sharpe ratio this whole scheme is drawn from - so that is the form
 * implemented here.
 *
 * ### Performance
 * Two `Double` fields (`a`, `b`) are the only state this class carries;
 * [step] does a fixed, small number of scalar operations - no allocation,
 * no `FloatArray`, since there is no per-bar vector here, just scalars, so
 * there is nothing to gain from the reservoir/agent's flat-array
 * discipline.
 */
class RewardEngine(
    private val sharpeAdaptationRate: Double = DEFAULT_SHARPE_ADAPTATION_RATE,
    /**
     * `λ` in eq. 6, `υ_t = µ_t − (λ/2)σ_t²` - the risk appetite constant.
     * Paper's experiment design (subsection III-C): "Set the risk appetite
     * constant λ = 0.00001 for quadratic utility equation 6." The paper
     * also notes (§III-B2) that λ could instead be set adaptively as
     * `ir_t / σ_t`, but the experiment that produced the paper's reported
     * 350% / 1.46 IR result used the fixed constant, so that's the default
     * here too - adaptive λ is a documented option, not silently swapped in.
     */
    private val riskAppetite: Double = DEFAULT_RISK_APPETITE,
) {
    companion object {
        /** Moody et al.'s `τ` - how quickly the EWMA moments track the live reward stream. */
        const val DEFAULT_SHARPE_ADAPTATION_RATE = 0.01

        /** `λ = 0.00001` per the paper's experiment design. */
        const val DEFAULT_RISK_APPETITE = 0.00001

        // Numerical safety net, same spirit as ReadoutTrainer's DENOM_EPS:
        // the EWMA variance b_{t-1} - a_{t-1}^2 is >= 0 by construction
        // (Cauchy-Schwarz), but starts at exactly 0 (a_0 = b_0 = 0, so
        // there's no prior variance estimate yet) and can float-round to a
        // hair below 0. dsr_t is conventionally taken to be 0 during this
        // "no variance estimate yet" warm-up rather than NaN/±Inf.
        private const val VARIANCE_FLOOR = 1e-12

        /**
         * `d(r_t)/d(f_t)` - the sensitivity of this bar's reward to the
         * *current* position `f_t`, holding `f_{t-1}` fixed. This is the
         * other half [RrlAgent.update] needs to form `∇υ_t` for its EKF
         * step (the other half being
         * [RewardBreakdown.quadraticUtilityGradientWrtReward]); chaining
         * the two via `d(υ_t)/d(f_t) = d(υ_t)/d(r_t) * d(r_t)/d(f_t)` is
         * the same chain rule the paper's eq. 12 uses, restricted here to
         * `r_t`'s *immediate* dependence on `f_t` (the transaction-cost and
         * funding terms) - not `r_{t+1}`'s dependence on `f_t` via next
         * bar's mark-to-market term, which would require differentiating
         * through a reward that hasn't happened yet. This one-bar-lookback-only
         * gradient is the "RTRL-lite" simplification
         * `ESN_RRL_Agent_Task_Prompts.md` Prompt 6 calls for.
         *
         * `r_t = Δp_t·f_{t-1} − (δ_t + feeRate·p_t)·|f_t−f_{t-1}| − f_t·p_t·fundingRate·intervalFraction`
         * (design doc §1/§3, [step]'s exact formula), so:
         * ```
         * d(r_t)/d(f_t) = −(δ_t + feeRate·p_t)·sign(f_t−f_{t-1}) − p_t·fundingRate·intervalFraction
         * ```
         * The transaction-cost term is only a subgradient at `f_t ==
         * f_{t-1}` (`|x|` isn't differentiable at 0); `sign(0) == 0` is
         * used there, matching the convention that holding a position
         * unchanged has no marginal cost either way at that exact point.
         *
         * Uses the *same* `(δ_t + feeRate·p_t)` and funding-accrual
         * formulas [step] does - no second, parallel cost/funding formula,
         * per design doc §3/§4's "one formula" requirement.
         */
        fun positionGradient(
            prevPosition: Double,
            currPosition: Double,
            currMidPrice: Double,
            bid: Double,
            ask: Double,
            feeRate: Double = 0.0,
            fundingRate: Double = 0.0,
            barSpanMs: Long = 0L,
        ): Double {
            require(ask >= bid) { "ask ($ask) must be >= bid ($bid)" }
            require(feeRate >= 0.0) { "feeRate must be >= 0, was $feeRate" }
            require(barSpanMs >= 0L) { "barSpanMs must be >= 0, was $barSpanMs" }

            val deltaPosition = currPosition - prevPosition
            val halfSpread = 0.5 * (ask - bid)
            val costRate = halfSpread + feeRate * currMidPrice
            val costGradient = -costRate * kotlin.math.sign(deltaPosition)

            val intervalFraction = barSpanMs.toDouble() / FundingSchedule.INTERVAL_MS
            val fundingGradient = -currMidPrice * fundingRate * intervalFraction

            return costGradient + fundingGradient
        }
    }

    init {
        require(sharpeAdaptationRate > 0.0 && sharpeAdaptationRate <= 1.0) {
            "sharpeAdaptationRate must be in (0, 1], was $sharpeAdaptationRate"
        }
    }

    // a_t = µ_t (eq. 7's first moment), b_t used to derive σ_t² = b_t - a_t².
    // Reused for both the (monitoring-only) dsr and the (training) quadratic
    // utility - both are the same EWMA moments, per design doc §1: this
    // class owns exactly one reward-moments estimate, not two that could
    // drift out of sync. Both start at 0: there is no prior reward history
    // for a freshly constructed engine.
    private var a: Double = 0.0
    private var b: Double = 0.0

    /** One bar's reward, broken into its components, plus the quadratic utility and (monitoring-only) differential Sharpe computed from it. */
    data class RewardBreakdown(
        /** `r_t` - the full per-bar reward, `markToMarketPnl - transactionCost - fundingCost`. */
        val reward: Double,
        /** `Δp_t · f_{t-1}` - mark-to-market P&L on the position already held going into this bar. */
        val markToMarketPnl: Double,
        /** `(δ_t + feeRate_t·p_t) · |Δf_t|` - modeled spread cost plus exchange fee, charged only when the position changes. */
        val transactionCost: Double,
        /** `f_t · p_t · fundingRate_t · (barSpanMs / FundingSchedule.INTERVAL_MS)` - this bar's funding accrual (design doc §3). */
        val fundingCost: Double,
        /** `µ_t` - EWMA mean of the reward stream, pre-this-step (eq. 7). */
        val expectedReturn: Double,
        /** `σ_t²` - EWMA variance of the reward stream, pre-this-step (eq. 7). */
        val variance: Double,
        /**
         * `υ_t = µ_t − (λ/2)σ_t²` (eq. 6) - the quadratic utility. This,
         * not [differentialSharpe], is what [RrlAgent] is trained to
         * maximise, via [quadraticUtilityGradientWrtReward].
         */
        val quadraticUtility: Double,
        /**
         * `dυ_t/dr_t = (1 − τ)·[1 − λ·(r_t − µ_t)]`, the derivative given
         * directly under eq. 12 in the paper (with `τ` the same EWMA decay
         * used for eq. 7's moments - the paper prints `η` there, which is
         * a transcription slip for the `τ` defined two paragraphs earlier
         * in the same subsection). [RrlAgent.update] multiplies this by
         * [RewardEngine.positionGradient] (`dr_t/df_t`) and its own trace
         * (`d f_t/d w_i`) to form `∇υ_t` for the EKF step.
         */
        val quadraticUtilityGradientWrtReward: Double,
        /** `dsr_t` - kept for monitoring only (paper's own "trade freely if µt >= 0" rule) - not a training signal. */
        val differentialSharpe: Double,
    )

    /**
     * Computes one bar's reward, quadratic utility, and (monitoring-only)
     * differential Sharpe ratio, and folds `r_t` into the internal
     * `a_t`/`b_t` moments for the next call.
     *
     * @param prevMidPrice reference (mid) price going into this bar, `p_{t-1}`.
     * @param currMidPrice reference (mid) price at this bar's close, `p_t`.
     * @param prevPosition position held going into this bar, `f_{t-1}` (bounded `[-1, 1]` by [RrlAgent]; long positive, short negative).
     * @param currPosition position held at this bar's close, `f_t`.
     * @param bid this bar's best bid, used for `δ_t`.
     * @param ask this bar's best ask, used for `δ_t`; must be `>= bid`.
     * @param feeRate the maker/taker rate ([org.example.syncora.bitget.FeeRates]) applicable to this bar's fill, if `Δf_t != 0`. Defaults to 0 (no fee modeled).
     * @param fundingRate the funding rate applicable to the [FundingSchedule] interval this bar falls in ([org.example.syncora.bitget.FundingRateInfo.fundingRate]). Defaults to 0 (no funding modeled).
     * @param barSpanMs how much of a full funding interval this bar covers, in wall-clock milliseconds - typically `currBarStartMs - prevBarStartMs`. Defaults to 0 (no funding accrual charged this bar).
     */
    fun step(
        prevMidPrice: Double,
        currMidPrice: Double,
        prevPosition: Double,
        currPosition: Double,
        bid: Double,
        ask: Double,
        feeRate: Double = 0.0,
        fundingRate: Double = 0.0,
        barSpanMs: Long = 0L,
    ): RewardBreakdown {
        require(ask >= bid) { "ask ($ask) must be >= bid ($bid)" }
        require(feeRate >= 0.0) { "feeRate must be >= 0, was $feeRate" }
        require(barSpanMs >= 0L) { "barSpanMs must be >= 0, was $barSpanMs" }

        val deltaPosition = currPosition - prevPosition

        val markToMarketPnl = (currMidPrice - prevMidPrice) * prevPosition

        val halfSpread = 0.5 * (ask - bid)
        val transactionCost = (halfSpread + feeRate * currMidPrice) * abs(deltaPosition)

        val intervalFraction = barSpanMs.toDouble() / FundingSchedule.INTERVAL_MS
        val fundingCost = currPosition * currMidPrice * fundingRate * intervalFraction

        val reward = markToMarketPnl - transactionCost - fundingCost

        // Pre-step moments (a_{t-1}, b_{t-1}) are what both the utility
        // and its gradient are evaluated against, per eq. 6/7 and eq. 12.
        val muPrev = a
        val variancePrev = (b - a * a).coerceAtLeast(0.0)
        val quadraticUtility = muPrev - 0.5 * riskAppetite * variancePrev
        val quadraticUtilityGradient =
            (1.0 - sharpeAdaptationRate) * (1.0 - riskAppetite * (reward - muPrev))

        val dsr = differentialSharpe(reward)
        updateMoments(reward)

        return RewardBreakdown(
            reward = reward,
            markToMarketPnl = markToMarketPnl,
            transactionCost = transactionCost,
            fundingCost = fundingCost,
            expectedReturn = muPrev,
            variance = variancePrev,
            quadraticUtility = quadraticUtility,
            quadraticUtilityGradientWrtReward = quadraticUtilityGradient,
            differentialSharpe = dsr,
        )
    }

    /**
     * `dsr_t`, computed from [r] and the moments as they stood *before*
     * this bar - see class doc's "Differential Sharpe ratio" section.
     * Monitoring-only: nothing downstream trains against this value
     * anymore (see the class doc's redesign note).
     */
    private fun differentialSharpe(r: Double): Double {
        val variance = b - a * a
        if (variance <= VARIANCE_FLOOR) return 0.0
        val deltaA = r - a
        val deltaB = r * r - b
        val denom = variance.pow(1.5)
        return (b * deltaA - 0.5 * a * deltaB) / denom
    }

    /** Folds [r] into the EWMA moments `a_t`/`b_t` for the next [step] call. */
    private fun updateMoments(r: Double) {
        a += sharpeAdaptationRate * (r - a)
        b += sharpeAdaptationRate * (r * r - b)
    }

    /** Current `a_t` - the EWMA first moment (mean) of the reward stream. */
    fun firstMoment(): Double = a

    /** Current `b_t` - the EWMA second moment of the reward stream. */
    fun secondMoment(): Double = b

    /** Resets `a_t`/`b_t` to a fresh, no-history state - for starting a new replay/episode without constructing a new engine. */
    fun reset() {
        a = 0.0
        b = 0.0
    }
}
