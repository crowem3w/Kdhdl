package org.example.syncora.agent

/**
 * Extended Kalman Filter (EKF) weight updater for [PolicyEngine] (see
 * `agent-architecture-gap-closure.md` Gap 3 and Borrageiro, Firoozye &
 * Barucca 2022, subsection III-B2 / `rrl_crypto_agent_architecture.md`
 * §3 "Online weight update").
 *
 * ### What this replaces
 * [PolicyEngine] used to update its weights via plain gradient ascent:
 * `w_i += learningRate * gradUtility_i`, a fixed, isotropic step size for
 * every weight. The paper instead treats the weight vector as the hidden
 * state of an EKF and the utility gradient `∇υ_t` as (proportional to)
 * an observation driving that state, giving each weight its own,
 * curvature-aware adaptive step size via the filter's covariance `P` -
 * the same second-order intuition as Newton's method, but built up
 * recursively online instead of requiring a batch Hessian.
 *
 * This class only replaces *how the RTRL trace becomes a weight delta*.
 * The trace itself - `d(f_t)/d(w_i)`, computed via [PolicyEngine]'s own
 * truncated RTRL through the feedback recurrence - is unchanged; see
 * [PolicyEngine.update] for how the two compose:
 * `gradUtility_i = dUtilityDReward * dRewardDPosition * trace_i`, and
 * that product is what's passed into [computeDelta] here.
 *
 * ### The recursion (paper §III-B2 / doc §3)
 * ```
 * q     = 1 + ∇υ_tᵀ P_{t-1} ∇υ_t / tau
 * k     = P_{t-1} ∇υ_t / (q * tau)
 * w_t   = w_{t-1} + k
 * P_t   = P_{t-1}/tau - k kᵀ q
 * P_t   = P_t * tau                     (variance stabilization)
 * ```
 * `P` is initialized to `I / beta` (`beta` a ridge penalty - a small
 * `beta` means a large initial `P`, i.e. very little prior confidence, so
 * early updates move fast, the same role [ReadoutTrainer]'s
 * `initialCovarianceScale` plays for its RLS `P`). `tau` is an
 * exponential decay constant that both discounts older observations
 * (paralleling [ReadoutTrainer.forgettingFactor]) and appears in the
 * "stabilization" rescale that keeps `P` from drifting outside a sane
 * numerical range over a long online session.
 *
 * `w_t = w_{t-1} + k` means `k` **is** the weight delta - [computeDelta]
 * returns it directly rather than an updated weight vector, so the
 * caller ([PolicyEngine.update]) applies its own clipping/`isFinite`
 * guards on top: the EKF itself has no boundedness guarantee the way a
 * clamped gradient-ascent step did, so the app keeps its existing
 * defense-in-depth as an outer safety net around it - a deliberate,
 * documented divergence from the paper, not an oversight.
 *
 * ### Performance
 * Same shape and cost discipline as [ReadoutTrainer]'s RLS: `P` is a
 * flat, row-major `FloatArray` (`nWeights x nWeights`), no boxed
 * `Double`, no `Array<FloatArray>`. [computeDelta]'s two `O(nWeights^2)`
 * passes (the `Pg = P * gradUtility` matrix-vector product and the `P`
 * rank-one downdate) are the only per-step cost - the same order as the
 * RLS update it structurally mirrors. Scratch buffers are allocated once,
 * here in the constructor.
 */
