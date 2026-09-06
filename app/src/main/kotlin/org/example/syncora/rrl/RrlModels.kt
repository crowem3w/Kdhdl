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

/**
 * A snapshot of every piece of state [RecurrentReinforcementLearner.step]
 * carries from one call to the next: the echo-state reservoir's dynamical
 * state, the extended-Kalman-filter readout weights and precision matrix,
 * and the small amount of recurrent bookkeeping the learner itself owns.
 * See [RecurrentReinforcementLearner.snapshotState] / `restoreState` and
 * [RrlAgentCheckpoint], which persists this alongside [RrlAgentLayer]'s own
 * cached market state.
 */
data class RrlLearnerState(
    /** x_t, the echo-state reservoir's internal dynamical state. */
    val reservoirState: DoubleArray,
    /** w^out_t, the learned readout weights (eq. 10). */
    val optimizerWeights: DoubleArray,
    /** P_t, the EKF's approximate inverse-Hessian precision matrix (Algorithm 1). */
    val optimizerPrecision: Array<DoubleArray>,
    /** Circular buffer of the last n_back desired positions, oldest first (eq. 11). */
    val pastPositions: DoubleArray,
    /** f_{t-1}, the previous step's desired position. */
    val previousPosition: Double,
    /** z_{t-1}, the previous step's augmented reservoir state; null before the first step. */
    val previousZ: DoubleArray?,
    /** df_{t-1}/dw_{t-1}^out, used by the recursive term of eq. 12 on the next step. */
    val previousDfDw: DoubleArray,
    /** mu_t, the online exponentially-weighted expected net return (eq. 7). */
    val expectedReturn: Double,
    /** sigma_t^2, the online exponentially-weighted variance of net returns (eq. 7). */
    val returnVariance: Double,
)
