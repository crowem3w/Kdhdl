package org.example.syncora.agent

/**
 * Extended Kalman Filter (EKF) weight updater for [PolicyEngine] (see
 * `agent-architecture-gap-closure.md` Gap 3 and Borrageiro, Firoozye &
 * Barucca 2022, subsection III-B2 / `rrl_crypto_agent_architecture.md`
 * §3 "Online weight update").
 *
 * ### What this replaces
 * [PolicyEngine] used to update its weights via plain gradient ascent:
 * `w_i += learningRate * dU/df_t * trace_i`, a fixed, isotropic step size
 * for every weight. The paper instead treats the weight vector as the
 * hidden state of an EKF and the RTRL trace as (proportional to) an
 * observation driving that state, giving each weight its own,
 * curvature-aware adaptive step size via the filter's covariance `P` -
 * the same second-order intuition as Newton's method, but built up
 * recursively online instead of requiring a batch Hessian.
 *
 * This class only replaces *how the RTRL trace becomes a weight delta*.
 * The trace itself - `d(f_t)/d(w_i)`, computed via [PolicyEngine]'s own
 * truncated RTRL through the feedback recurrence - is unchanged; see
 * [PolicyEngine.update] for how the two compose.
 *
 * ### Bug fix: the Jacobian and the residual must stay separate
 * An earlier revision fused `dUtilityDReward * dRewardDPosition` (a
 * *scalar* - the sensitivity of utility to the policy's raw output,
 * `dU/df_t`) into the per-weight trace *before* handing the whole product
 * to this class as a single `gradUtility` vector, then used that fused
 * vector both to form the Kalman gain (`q`, `k`) *and* as the delta
 * itself. That conflates two different roles: in the standard EKF
 * training recursion for a network's weights (Williams 1992; Haykin,
 * *Kalman Filtering and Neural Networks*, ch. 2 - also what
 * [ReadoutTrainer]'s own RLS mirrors, see its class doc: a *raw regressor*
 * `x_t` builds `Px`/`denom`, and a *separate scalar residual* `e_o` scales
 * the delta), the quantity that should build the covariance/gain (`P`,
 * `q`, `k`) is the network's own output Jacobian `H_t = d(f_t)/d(w)` - i.e.
 * [PolicyEngine]'s RTRL [PolicyEngine.traceSnapshot] alone - while the
 * scalar `dU/df_t` only ever multiplies the *final* delta.
 *
 * Feeding the fused vector into the quadratic form instead means the
 * "information" the filter thinks it has just observed scales with the
 * *square* of the current error, so a single early, large-error step
 * collapses `P` almost to zero (see the class's own
 * `EkfWeightUpdaterTest` for the regression that catches this: with the
 * fused vector, `P`'s trace fell from `1400` to `~0.14` within the first
 * hundred steps and the weights never recovered, converging to nowhere
 * near the true target). Feeding the *raw trace* keeps `P`'s scale tied to
 * the regressor's own geometry - exactly as RLS does - so the filter
 * converges the way the paper's Monte Carlo results imply it should.
 *
 * ### The recursion (paper §III-B2 / doc §3), corrected
 * ```
 * H_t   = trace_t                                  (d(f_t)/d(w), NOT pre-multiplied by dU/df_t)
 * q     = 1 + H_tᵀ P_{t-1} H_t / tau
 * gain  = P_{t-1} H_t / (q * tau)
 * k     = (dU/df_t) * gain                          (the scalar residual scales the delta only)
 * w_t   = w_{t-1} + k
 * P_t   = P_{t-1} - (q * tau) * gain gainᵀ
 * ```
 * (The doc's `P_t = P_{t-1}/tau − k kᵀ q` then `P_t *= tau` "stabilization"
 * step algebraically cancels to exactly `P_{t-1} − (q·tau)·k kᵀ` - the two
 * lines were never doing anything beyond that single downdate. Written as
 * one step here to avoid implying there's a separate forgetting-factor
 * growth the old two-line form didn't actually provide.)
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
 * passes (the `Pg = P * trace` matrix-vector product and the `P`
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
    private val gainScratch = FloatArray(nWeights)
    private val kScratch = FloatArray(nWeights)

    /**
     * One EKF step: turns [trace] (the RTRL Jacobian `H_t = d(f_t)/d(w)`)
     * and the scalar [residual] (`dU/df_t`) into a weight delta `k`, and
     * updates the internal covariance `P` in place. See the class doc for
     * the recursion and for why `trace` and `residual` must stay separate
     * arguments rather than being pre-multiplied by the caller.
     * `O(nWeights^2)`, no allocation.
     *
     * @param trace `H_t` - the per-weight RTRL trace `d(f_t)/d(w_i)` from
     *   [PolicyEngine.traceSnapshot]/`lastTrace`. This is the network's own
     *   output-sensitivity Jacobian and must **not** be pre-scaled by the
     *   utility gradient - see the class doc's "bug fix" note.
     * @param residual `dU/df_t` - `dUtilityDReward * dRewardDPosition`,
     *   the scalar sensitivity of utility to the policy's raw output.
     *   Scales the resulting delta only; it never enters the covariance
     *   update, exactly mirroring how [ReadoutTrainer.update] keeps its
     *   scalar error `e_o` out of the `P` downdate.
     * @return the weight delta `k` to add - `w_t = w_{t-1} + k`. Returns
     *   [kScratch], the engine's own scratch buffer (same convention as
     *   [ReservoirEngine.step]/[ReadoutTrainer.predict]) - copy it if a
     *   stable snapshot is needed past the caller's immediate use. If the
     *   update denominator degenerates (guarded by [DENOM_EPS], mirroring
     *   [ReadoutTrainer]'s own guard) or [residual] isn't finite, returns
     *   an all-zero delta and leaves `P` untouched rather than risking a
     *   corrupting step.
     */
    fun computeDelta(trace: FloatArray, residual: Float): FloatArray {
        require(trace.size == nWeights) {
            "trace size ${trace.size} != nWeights $nWeights"
        }
        if (!residual.isFinite()) {
            kScratch.fill(0f)
            return kScratch
        }

        // Pg = P_{t-1} * H_t  (H_t = trace, NOT residual-scaled)
        var i = 0
        while (i < nWeights) {
            pgScratch[i] = dot(covariance, i * nWeights, trace)
            i++
        }

        // q = 1 + H_t^T Pg / tau
        var quad = 0f
        i = 0
        while (i < nWeights) {
            quad += trace[i] * pgScratch[i]
            i++
        }
        val q = 1f + quad / tau
        val denom = q * tau
        if (!denom.isFinite() || kotlin.math.abs(denom) < DENOM_EPS) {
            kScratch.fill(0f)
            return kScratch
        }

        // gain = Pg / (q * tau); k = residual * gain. gainScratch is reused
        // (post-multiplication) as kScratch's backing buffer via in-place
        // scaling, so both the covariance downdate (which needs the
        // *unscaled* gain) and the returned delta (which needs the
        // *residual-scaled* gain) are available without an extra buffer.
        i = 0
        while (i < nWeights) {
            gainScratch[i] = pgScratch[i] / denom
            i++
        }

        // P_t = P_{t-1} - (q * tau) * gain * gain^T  - uses the unscaled
        // gain, never the residual, so the covariance reflects only the
        // regressor's own geometry (see class doc's "bug fix" note).
        i = 0
        while (i < nWeights) {
            val rowBase = i * nWeights
            val gi = gainScratch[i]
            var j = 0
            while (j < nWeights) {
                covariance[rowBase + j] -= gi * gainScratch[j] * denom
                j++
            }
            i++
        }

        i = 0
        while (i < nWeights) {
            kScratch[i] = residual * gainScratch[i]
            i++
        }

        return kScratch
    }

    /** Dot product of `row` (a length-[nWeights] slice of a flat matrix starting at [base]) with [vector]. */
    private fun dot(flatMatrix: FloatArray, base: Int, vector: FloatArray): Float {
        var acc = 0f
        var k = 0
        while (k < nWeights) {
            acc += flatMatrix[base + k] * vector[k]
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
