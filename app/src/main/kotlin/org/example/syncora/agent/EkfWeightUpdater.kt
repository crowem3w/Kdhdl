package org.example.syncora.agent

/**
 * The Extended Kalman Filter (EKF) recursion `PolicyEngine` uses to turn a
 * per-weight utility gradient into a weight update (see
 * `agent-architecture-gap-closure.md` Gap 3 and Borrageiro, Firoozye &
 * Barucca 2022, subsection III-B2). This replaces the plain fixed-
 * learning-rate gradient-ascent step [PolicyEngine.update] used before
 * gap-closure #3 - the RTRL trace computation in [PolicyEngine.step] that
 * produces the per-weight gradient `∇υ_t` is unchanged; only *how that
 * gradient is turned into a weight delta* moves here.
 *
 * ### The recursion
 * With ridge penalty `β` and exponential decay `τ`:
 * ```
 * Initialize: P_0 = I / β
 * For each t:
 *   q   = 1 + ∇υ_tᵀ P_{t-1} ∇υ_t / τ
 *   k   = P_{t-1} ∇υ_t / (q · τ)
 *   Δw  = k                              (caller adds this to w_{t-1})
 *   P_t = (P_{t-1} / τ − k kᵀ q) · τ      (rank-one update, then the
 *                                          variance-stabilization rescale)
 * ```
 * `k` doubles as both the Kalman gain and the weight delta itself - unlike
 * [ReadoutTrainer]'s RLS, which scales its analogous gain by a scalar
 * innovation `(target - prediction)`, this recursion has no separate
 * innovation term because `∇υ_t` (the utility gradient) already *is* the
 * quantity being driven toward zero.
 *
 * ### Relationship to [ReadoutTrainer]'s RLS
 * Structurally the same shape as [ReadoutTrainer]'s covariance recursion -
 * same `P`-is-`nWeights x nWeights`-flat-row-major discipline, same
 * matrix-vector-product-then-rank-one-downdate cost profile - which is
 * deliberate: this class reuses that shape rather than inventing a new
 * one, per the gap-closure plan's own note to reuse [ReadoutTrainer]'s `P`
 * code shape.
 *
 * ### Performance
 * `O(nWeights^2)` per [computeDelta] call (the `P_{t-1} ∇υ_t` product and
 * the rank-one downdate) - same order as [ReadoutTrainer.update], which
 * the existing benchmark tests already show comfortably fits the bar-close
 * budget. Flat `FloatArray`s throughout, no boxed `Double`, no object
 * graphs. The three scratch buffers below are the only allocations this
 * class ever performs, all at construction time.
 */
