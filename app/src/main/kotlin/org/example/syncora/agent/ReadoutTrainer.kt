package org.example.syncora.agent

/**
 * Trains the readout weights `W_out` of an echo state network online,
 * bar-close by bar-close, via recursive least squares (RLS) with a
 * forgetting factor (see the reference architecture diagram /
 * `docs/agent-design-contract.md` and Borrageiro, Firoozye & Barucca 2022,
 * subsection III-B1, which cites RLS as the standard way to solve the
 * readout analytically and sequentially).
 *
 * This is Phase 3: it sits directly on top of Phase 2's
 * [ReservoirEngine] - every [update]/[predict] call takes that engine's
 * hidden state `x_t` as input - and it is trained against a *supervised
 * proxy target* (next-bar return, or whatever scalar/low-dimensional
 * signal the caller passes as `target`), never against P&L or reward,
 * since [RewardEngine] and the utility layer that consumes it don't exist
 * until Phase 4. Nothing here is a trading decision; `W_out` is purely a
 * regression readout.
 *
 * ### The RLS update
 * Given the augmented regressor `x_t` (the reservoir state, plus a
 * trailing bias entry if [includeBias]) and observed target `y_t`:
 *
 * ```
 * Px_t   = P_{t-1} x_t
 * denom  = lambda + x_t^T Px_t
 * e_o    = y_{t,o} - w_o^T x_t                    (per output o)
 * w_o    += (e_o / denom) Px_t
 * P_t    = (P_{t-1} - Px_t Px_t^T / denom) / lambda
 * ```
 *
 * `lambda` = [forgettingFactor] in `(0, 1]` down-weights older observations
 * exponentially (`lambda = 1` is plain growing-window least squares); `P`
 * is initialized to `initialCovarianceScale * I`, the standard large-prior
 * RLS init that lets early updates move `W_out` quickly before the
 * covariance has had time to concentrate.
 *
 * ### Performance
 * Everything is a flat, row-major `FloatArray` - `W_out` is
 * `n_outputs x n_regressors`, `P` is `n_regressors x n_regressors` - no
 * boxed `Double`, no `Array<FloatArray>` object graph, consistent with
 * Phase 2's constraint. [update]'s two `O(n_regressors^2)` passes (the
 * `Px_t` matrix-vector product and the `P` rank-one downdate) are the only
 * per-step cost; the three scratch buffers below are the only allocations
 * this class ever performs, all at construction time.
 */
