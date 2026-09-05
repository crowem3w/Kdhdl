package org.example.syncora.rrl

/**
 * Configuration for the recurrent reinforcement learning agent layer.
 * Defaults mirror the experiment design in section III-C of the paper
 * unless otherwise noted.
 */
data class RrlAgentConfig(
    /** n_input: size of the external feature vector u_t, see [RrlFeatureExtractor]. */
    val nInput: Int = RrlFeatureExtractor.FEATURE_COUNT,
    /** n_hidden: reservoir size (paper: 100). */
    val nHidden: Int = 100,
    /** n_back: number of past positions fed back into the reservoir (paper: 10). */
    val nBack: Int = 10,
    /** alpha: fraction of W^hidden entries sparsified to zero (paper: 0.75). */
    val sparsity: Double = 0.75,
    /** Target spectral radius of W^hidden; must be < 1 for the echo state property. */
    val spectralRadius: Double = 0.9,
    /** Fraction of non-sparsified W^hidden entries whose sign is flipped negative. */
    val signFlipProbability: Double = 0.5,
    /** Ridge penalty beta for the extended Kalman filter, P_0 = I / beta (paper: 1.0). */
    val ridgePenalty: Double = 1.0,
    /** Exponential decay factor tau for the extended Kalman filter (paper: 0.999). */
    val kalmanDecay: Double = 0.999,
    /** Exponential decay factor tau for the online mean/variance estimates of eq. 7. */
    val emaDecay: Double = 0.999,
    /**
     * Risk appetite: how lambda in the quadratic utility (eq. 6) is chosen.
     * [RiskAppetiteMode.FIXED] mirrors the paper's experiment (lambda = 1e-5);
     * [RiskAppetiteMode.INFORMATION_RATIO] follows lambda = ir_t / sigma_t as
     * derived in section III-B2.
     */
    val riskAppetiteMode: RiskAppetiteMode = RiskAppetiteMode.FIXED,
    /** Fixed risk appetite constant lambda > 0, used when [riskAppetiteMode] is FIXED. */
    val fixedRiskAppetite: Double = 0.00001,
    /** Annualisation factor for the information ratio (paper uses sqrt(252)). */
    val annualisationFactor: Double = kotlin.math.sqrt(252.0),
    /** Benchmark/baseline return b_t used when computing the information ratio. */
    val benchmarkReturn: Double = 0.0,
    /**
     * Default exchange taker fee rate applied on top of half the bid/ask spread (paper: 5 bps =
     * 0.0005). This is only a starting value for [RrlAgentLayer]; it is refreshed at runtime
     * whenever an account-specific rate arrives via [RrlAgentLayer.onFeeRates], since the fee is
     * an operational input rather than part of the agent's learned structure.
     */
    val exchangeFeeRate: Double = 0.0005,
    /**
     * When true, the agent is only allowed to trade freely while its online
     * expected net return mu_t >= 0; otherwise it is flattened to zero and
     * waits for an opportunity to re-enter (final bullet of section III-C).
     */
    val gateOnExpectedReturn: Boolean = true,
    /** Random seed for reservoir weight initialisation, for reproducibility. */
    val seed: Long = 42L,
) {
    enum class RiskAppetiteMode { FIXED, INFORMATION_RATIO }
}
