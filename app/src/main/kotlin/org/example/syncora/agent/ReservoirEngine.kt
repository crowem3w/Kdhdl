package org.example.syncora.agent

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The fixed, randomly-initialized dynamic reservoir of an echo state
 * network (see the reference architecture diagram / `docs/agent-design-
 * contract.md` and Borrageiro, Firoozye & Barucca 2022, subsection III-B1).
 *
 * Originally (Phase 2) only [ReservoirWeights.wInput] / [ReservoirWeights.wHidden]
 * and the `tanh`-activated state update existed here:
 * `x_t = tanh(W_input * u_t + W_hidden * x_{t-1})`. This class now also
 * owns the paper's own-output feedback path, `W_back` (gap-closure #1):
 * ```
 * x_t = tanh(W_input * u_t + W_hidden * x_{t-1} + W_back * ŷ_t)
 * ŷ_t = [f_{t-nBack}, ..., f_{t-1}]
 * z_t = concat(u_t, x_t, ŷ_t)
 * ```
 * `f_{t-1}, ..., f_{t-nBack}` is the *policy's* own last `nBack` position
 * decisions, supplied by the caller as [step]'s `ownOutput` argument one
 * bar at a time (most recent first internally). `W_back`, like `W_input`
 * and `W_hidden`, is fixed and random - it is drawn once at construction
 * and never trained. There is still no readout (`w_out`, Phase 3) here,
 * and nothing in this class is trained - the reservoir weights are drawn
 * once at construction and never change afterwards. Only the state `x_t`
 * and the feedback history evolve.
 *
 * Callers that don't pass `nBack`/`ownOutput` get the original,
 * feedback-free behaviour exactly: [ReservoirWeights.randomWeights]
 * defaults `nBack` to `0`, which makes `W_back` a zero-width matrix and
 * [step]'s `ownOutput` argument a no-op - so every existing call site
 * (`step(u)`, no feedback wired in) is bit-for-bit unaffected by this
 * addition.
 *
 * ### Why this matters: the echo state property
 * Per Jaeger's Definition 2 (quoted in the source paper), if two copies of
 * the *same* network are started from different initial states `x_0`,
 * `x̃_0` and driven by the same input sequence, their state trajectories
 * must converge. This only holds if the hidden weight matrix `W_hidden` is
 * contractive, i.e. its spectral radius `ρ(W_hidden) < 1`. [ReservoirWeights.randomWeights]
 * follows the Yildiz et al. procedure the paper cites: draw a
 * *non-negative* random matrix, scale it (via power iteration, see
 * [PowerIteration]) so its spectral radius sits at the configured target,
 * *then* flip signs and sparsify. Because the spectral radius of a matrix
 * is always bounded by the spectral radius of its entrywise absolute
 * value (Perron-Frobenius), sign-flipping and zeroing entries afterward
 * can only ever shrink `ρ(W_hidden)` relative to the scaled non-negative
 * matrix - never grow it back above the target. So the echo state property
 * is guaranteed by construction, not just hoped for.
 *
 * ### Performance
 * Everything is a flat, row-major `FloatArray` - no boxed `Double`, no
 * `Array<FloatArray>` object graphs, no per-step allocation beyond what is
 * created once in the constructor. [step] mutates an internal scratch
 * buffer and copies it back into the live state; it returns a reference to
 * the engine's own state array (not a fresh copy) precisely so repeated
 * calls on the MT6765G's A55 cores don't feed the GC. Callers that need a
 * stable snapshot (e.g. for logging) should copy it themselves via
 * `currentState().copyOf()`.
 */