class EkfWeightUpdater(
    val nWeights: Int,
    val beta: Float = DEFAULT_BETA,
    val tau: Float = DEFAULT_TAU,
) {
    companion object {
        /** Ridge penalty: `P` is initialized to `I / beta`. Small `beta` -> large initial `P` -> fast early adaptation, same role as [ReadoutTrainer.DEFAULT_INITIAL_COVARIANCE_SCALE] plays inversely. */
        const val DEFAULT_BETA = 0.01f

        /** Exponential decay constant, `(0, 1]`. `tau = 1` disables both the forgetting and the stabilization rescale (a pure, undiscounted EKF); slightly below 1 mirrors [ReadoutTrainer.DEFAULT_FORGETTING_FACTOR]'s role. */
        const val DEFAULT_TAU = 0.999f

        // Guards q * tau against underflow/near-zero the same way
        // ReadoutTrainer.DENOM_EPS guards its RLS denominator: q = 1 +
        // (nonnegative quadratic form)/tau >= 1 whenever P is PSD (true by
        // construction - diagonal init, and the downdate below preserves
        // PSD-ness up to float rounding) and tau > 0, so q*tau >= tau > 0
        // in exact arithmetic. This is a numerical safety net against
        // float rounding, not the expected path.
        private const val DENOM_EPS = 1e-8f
    }

    init {
        require(nWeights >= 1) { "nWeights must be >= 1, was $nWeights" }
        require(beta > 0f) { "beta must be > 0, was $beta" }
        require(tau > 0f) { "tau must be > 0, was $tau" }
    }

    // P, nWeights x nWeights, flat row-major, symmetric - same discipline
    // as ReadoutTrainer's RLS covariance. This is the only state this
    // class mutates; PolicyEngine owns the weight vector itself.
    private val covariance: FloatArray = FloatArray(nWeights * nWeights).also { p ->
        val diag = 1f / beta
        var i = 0
        while (i < nWeights) {
            p[i * nWeights + i] = diag
            i++
        }
    }

    // Scratch buffers, allocated once - the only allocations this class
    // ever performs beyond the covariance matrix itself.
    private val pgScratch = FloatArray(nWeights)
    private val kScratch = FloatArray(nWeights)

    /**
     * One EKF step: turns the per-weight utility gradient [gradUtility]
     * into a weight delta `k`, and updates the internal covariance `P` in
     * place. See the class doc for the recursion; `O(nWeights^2)`, no
     * allocation.
     *
     * @param gradUtility `∇υ_t` - `dUtilityDReward * dRewardDPosition *
     *   trace_i` per weight, i.e. exactly what [PolicyEngine.update] used
     *   to apply directly as a gradient-ascent step before this gap.
     * @return the weight delta `k` to add - `w_t = w_{t-1} + k`. Returns
     *   [kScratch], the engine's own scratch buffer (same convention as
     *   [ReservoirEngine.step]/[ReadoutTrainer.predict]) - copy it if a
     *   stable snapshot is needed past the caller's immediate use. If the
     *   update denominator degenerates (guarded by [DENOM_EPS], mirroring
     *   [ReadoutTrainer]'s own guard), returns an all-zero delta and
     *   leaves `P` untouched rather than risking a corrupting step.
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

        // q = 1 + gradUtility^T Pg / tau
        var quad = 0f
        i = 0
        while (i < nWeights) {
            quad += gradUtility[i] * pgScratch[i]
            i++
        }
        val q = 1f + quad / tau
        val denom = q * tau
        if (!denom.isFinite() || kotlin.math.abs(denom) < DENOM_EPS) {
            kScratch.fill(0f)
            return kScratch
        }

        // k = Pg / (q * tau)
        i = 0
        while (i < nWeights) {
            kScratch[i] = pgScratch[i] / denom
            i++
        }

        // P_t = P_{t-1}/tau - k k^T q, then *= tau (stabilization),
        // algebraically combined into one pass: P_t = P_{t-1} - k k^T (q * tau)
        val kqTau = q * tau
        i = 0
        while (i < nWeights) {
            val rowBase = i * nWeights
            val ki = kScratch[i]
            var j = 0
            while (j < nWeights) {
                covariance[rowBase + j] -= ki * kScratch[j] * kqTau
                j++
            }
            i++
        }

        return kScratch
    }

    /** Dot product of `row` (a length-[nWeights] slice of a flat matrix starting at [base]) with [gradUtility]. */
    private fun dot(flatMatrix: FloatArray, base: Int, gradUtility: FloatArray): Float {
        var acc = 0f
        var k = 0
        while (k < nWeights) {
            acc += flatMatrix[base + k] * gradUtility[k]
            k++
        }
        return acc
    }

    /** Snapshot (fresh copy) of the EKF covariance matrix `P` - for logging/diagnostics/tests only, mirrors [ReadoutTrainer.covarianceSnapshot]. */
    fun covarianceSnapshot(): FloatArray = covariance.copyOf()

    /**
     * True iff every entry of `P` is finite - mirrors
     * [ReadoutTrainer.isStable]'s role for its own RLS covariance. A
     * backtest/soak replay should assert this after every
     * [PolicyEngine.update] call.
     */
    fun isStable(): Boolean {
        for (p in covariance) if (!p.isFinite()) return false
        return true
    }

    /**
     * The largest absolute value anywhere in `P` - mirrors
     * [ReadoutTrainer.covarianceMagnitude]'s early-warning role: `P` can
     * grow unboundedly while every entry stays finite right up until the
     * step it overflows, so a magnitude ceiling a few orders of magnitude
     * above `1/beta` (`P`'s initial scale) is a cheap, meaningful
     * divergence signal that fires before [isStable] would.
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
