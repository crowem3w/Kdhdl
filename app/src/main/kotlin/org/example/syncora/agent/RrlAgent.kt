package org.example.syncora.agent

import kotlin.math.tanh
import kotlin.random.Random

/**
 * The direct, recurrent reinforcement learning agent - the paper's target
 * model (Borrageiro, Firoozye & Barucca 2022, subsection III-B2), trained
 * against the quadratic utility of eq. 6 via the extended Kalman filter of
 * Algorithm 1.
 *
 * ### Redesign note (replaces `PolicyEngine` + `ReadoutTrainer`)
 * The previous implementation split this into two independently-trained
 * pieces: an RLS-trained forecast (`ReadoutTrainer`, feeding a single
 * scalar into the policy) and a policy trained via plain gradient ascent
 * on the differential Sharpe ratio. Neither matches the paper's actual
 * model. The paper explicitly says its procedure "differs from the
 * original echo state network formulation" precisely because there is
 * *no* separately-trained regression readout in the final model - eq. 10
 * has a single weight vector `w_out` over the single augmented state
 * `z_t = [u_t, x_t, ŷ_t]` (eq. 5), and that vector is trained end-to-end
 * against the quadratic utility (eq. 6), not a supervised proxy target and
 * not the differential Sharpe ratio directly - `dsr_t` is the training
 * signal for the *unrelated* Moody & Saffell scheme the paper only
 * discusses in its literature review (§II-C1), not the scheme its own
 * crypto-agent experiment runs.
 *
 * ```
 * z_t = [u_t, x_t, ŷ_t, 1]                    (eq. 5, plus a bias term)
 * f_t = tanh(w_out_t · z_t)                    (eq. 10)
 * ```
 *
 * ### Training: EKF on ∇υ_t (Algorithm 1)
 * `w_out` is updated by the extended Kalman filter in Algorithm 1, driven
 * by `∇υ_t` (eq. 12), not plain gradient ascent with a fixed learning
 * rate. [RewardEngine.quadraticUtilityGradientWrtReward] supplies
 * `dυ_t/dr_t`; [RewardEngine.positionGradient] supplies `dr_t/df_t`; this
 * class supplies the third factor, `d(f_t)/d(w_i)`, via [lastTrace].
 *
 * ### The trace, now that `ŷ_t` feeds the reservoir too
 * With `W_back` wired into the reservoir (see the redesigned
 * `ReservoirEngine`), `w_out`'s influence on `x_t` no longer stops at the
 * policy's own regressor - it also runs *through* the last `n_back`
 * positions into `W_back`, into `x_t`, back into this step's regressor.
 * Naively this looks like it needs a full `d(x_t)/d(w_i)` trace of shape
 * `n_hidden x n_regressors`, which would be expensive. It collapses,
 * though: since `W_hidden`/`W_input`/`W_back` are fixed (only `w_out` is
 * trained), `w_out`'s effect on `x_t` flows *only* through the `ŷ_t`
 * feedback term, and the chain rule through that single path telescopes
 * into an "effective feedback weight" per lag,
 * ```
 * effectiveBackWeight_k = Σ_h  w_out[x_h] · (1 − x_t[h]²) · W_back[h, k]
 * ```
 * an `n_back`-length vector, computed once per step in `O(n_hidden ·
 * n_back)`. Folding it into the usual own-output feedback term recovers
 * exactly the paper's eq. 12 two-term recursion at the original,
 * `O(n_regressors · n_back)`-per-step cost - no `n_hidden`-sized trace
 * needed. Full derivation:
 * ```
 * d(f_t)/d(w_i) = (1 − f_t²) · [ z_t_i
 *     + Σ_k (w_out[ŷ_k] + effectiveBackWeight_k) · d(f_{t-k})/d(w_i) ]
 * ```
 * which is [step]'s `newTraceScratch` computation below, verbatim.
 */