class EkfWeightUpdater(
    val nWeights: Int,
    val beta: Float = DEFAULT_BETA,
    val tau: Float = DEFAULT_TAU,
) {
    companion object {
        /** Ridge penalty - `P` is initialized to `I / beta`, same large-prior-covariance spirit as [ReadoutTrainer.DEFAULT_INITIAL_COVARIANCE_SCALE] (there, `P_0 = initialCovarianceScale * I`; here it's the reciprocal, per the paper's `β` convention). */
        const val DEFAULT_BETA = 0.01f

        /** Exponential decay - analogous role to [ReadoutTrainer.DEFAULT_FORGETTING_FACTOR], but appears differently in this recursion's algebra (dividing/multiplying `P` rather than a single `lambda` blend), per the paper's EKF formulation. */
        const val DEFAULT_TAU = 0.995f

        // Guards q: mathematically q = 1 + gradᵀ P grad / tau >= 1 whenever P
        // is positive semi-definite (which it is by construction - diagonal
        // init, and the downdate below preserves PSD-ness up to float
        // rounding) and tau > 0. This is a numerical safety net against that
        // rounding, not the expected path - see [computeDelta].
        private const val Q_EPS = 1e-8f
    }

    init {
        require(nWeights >= 1) { "nWeights must be >= 1, was $nWeights" }
        require(beta > 0f) { "beta must be > 0, was $beta" }
        require(tau > 0f) { "tau must be > 0, was $tau" }
    }

    // P is nWeights x nWeights, flat row-major, symmetric - same discipline
    // as ReadoutTrainer's RLS covariance matrix.
    private val covariance: FloatArray = FloatArray(nWeights * nWeights).also { p ->
        val diag = 1f / beta
        var i = 0
        while (i < nWeights) {
            p[i * nWeights + i] = diag
            i++
        }
    }

    // Scratch buffers reused by every computeDelta() call - the only
    // allocations beyond `covariance` itself, and those only happen once,
    // here in the constructor.
    private val pgScratch = FloatArray(nWeights)
    private val kScratch = FloatArray(nWeights)

    /**
     * One EKF step: turns the per-weight utility gradient [gradUtility]
     * (`∇υ_t` - in [PolicyEngine], `(dUtilityDReward * dRewardDPosition) *
     * trace_i` for each weight `i`, i.e. exactly what the old gradient-
     * ascent step applied directly, scaled by `learningRate`) into a
     * weight delta via the class doc's recursion, and updates the internal
     * covariance `P` in place.
     *
     * @return `k`, the delta to add to each weight (`w_i += result[i]`).
     *   The caller still owns clipping / `isFinite` guards on the result
     *   and on the resulting weights - same defense-in-depth posture the
     *   app already applies around [ReadoutTrainer]'s RLS, kept
     *   deliberately since the paper's EKF recursion is not itself
     *   guaranteed bounded.
     */
    fun computeDelta(gradUtility: FloatArray): FloatArray {
        require(gradUtility.size == nWeights) {
            "gradUtility size ${gradUtility.size} != nWeights $nWeights"
        }

        // Pg = P_{t-1} * gradUtility
        var i = 0
        while (i < nWeights) {
            pgScratch[i] = dot(covariance, i * nWeights, gradUtility)
            i++
        }

        // q = 1 + gradUtilityᵀ Pg / tau
        var gPg = 0f
        i = 0
        while (i < nWeights) {
            gPg += gradUtility[i] * pgScratch[i]
            i++
        }
        val q = 1f + gPg / tau
        if (!q.isFinite() || q < Q_EPS) {
            // Degenerate/ill-conditioned step: leave P untouched and return
            // a zero delta rather than risk corrupting the covariance with
            // a division by a near-zero/non-finite q.
            kScratch.fill(0f)
            return kScratch
        }

        // k = Pg / (q * tau)
        val denom = q * tau
        i = 0
        while (i < nWeights) {
            kScratch[i] = pgScratch[i] / denom
            i++
        }

        // P_t = (P_{t-1} / tau - k kᵀ q) * tau
        //     = P_{t-1} - k kᵀ q tau
        val kkScale = q * tau
        i = 0
        while (i < nWeights) {
            val rowBase = i * nWeights
            val kI = kScratch[i]
            var j = 0
            while (j < nWeights) {
                covariance[rowBase + j] -= kI * kScratch[j] * kkScale
                j++
            }
            i++
        }

        return kScratch
    }

    /** Dot product of `row` (a length-[nWeights] slice of a flat matrix starting at [base]) with [x]. */
    private fun dot(flatMatrix: FloatArray, base: Int, x: FloatArray): Float {
        var acc = 0f
        var k = 0
        while (k < nWeights) {
            acc += flatMatrix[base + k] * x[k]
            k++
        }
        return acc
    }

    /** Snapshot (fresh copy) of the EKF covariance matrix `P` - diagnostic/logging use only, not read on the hot path. */
    fun covarianceSnapshot(): FloatArray = covariance.copyOf()

    /** True iff every entry of `P` is finite - mirrors [ReadoutTrainer.isStable]'s role for the RLS covariance. */
    fun isStable(): Boolean {
        for (p in covariance) if (!p.isFinite()) return false
        return true
    }

    /**
     * The largest absolute value anywhere in `P` - mirrors
     * [ReadoutTrainer.covarianceMagnitude]'s role as an early-warning
     * divergence signal that fires well before [isStable] would (`P` can
     * grow unboundedly while every entry stays finite right up until the
     * step it overflows).
     */
    fun covarianceMagnitude(): Float {
        var maxAbs = 0f
        for (p in covariance) {
            if (!p.isFinite()) return Float.POSITIVE_INFINITY
            val a = kotlin.math.abs(p)
            if (a > maxAbs) maxAbs = a
        }
        return maxAbs
    }
}
