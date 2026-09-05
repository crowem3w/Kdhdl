package org.example.syncora.rrl

/**
 * Extended Kalman filter weight optimiser, "Algorithm 1" of Borrageiro et
 * al., used to sequentially update the direct recurrent reinforcement
 * learner's readout weights w^out given the gradient of the quadratic
 * utility with respect to those weights, ∇υ_t.
 *
 * Per step, given ∇υ_t:
 *   q  = 1 + ∇υ_t^T P_{t-1} ∇υ_t / τ
 *   k  = P_{t-1} ∇υ_t / (q τ)
 *   w_t = w_{t-1} + k
 *   P_t = P_{t-1} / τ - k k^T q
 *   P_t = P_t * τ                     // variance stabilisation
 *
 * @param dimension d = nInput + nHidden + nBack, the size of the augmented
 *   state / weight vector w^out.
 * @param ridgePenalty beta >= 0, controls the initial precision matrix P_0 = I_d / beta.
 * @param decayFactor tau in (0, 1], the exponential forgetting factor.
 */
class RrlWeightOptimizer(
    dimension: Int,
    private val ridgePenalty: Double = 1.0,
    private val decayFactor: Double = 0.999,
) {
    private val dimension: Int = dimension

    var weights: DoubleArray = DoubleArray(dimension)
        private set

    /** P_t: the approximate inverse Hessian of the utility w.r.t. the weights. */
    private var precision: Array<DoubleArray> = Matrix.identity(dimension, 1.0 / ridgePenalty)

    /** Applies one sequential update of Algorithm 1 given ∇υ_t and returns the updated weights w_t. */
    fun update(utilityGradient: DoubleArray): DoubleArray {
        require(utilityGradient.size == dimension) {
            "expected gradient of size $dimension, got ${utilityGradient.size}"
        }

        val pPrev = precision
        val gradient = utilityGradient

        // q = 1 + ∇υ_t^T P_{t-1} ∇υ_t / τ
        val q = 1.0 + Matrix.quadraticForm(gradient, pPrev) / decayFactor

        // k = P_{t-1} ∇υ_t / (q τ)
        val pGrad = Matrix.multiply(pPrev, gradient)
        val k = Matrix.scale(pGrad, 1.0 / (q * decayFactor))

        // w_t = w_{t-1} + k
        weights = Matrix.add(weights, k)

        // P_t = P_{t-1} / τ - k k^T q, then variance-stabilised: P_t *= τ
        val n = dimension
        val updated = Array(n) { i -> DoubleArray(n) { j -> pPrev[i][j] / decayFactor - k[i] * k[j] * q } }
        for (i in 0 until n) for (j in 0 until n) updated[i][j] *= decayFactor
        precision = updated

        return weights
    }

    fun reset() {
        weights = DoubleArray(dimension)
        precision = Matrix.identity(dimension, 1.0 / ridgePenalty)
    }
}
