package org.example.syncora.agent

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The fixed, randomly-initialized dynamic reservoir of an echo state
 * network, per Borrageiro, Firoozye & Barucca (2022) subsection III-B1,
 * eq. 5.
 *
 * ### Redesign note (aligning to the paper)
 * The previous version of this class omitted `W_back` entirely and left
 * the reservoir purely feedforward-in-time; own-output feedback was
 * instead wired only into the policy's linear regressor. That is a
 * different network from the one the paper describes. The paper is
 * explicit that what makes *this* echo state network recurrent - as
 * opposed to the plain Jaeger formulation it says it deviates from - is
 * exactly this feedback path: the agent's own past `n_back` positions
 * `ŷ_t` are wired back into the *reservoir's own state update* via a
 * third fixed weight matrix `W_back`, not just appended to the policy's
 * regressor after the fact:
 * ```
 * x_t = tanh(W_input·u_t + W_hidden·x_{t-1} + W_back·ŷ_t)
 * ```
 * `W_back` is drawn once, from a standard normal, and - like `W_input`
 * and `W_hidden` - never trained. Only the downstream policy's `w_out`
 * (see `RrlAgent`) is learned.
 *
 * ### Echo state property
 * Unchanged from before: `W_hidden` is built via the Yildiz et al.
 * procedure (draw non-negative, scale to a target spectral radius via
 * power iteration, sign-flip, sparsify) so `ρ(W_hidden) < 1` is guaranteed
 * by construction. `W_back` does not participate in that guarantee and
 * doesn't need to - Jaeger's echo state property is a statement about
 * `W_hidden` alone for a *given* (here: agent-supplied) input stream, and
 * `ŷ_t` is just another bounded (`[-1,1]`) input stream from the reservoir's
 * point of view, no different in kind from `u_t`.
 */
class ReservoirEngine(
    val weights: ReservoirWeights,
    initialState: FloatArray? = null,
) {
    val nInput: Int get() = weights.nInput
    val nHidden: Int get() = weights.nHidden
    val nBack: Int get() = weights.nBack

    private val state: FloatArray = if (initialState != null) {
        require(initialState.size == weights.nHidden) {
            "initialState size ${initialState.size} != nHidden ${weights.nHidden}"
        }
        initialState.copyOf()
    } else {
        FloatArray(weights.nHidden)
    }
    private val nextStateScratch = FloatArray(weights.nHidden)

    /**
     * Advances the reservoir by one bar-close step:
     * `x_t = tanh(W_input·u_t + W_hidden·x_{t-1} + W_back·ŷ_t)`.
     *
     * @param u [FeatureAssembler]'s output, [nInput]-shaped.
     * @param yHat the agent's own last [nBack] positions, `[f_{t-1}, ...,
     *   f_{t-nBack}]` - same array [RrlAgent] threads into its own
     *   regressor as `ŷ_t`, so the *same* feedback the policy sees is also
     *   what the reservoir sees, per eq. 5. Pass an `nBack`-length zero
     *   array before any position has been taken.
     */
    fun step(u: FloatArray, yHat: FloatArray): FloatArray {
        require(u.size == weights.nInput) {
            "input size ${u.size} != nInput ${weights.nInput}"
        }
        require(yHat.size == weights.nBack) {
            "yHat size ${yHat.size} != nBack ${weights.nBack}"
        }
        val wInput = weights.wInput
        val wHidden = weights.wHidden
        val wBack = weights.wBack
        val nIn = weights.nInput
        val nHid = weights.nHidden
        val nBk = weights.nBack

        var h = 0
        while (h < nHid) {
            var acc = 0f

            val inBase = h * nIn
            var i = 0
            while (i < nIn) {
                acc += wInput[inBase + i] * u[i]
                i++
            }

            val hidBase = h * nHid
            var j = 0
            while (j < nHid) {
                acc += wHidden[hidBase + j] * state[j]
                j++
            }

            val backBase = h * nBk
            var k = 0
            while (k < nBk) {
                acc += wBack[backBase + k] * yHat[k]
                k++
            }

            nextStateScratch[h] = tanh(acc.toDouble()).toFloat()
            h++
        }
        System.arraycopy(nextStateScratch, 0, state, 0, nHid)
        return state
    }

    /** Read-only view of the current hidden state `x_t`. Do not mutate. */
    fun currentState(): FloatArray = state

    fun resetState(newState: FloatArray? = null) {
        if (newState != null) {
            require(newState.size == weights.nHidden) {
                "newState size ${newState.size} != nHidden ${weights.nHidden}"
            }
            System.arraycopy(newState, 0, state, 0, weights.nHidden)
        } else {
            state.fill(0f)
        }
    }
}

/**
 * The three fixed weight matrices of a reservoir: `W_input` (n_hidden x
 * n_input), `W_hidden` (n_hidden x n_hidden, scaled to the target spectral
 * radius), and `W_back` (n_hidden x n_back, the own-output feedback path -
 * new in this redesign, see [ReservoirEngine]'s class doc). All three are
 * flat, row-major `FloatArray`s and none of them are ever trained.
 */
