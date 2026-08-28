package org.example.syncora.agent

import org.example.syncora.bitget.FundingSchedule
import kotlin.math.abs
import kotlin.math.pow

/**
 * Computes the per-bar reward `r_t` the RRL agent is trained against, and
 * the differential Sharpe ratio `dsr_t` derived from it (see the reference
 * architecture diagram / `docs/agent-design-contract.md` §1, and
 * Borrageiro, Firoozye & Barucca 2022 eq. 8, itself an extension of Moody
 * et al.'s reward).
 *
 * This is Phase 4: it sits downstream of nothing built so far (it is a pure
 * function of price, position and cost/funding inputs the caller supplies -
 * bar close by bar close) and it is what [org.example.syncora.agent.PolicyEngine]
 * (Phase 5) will eventually perform gradient ascent against, via [dsr_t],
 * not `r_t` directly. Nothing here decides a position; `f_t`/`f_{t-1}` are
 * inputs, always sourced from the policy, never computed by this class.
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
 * ### Differential Sharpe ratio
 * `a_t`/`b_t` are exponentially-weighted first/second moments of the
 * reward stream (Moody et al., also reproduced in the source paper's
 * eq. 5/eq. 2):
 * ```
 * a_t = a_{t-1} + τ(r_t − a_{t-1})
 * b_t = b_{t-1} + τ(r_t² − b_{t-1})
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
 * no `FloatArray`, since (unlike Phases 2/3) there is no per-bar vector
 * here, just scalars, so there is nothing to gain from Phase 2/3's
 * flat-array discipline.
 */
class RewardEngine(
    private val sharpeAdaptationRate: Double = DEFAULT_SHARPE_ADAPTATION_RATE,
) {
    companion object {
        /** Moody et al.'s `τ` - how quickly the EWMA moments track the live reward stream. */
        const val DEFAULT_SHARPE_ADAPTATION_RATE = 0.01

        // Numerical safety net, same spirit as ReadoutTrainer's DENOM_EPS:
        // the EWMA variance b_{t-1} - a_{t-1}^2 is >= 0 by construction
        // (Cauchy-Schwarz), but starts at exactly 0 (a_0 = b_0 = 0, so
        // there's no prior variance estimate yet) and can float-round to a
        // hair below 0. dsr_t is conventionally taken to be 0 during this
        // "no variance estimate yet" warm-up rather than NaN/±Inf.
        private const val VARIANCE_FLOOR = 1e-12
    }

    init {
        require(sharpeAdaptationRate > 0.0 && sharpeAdaptationRate <= 1.0) {
            "sharpeAdaptationRate must be in (0, 1], was $sharpeAdaptationRate"
        }
    }

    // EWMA first/second moments of the reward stream (a_t, b_t) - see the
    // class doc's "Differential Sharpe ratio" section. Both start at 0:
    // there is no prior reward history for a freshly constructed engine.
    private var a: Double = 0.0
    private var b: Double = 0.0

    /** One bar's reward, broken into its components, plus the differential Sharpe computed from it. */
    data class RewardBreakdown(
        /** `r_t` - the full per-bar reward, `markToMarketPnl - transactionCost - fundingCost`. */
        val reward: Double,
        /** `Δp_t · f_{t-1}` - mark-to-market P&L on the position already held going into this bar. */
        val markToMarketPnl: Double,
        /** `(δ_t + feeRate_t·p_t) · |Δf_t|` - modeled spread cost plus exchange fee, charged only when the position changes. */
        val transactionCost: Double,
        /** `f_t · p_t · fundingRate_t · (barSpanMs / FundingSchedule.INTERVAL_MS)` - this bar's funding accrual (design doc §3). */
        val fundingCost: Double,
        /** `dsr_t` - the differential Sharpe ratio, computed from `r_t` and the moments *before* this step folded it in. */
        val differentialSharpe: Double,
    )

    /**
     * Computes one bar's reward and differential Sharpe ratio, and folds
     * `r_t` into the internal `a_t`/`b_t` moments for the next call.
     *
     * @param prevMidPrice reference (mid) price going into this bar, `p_{t-1}`.
     * @param currMidPrice reference (mid) price at this bar's close, `p_t`.
     * @param prevPosition position held going into this bar, `f_{t-1}` (bounded `[-1, 1]` by [org.example.syncora.agent.PolicyEngine]; long positive, short negative).
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

        val dsr = differentialSharpe(reward)
        updateMoments(reward)

        return RewardBreakdown(
            reward = reward,
            markToMarketPnl = markToMarketPnl,
            transactionCost = transactionCost,
            fundingCost = fundingCost,
            differentialSharpe = dsr,
        )
    }

    /** `dsr_t`, computed from [r] and the moments as they stood *before* this bar - see class doc. */
    private fun differentialSharpe(r: Double): Double {
        val variance = b - a * a
        if (variance <= VARIANCE_FLOOR) return 0.0
        val deltaA = r - a
        val deltaB = r * r - b
        return (b * deltaA - 0.5 * a * deltaB) / variance.pow(1.5)
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
