package org.example.syncora.rrl

/**
 * A single market observation the agent reacts to: one intraday sampling
 * interval's worth of price, spread and funding information (the paper
 * samples every five minutes; this layer is agnostic to the sampling rate).
 *
 * @param timestampMs observation time.
 * @param bid best bid price.
 * @param ask best ask price.
 * @param features u_t, the external feature vector built by [RrlFeatureExtractor].
 * @param fundingRate the current funding rate kappa_t (eq. 4). Should be 0.0
 *   for observations that do not coincide with a funding settlement, since
 *   funding is paid/received only at settlement (see [FundingSettlementGate]).
 */
data class MarketObservation(
    val timestampMs: Long,
    val bid: Double,
    val ask: Double,
    val features: DoubleArray,
    val fundingRate: Double,
)

/**
 * Full decomposition of one agent step, mirroring Table 1 of the paper
 * (position, execution, carry, pnl) plus the online utility statistics that
 * drove the weight update.
 */
data class RrlStepResult(
    val timestampMs: Long,
    /** f_t after any expected-return gating (eq. 10, tanh-bounded to [-1, 1]). */
    val position: Double,
    /** The model's raw tanh output before gating is applied. */
    val rawPosition: Double,
    /** Delta p_t * f_{t-1}: mark-to-market P&L from holding the previous position. */
    val priceReturn: Double,
    /** -(delta_t + fee) * |Delta f_t|: cost of trading as a price taker (eq. 9 plus exchange fees). */
    val executionCost: Double,
    /** -kappa_t * f_t: funding profit or loss (eq. 4), zero outside settlement bars. */
    val fundingCarry: Double,
    /** r_t, the total net reward for this step (eq. 8). */
    val reward: Double,
    /** mu_t: online expected net return (eq. 7). */
    val expectedReturn: Double,
    /** sigma_t^2: online variance of net returns (eq. 7). */
    val variance: Double,
    /** upsilon_t: the quadratic risk-adjusted utility (eq. 6). */
    val utility: Double,
    /** ir_t: the annualised information ratio. */
    val informationRatio: Double,
    /** lambda used for this step's utility. */
    val riskAppetite: Double,
)