class ReservoirWeights private constructor(
    val nInput: Int,
    val nHidden: Int,
    val nBack: Int,
    val wInput: FloatArray,
    val wHidden: FloatArray,
    val wBack: FloatArray,
    val spectralRadiusAchieved: Float,
) {
    companion object {
        const val DEFAULT_N_HIDDEN = 100
        /** `n_back = 10` per the paper's own experiment design (subsection III-C), not the previous redesign's `5`. */
        const val DEFAULT_N_BACK = 10
        const val DEFAULT_SPECTRAL_RADIUS_TARGET = 0.9f
        const val DEFAULT_SIGN_FLIP_PROBABILITY = 0.5f
        const val DEFAULT_SPARSITY = 0.75f

        const val MIN_N_HIDDEN = 50
        const val MAX_N_HIDDEN = 150

        fun randomWeights(
            nInput: Int,
            nHidden: Int = DEFAULT_N_HIDDEN,
            nBack: Int = DEFAULT_N_BACK,
            spectralRadiusTarget: Float = DEFAULT_SPECTRAL_RADIUS_TARGET,
            signFlipProbability: Float = DEFAULT_SIGN_FLIP_PROBABILITY,
            sparsity: Float = DEFAULT_SPARSITY,
            seed: Long = 0L,
        ): ReservoirWeights {
            require(nInput >= 1) { "nInput must be >= 1, was $nInput" }
            require(nHidden in MIN_N_HIDDEN..MAX_N_HIDDEN) {
                "nHidden must be in [$MIN_N_HIDDEN, $MAX_N_HIDDEN], was $nHidden"
            }
            require(nBack >= 0) { "nBack must be >= 0, was $nBack" }
            require(spectralRadiusTarget > 0f && spectralRadiusTarget < 1f) {
                "spectralRadiusTarget must be in (0, 1), was $spectralRadiusTarget"
            }
            require(signFlipProbability in 0f..1f)
            require(sparsity in 0f..1f)

            val rng = Random(seed)
            val gaussians = GaussianSource(rng)

            val wInput = FloatArray(nHidden * nInput) { gaussians.next() }
            // W_back: drawn from a standard normal, same as W_input - the
            // paper places no non-negativity/scaling requirement on it,
            // only on W_hidden (echo state property is a W_hidden-only
            // condition).
            val wBack = FloatArray(nHidden * nBack) { gaussians.next() }

            val wHidden = FloatArray(nHidden * nHidden) { abs(gaussians.next()) }
            val rawRho = PowerIteration.estimateSpectralRadius(wHidden, nHidden, rng)
            if (rawRho > POWER_ITERATION_EPS) {
                val scale = spectralRadiusTarget / rawRho
                for (idx in wHidden.indices) wHidden[idx] *= scale
            }
            for (idx in wHidden.indices) {
                if (rng.nextFloat() < signFlipProbability) wHidden[idx] = -wHidden[idx]
            }
            for (idx in wHidden.indices) {
                if (rng.nextFloat() < sparsity) wHidden[idx] = 0f
            }

            val achievedRho = PowerIteration.estimateSpectralRadius(wHidden, nHidden, rng)

            return ReservoirWeights(nInput, nHidden, nBack, wInput, wHidden, wBack, achievedRho)
        }

        private const val POWER_ITERATION_EPS = 1e-9f
    }
}

private class GaussianSource(private val rng: Random) {
    private var spare: Float? = null

    fun next(): Float {
        spare?.let { spare = null; return it }
        var u1: Float
        do {
            u1 = rng.nextFloat()
        } while (u1 <= Float.MIN_VALUE)
        val u2 = rng.nextFloat()
        val radius = sqrt(-2.0 * ln(u1.toDouble()))
        val angle = 2.0 * Math.PI * u2
        val z0 = (radius * cos(angle)).toFloat()
        val z1 = (radius * kotlin.math.sin(angle)).toFloat()
        spare = z1
        return z0
    }
}

object PowerIteration {
    const val DEFAULT_ITERATIONS = 300
    private const val NORM_FLOOR = 1e-12f

    fun estimateSpectralRadius(
        matrix: FloatArray,
        n: Int,
        rng: Random,
        iterations: Int = DEFAULT_ITERATIONS,
    ): Float {
        require(matrix.size == n * n) { "matrix must be n x n flat, was ${matrix.size} for n=$n" }
        if (n == 0) return 0f

        var v = FloatArray(n) { rng.nextFloat() * 2f - 1f }
        normalizeInPlace(v)

        val scratch = FloatArray(n)
        var rho = 0f
        repeat(iterations) {
            matVec(matrix, n, v, scratch)
            val norm = l2Norm(scratch)
            if (norm < NORM_FLOOR) return 0f
            rho = norm
            var i = 0
            while (i < n) {
                v[i] = scratch[i] / norm
                i++
            }
        }
        return rho
    }

    private fun matVec(matrix: FloatArray, n: Int, v: FloatArray, out: FloatArray) {
        var row = 0
        while (row < n) {
            var acc = 0f
            val base = row * n
            var col = 0
            while (col < n) {
                acc += matrix[base + col] * v[col]
                col++
            }
            out[row] = acc
            row++
        }
    }

    private fun l2Norm(v: FloatArray): Float {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        return sqrt(sumSq)
    }

    private fun normalizeInPlace(v: FloatArray) {
        val norm = l2Norm(v)
        if (norm < NORM_FLOOR) {
            if (v.isNotEmpty()) v[0] = 1f
            return
        }
        for (i in v.indices) v[i] = v[i] / norm
    }
}