class ReservoirEngine(
    val weights: ReservoirWeights,
    initialState: FloatArray? = null,
) {
    val nInput: Int get() = weights.nInput
    val nHidden: Int get() = weights.nHidden
    val nBack: Int get() = weights.nBack

    // Hot-path state: x_{t-1} going in, x_t coming out of every step(). The
    // only Float arrays this engine ever mutates after construction.
    private val state: FloatArray = if (initialState != null) {
        require(initialState.size == weights.nHidden) {
            "initialState size ${initialState.size} != nHidden ${weights.nHidden}"
        }
        initialState.copyOf()
    } else {
        FloatArray(weights.nHidden)
    }
    private val nextStateScratch = FloatArray(weights.nHidden)

    // Own-output feedback history: ownOutputHistory[0] = f_{t-1},
    // ownOutputHistory[1] = f_{t-2}, ..., ownOutputHistory[nBack-1] =
    // f_{t-nBack}. Empty (size 0) when weights.nBack == 0, so every method
    // below is a no-op with respect to feedback in that case.
    private val ownOutputHistory = FloatArray(weights.nBack)

    /**
     * Advances the reservoir by one bar-close step:
     * `x_t = tanh(W_input * u_t + W_hidden * x_{t-1} + W_back * ŷ_t)`.
     *
     * [u] must be exactly [FeatureAssembler.FEATURE_WIDTH]-shaped, i.e.
     * [nInput]-shaped - this is [FeatureAssembler]'s output `u_t` unmodified.
     *
     * [ownOutput] is the policy's `f_{t-1}` - its position decision from the
     * *previous* bar-close step, not this one's (this one, `f_t`, doesn't
     * exist yet: it's computed downstream of `x_t`, from [augmentedState]).
     * Pass `0f` (the default) before the first policy decision exists, e.g.
     * the very first bar of a session. The feedback history is shifted
     * *before* this step's forward pass, so the `ŷ_t` this step's `x_t` is
     * computed from is exactly `[f_{t-nBack}, ..., f_{t-1}]` - it can never
     * contain `f_t` itself (no look-ahead). When [nBack] is `0` (the
     * default `ReservoirWeights` shape), `ownOutput` is ignored entirely
     * and this method's output is bit-for-bit identical to the
     * feedback-free version.
     *
     * The only allocation anywhere in this method is zero: the returned
     * array *is* the engine's internal state buffer.
     */
    fun step(u: FloatArray, ownOutput: Float = 0f): FloatArray {
        require(u.size == weights.nInput) {
            "input size ${u.size} != nInput ${weights.nInput}"
        }
        shiftFeedbackHistory(ownOutput)

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

            if (nBk > 0) {
                val backBase = h * nBk
                var k = 0
                while (k < nBk) {
                    acc += wBack[backBase + k] * ownOutputHistory[k]
                    k++
                }
            }

            nextStateScratch[h] = tanh(acc.toDouble()).toFloat()
            h++
        }
        System.arraycopy(nextStateScratch, 0, state, 0, nHid)
        return state
    }

    /**
     * Shifts the `[f_{t-1}, ..., f_{t-nBack}]` ring buffer by one and
     * inserts [ownOutput] at the front, *before* this step's forward pass
     * reads it - see [step]'s doc for why that ordering is what prevents
     * look-ahead. No-op when [ReservoirWeights.nBack] is `0`.
     */
    private fun shiftFeedbackHistory(ownOutput: Float) {
        val nBk = weights.nBack
        if (nBk == 0) return
        var p = nBk - 1
        while (p >= 1) {
            ownOutputHistory[p] = ownOutputHistory[p - 1]
            p--
        }
        ownOutputHistory[0] = ownOutput
    }

    /** Read-only view of the current hidden state `x_t`. Do not mutate. */
    fun currentState(): FloatArray = state

    /**
     * Read-only view of the own-output feedback history `ŷ_t =
     * [f_{t-1}, ..., f_{t-nBack}]` that fed the most recent [step] call's
     * `x_t` (empty when [nBack] is `0`). Do not mutate.
     */
    fun currentFeedbackHistory(): FloatArray = ownOutputHistory

    /**
     * The augmented state `z_t = concat(u_t, x_t, ŷ_t)` the paper's policy
     * consumes directly (§III-B1: "the augmented state `z_t` is passed
     * straight into the reinforcement learning agent"), assembled from
     * [u] (the same `u_t` most recently passed to [step]) plus this
     * engine's own current `x_t`/`ŷ_t`. A fresh allocation each call
     * (unlike [step]/[currentState], which reuse internal buffers) since
     * this is meant to be handed off to a downstream consumer, not
     * re-read every bar the way the hot-path state is.
     */
    fun augmentedState(u: FloatArray): FloatArray {
        require(u.size == weights.nInput) {
            "input size ${u.size} != nInput ${weights.nInput}"
        }
        val nIn = weights.nInput
        val nHid = weights.nHidden
        val nBk = weights.nBack
        val out = FloatArray(nIn + nHid + nBk)
        System.arraycopy(u, 0, out, 0, nIn)
        System.arraycopy(state, 0, out, nIn, nHid)
        if (nBk > 0) System.arraycopy(ownOutputHistory, 0, out, nIn + nHid, nBk)
        return out
    }

    /**
     * Resets the live state to [newState] (or the zero vector if `null`),
     * and clears the own-output feedback history alongside it - both are
     * recurrent *state*, not weights, so both reset together the same way
     * [PolicyEngine.resetState] resets its own recurrent state without
     * touching its trained weights. Weights (`W_input`/`W_hidden`/`W_back`)
     * are untouched - only ever the state resets, e.g. when
     * `AgentOrchestrator` (Phase 6) restarts a session.
     */
    fun resetState(newState: FloatArray? = null) {
        if (newState != null) {
            require(newState.size == weights.nHidden) {
                "newState size ${newState.size} != nHidden ${weights.nHidden}"
            }
            System.arraycopy(newState, 0, state, 0, weights.nHidden)
        } else {
            state.fill(0f)
        }
        ownOutputHistory.fill(0f)
    }
}