class ReadoutTrainer(
    val nHidden: Int,
    val nOutputs: Int = 1,
    val includeBias: Boolean = true,
    val forgettingFactor: Float = DEFAULT_FORGETTING_FACTOR,
    initialCovarianceScale: Float = DEFAULT_INITIAL_COVARIANCE_SCALE,
    initialWOut: FloatArray? = null,
    initialCovariance: FloatArray? = null,
) {
    companion object {
        const val DEFAULT_FORGETTING_FACTOR = 0.995f
        const val DEFAULT_INITIAL_COVARIANCE_SCALE = 100f

        // Guards the RLS denominator: mathematically denom = lambda +
        // x^T P x >= lambda > 0 whenever P is positive semi-definite,
        // which it is by construction (diagonal init, and the downdate
        // below preserves PSD-ness up to float rounding). This is a
        // numerical safety net against that rounding, not the expected
        // path - see [update].
        private const val DENOM_EPS = 1e-8f
    }

    /** Regressor width: the reservoir state (`nHidden`) plus one bias entry if [includeBias]. */
    val nRegressors: Int = nHidden + if (includeBias) 1 else 0

    init {
        require(nHidden >= 1) { "nHidden must be >= 1, was $nHidden" }
        require(nOutputs >= 1) { "nOutputs must be >= 1, was $nOutputs" }
        require(forgettingFactor > 0f && forgettingFactor <= 1f) {
            "forgettingFactor must be in (0, 1], was $forgettingFactor"
        }
        require(initialCovarianceScale > 0f) {
            "initialCovarianceScale must be > 0, was $initialCovarianceScale"
        }
    }

    // Hot-path state: the readout weights w_out (row-major, wOut[o *
    // nRegressors + i]) and the RLS covariance P (row-major, symmetric,
    // P[i * nRegressors + j]). These are the two arrays a checkpoint (see
    // ReadoutCheckpointStore.kt) captures and restores.
    private val wOut: FloatArray = if (initialWOut != null) {
        require(initialWOut.size == nOutputs * nRegressors) {
            "initialWOut size ${initialWOut.size} != nOutputs*nRegressors ${nOutputs * nRegressors}"
        }
        initialWOut.copyOf()
    } else {
        FloatArray(nOutputs * nRegressors)
    }

    private val covariance: FloatArray = if (initialCovariance != null) {
        require(initialCovariance.size == nRegressors * nRegressors) {
            "initialCovariance size ${initialCovariance.size} != nRegressors^2 ${nRegressors * nRegressors}"
        }
        initialCovariance.copyOf()
    } else {
        FloatArray(nRegressors * nRegressors).also { p ->
            var i = 0
            while (i < nRegressors) {
                p[i * nRegressors + i] = initialCovarianceScale
                i++
            }
        }
    }

    // Scratch buffers reused by every predict()/update() call - the only
    // allocations beyond the two arrays above, and those only happen once,
    // here in the constructor.
    private val regressorScratch = FloatArray(nRegressors)
    private val pxScratch = FloatArray(nRegressors)
    private val predictionScratch = FloatArray(nOutputs)

    /**
     * Builds the augmented regressor `x_t` from a reservoir [state]
     * ([ReservoirEngine.step] / [ReservoirEngine.currentState] output)
     * into [regressorScratch]: the state itself, plus a trailing `1.0`
     * bias entry when [includeBias].
     */
    private fun buildRegressor(state: FloatArray) {
        require(state.size == nHidden) { "state size ${state.size} != nHidden $nHidden" }
        System.arraycopy(state, 0, regressorScratch, 0, nHidden)
        if (includeBias) regressorScratch[nHidden] = 1f
    }

    /**
     * Predicts `ŷ_t = W_out . x_t` for the given reservoir [state], using
     * the readout weights as they stand *before* any [update] this step.
     * Call this first and compare against the realized target once it's
     * known, *then* call [update] with the same `state` - that ordering is
     * what makes a reported correlation/hit-rate a genuine one-step-ahead
     * forecast rather than a fit the readout has already seen.
     *
     * Returns the engine's own scratch buffer (same convention as
     * [ReservoirEngine.step]) - copy it if a stable snapshot is needed
     * across further [predict]/[update] calls.
     */
    fun predict(state: FloatArray): FloatArray {
        buildRegressor(state)
        var o = 0
        while (o < nOutputs) {
            predictionScratch[o] = dot(wOut, o * nRegressors, regressorScratch)
            o++
        }
        return predictionScratch
    }

    /**
     * One RLS update against the observed [target] for the reservoir
     * [state] that predicted it. See the class doc for the update
     * equations. `O(nRegressors^2)` per call; no allocation.
     */
    fun update(state: FloatArray, target: FloatArray) {
        require(target.size == nOutputs) { "target size ${target.size} != nOutputs $nOutputs" }
        buildRegressor(state)

        // Px = P * x
        var i = 0
        while (i < nRegressors) {
            pxScratch[i] = dot(covariance, i * nRegressors, regressorScratch)
            i++
        }

        var denom = forgettingFactor
        i = 0
        while (i < nRegressors) {
            denom += regressorScratch[i] * pxScratch[i]
            i++
        }
        if (!denom.isFinite() || denom < DENOM_EPS) return

        // Weight update, per output: w_o += (e_o / denom) * Px
        var o = 0
        while (o < nOutputs) {
            val base = o * nRegressors
            val prediction = dot(wOut, base, regressorScratch)
            val scale = (target[o] - prediction) / denom
            i = 0
            while (i < nRegressors) {
                wOut[base + i] += scale * pxScratch[i]
                i++
            }
            o++
        }

        // Covariance downdate: P = (P - Px Px^T / denom) / lambda
        val invLambda = 1f / forgettingFactor
        i = 0
        while (i < nRegressors) {
            val rowBase = i * nRegressors
            val pxI = pxScratch[i]
            var j = 0
            while (j < nRegressors) {
                covariance[rowBase + j] = (covariance[rowBase + j] - pxI * pxScratch[j] / denom) * invLambda
                j++
            }
            i++
        }
    }

    /** Dot product of `row` (a length-[nRegressors] slice of a flat matrix starting at [base]) with [regressorScratch]. */
    private fun dot(flatMatrix: FloatArray, base: Int, x: FloatArray): Float {
        var acc = 0f
        var k = 0
        while (k < nRegressors) {
            acc += flatMatrix[base + k] * x[k]
            k++
        }
        return acc
    }

    /** Snapshot (fresh copy) of the readout weights - see [ReadoutCheckpoint]. */
    fun wOutSnapshot(): FloatArray = wOut.copyOf()

    /** Snapshot (fresh copy) of the RLS covariance matrix - see [ReadoutCheckpoint]. */
    fun covarianceSnapshot(): FloatArray = covariance.copyOf()

    /**
     * True iff every entry of `W_out` and `P` is finite. A backtest replay
     * should assert this after every [update] - Phase 3's "stable and
     * non-divergent across a full backtest replay" exit criterion, checked
     * continuously rather than only at the end.
     */
    fun isStable(): Boolean {
        for (w in wOut) if (!w.isFinite()) return false
        for (p in covariance) if (!p.isFinite()) return false
        return true
    }
}