class RrlAgent(
    val nInput: Int,
    val nHidden: Int,
    val nBack: Int,
    private val wBack: FloatArray, // shared with ReservoirWeights.wBack, n_hidden x n_back, fixed
    val ridgePenalty: Float = DEFAULT_RIDGE_PENALTY,
    val decayFactor: Float = DEFAULT_DECAY_FACTOR,
    seed: Long = 0L,
) {
    companion object {
        /** `β` per the paper's experiment design (subsection III-C): "Set the ridge penalty β = 1". */
        const val DEFAULT_RIDGE_PENALTY = 1f
        /** `τ` per the paper's experiment design: "the exponential decay factor τ = 0.999". */
        const val DEFAULT_DECAY_FACTOR = 0.999f
        const val DEFAULT_INIT_SCALE = 0.05f
    }

    /** `z_t = [u_t, x_t, ŷ_t, 1]` width - eq. 5's augmented state plus a trailing bias. */
    val nRegressors: Int = nInput + nHidden + nBack + 1

    private val xBase: Int = nInput
    private val yBase: Int = nInput + nHidden
    private val biasIndex: Int = nInput + nHidden + nBack

    init {
        require(nInput >= 1)
        require(nHidden >= 1)
        require(nBack >= 0)
        require(wBack.size == nHidden * nBack) {
            "wBack size ${wBack.size} != nHidden*nBack ${nHidden * nBack}"
        }
        require(ridgePenalty > 0f) { "ridgePenalty must be > 0, was $ridgePenalty" }
        require(decayFactor > 0f && decayFactor <= 1f) {
            "decayFactor must be in (0, 1], was $decayFactor"
        }
    }

    // ---- w_out: the only trained parameter vector ----
    private val weights: FloatArray = run {
        val rng = Random(seed)
        FloatArray(nRegressors) { (rng.nextFloat() * 2f - 1f) * DEFAULT_INIT_SCALE }
    }

    // ---- EKF precision matrix P (Algorithm 1: P = I_d / beta) ----
    private val precision: FloatArray = FloatArray(nRegressors * nRegressors).also { p ->
        var i = 0
        while (i < nRegressors) {
            p[i * nRegressors + i] = 1f / ridgePenalty
            i++
        }
    }

    // ---- recurrent state ----
    private val pastPositions: FloatArray = FloatArray(nBack) // ŷ_t: [f_{t-1}, ..., f_{t-nBack}]
    private val traceHistory: FloatArray = FloatArray(nBack * nRegressors)

    private val regressorScratch = FloatArray(nRegressors)
    private val newTraceScratch = FloatArray(nRegressors)
    private val effectiveBackWeight = FloatArray(nBack)
    private val lastTrace = FloatArray(nRegressors)

    // ---- EKF scratch (Algorithm 1) ----
    private val pGradScratch = FloatArray(nRegressors) // P_{t-1} . g
    private val kScratch = FloatArray(nRegressors)      // Kalman gain k

    private var lastPosition: Float = 0f

    fun currentPosition(): Float = lastPosition

    /** Current own-output feedback vector `ŷ_t = [f_{t-1}, ..., f_{t-nBack}]`, to pass into [ReservoirEngine.step]. */
    fun feedbackForReservoir(): FloatArray = pastPositions

    /**
     * Advances the agent by one bar-close step, producing `f_t` (eq. 10)
     * from this bar's `u_t`, the reservoir's `x_t` (already computed
     * *using this same* [feedbackForReservoir] output - see class doc),
     * and this agent's own trailing `ŷ_t`/bias.
     */
    fun step(u: FloatArray, reservoirState: FloatArray): Float {
        require(u.size == nInput) { "u size ${u.size} != nInput $nInput" }
        require(reservoirState.size == nHidden) {
            "reservoirState size ${reservoirState.size} != nHidden $nHidden"
        }
        buildRegressor(u, reservoirState)

        var z = 0f
        var i = 0
        while (i < nRegressors) {
            z += weights[i] * regressorScratch[i]
            i++
        }
        val f = tanh(z.toDouble()).toFloat()
        val dtanh = 1f - f * f

        // effectiveBackWeight_k = sum_h w_out[x_h] * (1 - x_t[h]^2) * W_back[h,k]
        // - see class doc's trace derivation.
        var k = 0
        while (k < nBack) {
            var acc = 0f
            var h = 0
            while (h < nHidden) {
                val xh = reservoirState[h]
                acc += weights[xBase + h] * (1f - xh * xh) * wBack[h * nBack + k]
                h++
            }
            effectiveBackWeight[k] = acc
            k++
        }

        i = 0
        while (i < nRegressors) {
            var indirect = 0f
            k = 0
            while (k < nBack) {
                val combined = weights[yBase + k] + effectiveBackWeight[k]
                indirect += combined * traceHistory[k * nRegressors + i]
                k++
            }
            newTraceScratch[i] = dtanh * (regressorScratch[i] + indirect)
            i++
        }
        System.arraycopy(newTraceScratch, 0, lastTrace, 0, nRegressors)

        var kk = nBack - 1
        while (kk >= 1) {
            System.arraycopy(traceHistory, (kk - 1) * nRegressors, traceHistory, kk * nRegressors, nRegressors)
            kk--
        }
        if (nBack > 0) System.arraycopy(lastTrace, 0, traceHistory, 0, nRegressors)

        var p = nBack - 1
        while (p >= 1) {
            pastPositions[p] = pastPositions[p - 1]
            p--
        }
        if (nBack > 0) pastPositions[0] = f

        lastPosition = f
        return f
    }

    /**
     * Extended Kalman filter update (Algorithm 1), applied to
     * `∇υ_t = (dυ_t/dr_t · dr_t/df_t) · d(f_t)/d(w)` - the RRL agent's
     * gradient of the quadratic utility w.r.t. its own weights, using
     * this step's [lastTrace] for the third factor.
     *
     * @param dUtilityDReward `dυ_t/dr_t` - [RewardEngine.quadraticUtilityGradientWrtReward] from the *same* bar's [RewardEngine.step].
     * @param dRewardDPosition `dr_t/df_t` - [RewardEngine.positionGradient] for the *same* `prevPosition`/`currPosition` pair this bar's [step] produced.
     */
    fun update(dUtilityDReward: Double, dRewardDPosition: Double) {
        val coeff = (dUtilityDReward * dRewardDPosition).toFloat()
        if (!coeff.isFinite()) return

        // g_i = coeff * lastTrace_i  ( = d(upsilon_t)/d(w_i) )
        // Algorithm 1:
        //   q = 1 + g^T P g / tau
        //   k = P g / (q tau)
        //   w += k
        //   P  = P/tau - k k^T q
        //   P *= tau   (variance stabilisation)
        val tau = decayFactor
        val d = nRegressors

        var gtPg = 0.0
        var row = 0
        while (row < d) {
            var acc = 0f
            val base = row * d
            var col = 0
            while (col < d) {
                acc += precision[base + col] * (coeff * lastTrace[col])
                col++
            }
            pGradScratch[row] = acc
            gtPg += acc.toDouble() * (coeff * lastTrace[row]).toDouble()
            row++
        }

        val q = 1.0 + gtPg / tau
        if (!q.isFinite() || q <= 0.0) return

        val denom = (q * tau).toFloat()
        row = 0
        while (row < d) {
            var delta = pGradScratch[row] / denom
            if (!delta.isFinite()) delta = 0f
            kScratch[row] = delta
            row++
        }

        row = 0
        while (row < d) {
            val updated = weights[row] + kScratch[row]
            weights[row] = if (updated.isFinite()) updated else weights[row]
            row++
        }

        val qf = q.toFloat()
        row = 0
        while (row < d) {
            val base = row * d
            var col = 0
            while (col < d) {
                val updatedP = (precision[base + col] / tau - kScratch[row] * kScratch[col] * qf) * tau
                precision[base + col] = if (updatedP.isFinite()) updatedP else precision[base + col]
                col++
            }
            row++
        }
    }

    private fun buildRegressor(u: FloatArray, reservoirState: FloatArray) {
        System.arraycopy(u, 0, regressorScratch, 0, nInput)
        System.arraycopy(reservoirState, 0, regressorScratch, xBase, nHidden)
        var k = 0
        while (k < nBack) {
            regressorScratch[yBase + k] = pastPositions[k]
            k++
        }
        regressorScratch[biasIndex] = 1f
    }

    fun isStable(): Boolean {
        for (w in weights) if (!w.isFinite()) return false
        for (p in precision) if (!p.isFinite()) return false
        val f = lastPosition
        if (!f.isFinite() || f < -1f || f > 1f) return false
        return true
    }

    fun weightsSnapshot(): FloatArray = weights.copyOf()

    fun resetState() {
        pastPositions.fill(0f)
        traceHistory.fill(0f)
        lastTrace.fill(0f)
        lastPosition = 0f
    }
}