/**
 * The fixed weight matrices of a reservoir: `W_input` (n_hidden x
 * n_input, drawn from a standard normal), `W_hidden` (n_hidden x
 * n_hidden, built per the Yildiz et al. procedure so `ρ(W_hidden)` sits at
 * the configured spectral radius target), and `W_back` (n_hidden x
 * n_back, drawn from a standard normal, unscaled - the paper's own-output
 * feedback path, gap-closure #1). All three are flat, row-major
 * `FloatArray`s: `wInput[h * nInput + i]` is the weight from input `i` to
 * hidden unit `h`; `wHidden[h * nHidden + j]` is the weight from hidden
 * unit `j` to hidden unit `h`; `wBack[h * nBack + k]` is the weight from
 * feedback slot `k` (`f_{t-1-k}`) to hidden unit `h`.
 *
 * `W_back` does *not* participate in the spectral-radius scaling step -
 * only `W_hidden` needs the echo state contraction property (`ρ(W_hidden)
 * < 1`); `W_back` is a plain external input channel to the reservoir, same
 * as `W_input`, and the echo state property (Definition 2, quoted in
 * [ReservoirEngine]'s class doc) holds regardless of what drives that
 * channel, as long as it's bounded - which `ŷ_t ∈ [-1, 1]^nBack` (the
 * policy's own `tanh`-bounded positions) always is.
 *
 * Split out from [ReservoirEngine] so the *same* weights can back two
 * engines with two different initial states - exactly what the echo state
 * property test needs (Definition 2 requires the *same* network, only the
 * starting state differs).
 */
class ReservoirWeights private constructor(
    val nInput: Int,
    val nHidden: Int,
    val nBack: Int,
    val wInput: FloatArray,
    val wHidden: FloatArray,
    val wBack: FloatArray,
    /** `ρ(W_hidden)` as actually estimated on the final (signed, sparsified) matrix. */
    val spectralRadiusAchieved: Float,
) {
    companion object {
        const val DEFAULT_N_HIDDEN = 100
        const val DEFAULT_SPECTRAL_RADIUS_TARGET = 0.9f
        const val DEFAULT_SIGN_FLIP_PROBABILITY = 0.5f

        /** Fraction of `W_hidden` entries zeroed; `α = 0.75` per the source paper's own configuration. */
        const val DEFAULT_SPARSITY = 0.75f

        const val MIN_N_HIDDEN = 50
        const val MAX_N_HIDDEN = 150

        /**
         * No own-output feedback by default - existing callers that don't
         * pass `nBack` get a zero-width `W_back` (i.e. [ReservoirEngine.step]'s
         * `ownOutput` argument becomes a no-op), so this addition is
         * opt-in and every pre-existing call site keeps its original,
         * bit-for-bit behaviour.
         */
        const val DEFAULT_N_BACK = 0

        /**
         * Draws a fresh, fixed reservoir weight triple. Follows the Yildiz et
         * al. procedure cited by the source paper, in order:
         *
         * 1. Draw `W_hidden` with every entry non-negative (`|N(0,1)|`).
         * 2. Scale it via [PowerIteration.estimateSpectralRadius] so
         *    `ρ(W_hidden) == spectralRadiusTarget`.
         * 3. Flip the sign of each entry independently with probability
         *    [signFlipProbability].
         * 4. Sparsify: zero each entry independently with probability
         *    [sparsity].
         *
         * Steps 3-4 can only shrink `ρ(W_hidden)` further (see the
         * Perron-Frobenius argument in [ReservoirEngine]'s class doc), so
         * the echo state property holds for the returned weights
         * regardless of which entries got flipped or zeroed.
         *
         * `W_input` is drawn independently, entries from a plain standard
         * normal (no non-negativity or scaling step - only `W_hidden` needs
         * to be contractive). `W_back` is drawn the same way as `W_input` -
         * plain standard normal, fixed, unscaled - and is drawn *last*
         * (after `W_input`/`W_hidden`/both spectral-radius estimates), so
         * it never perturbs the sequence of random draws those consume:
         * for a given seed, `wInput`/`wHidden`/`spectralRadiusAchieved` are
         * bit-identical whether or not [nBack] is `0`.
         */
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
                "nHidden must be in [$MIN_N_HIDDEN, $MAX_N_HIDDEN] per Phase 2's design, was $nHidden"
            }
            require(nBack >= 0) { "nBack must be >= 0, was $nBack" }
            require(spectralRadiusTarget > 0f && spectralRadiusTarget < 1f) {
                "spectralRadiusTarget must be in (0, 1) to guarantee the echo state property, was $spectralRadiusTarget"
            }
            require(signFlipProbability in 0f..1f)
            require(sparsity in 0f..1f)

            val rng = Random(seed)
            val gaussians = GaussianSource(rng)

            val wInput = FloatArray(nHidden * nInput) { gaussians.next() }

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

            // Drawn last, and only as many entries as nBack actually needs
            // (zero when nBack == 0) - see the kdoc above for why this
            // ordering keeps wInput/wHidden/achievedRho seed-stable.
            val wBack = FloatArray(nHidden * nBack) { gaussians.next() }

            return ReservoirWeights(nInput, nHidden, nBack, wInput, wHidden, wBack, achievedRho)
        }

        private const val POWER_ITERATION_EPS = 1e-9f
    }
}

/**
 * Box-Muller standard normal sampler built on [kotlin.random.Random] - the
 * paper only requires "a draw from a standard normal would suffice" for
 * `W_input` and the pre-sign-flip magnitudes of `W_hidden`; no external
 * distribution/statistics library is needed for that.
 */
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

/**
 * Power iteration for estimating a square matrix's spectral radius
 * `ρ(M) = max(|eigenvalue|)`, written from scratch since at `n_hidden` in
 * `[50, 150]` a full eigendecomposition needs no external linear-algebra
 * dependency to avoid - a plain iterative estimate is both sufficient and
 * simpler.
 *
 * Standard power iteration (repeatedly apply `M`, renormalize) only
 * converges *directionally* when the dominant eigenvalue is real and
 * simple. For a matrix like the sign-flipped `W_hidden`, the dominant
 * eigenvalue can be a complex-conjugate pair, in which case the iterate's
 * *direction* rotates and never settles - but its *norm growth rate*
 * still converges to `ρ(M)` regardless (the norm of `M^k v` grows like
 * `ρ(M)^k` for almost any starting `v`, independent of whether the
 * dominant eigenvalue is real). So this estimates `ρ(M)` via the norm
 * ratio `||M v_k|| / ||v_k||` on a unit-renormalized iterate, which is
 * robust to that rotation.
 */
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
