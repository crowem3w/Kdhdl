package org.example.syncora.bitget

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Echo state network reservoir, per
 * `recurrent-reinforcement-learning-crypto-agent.md` (Borrageiro, Firoozye,
 * Barucca - IEEE Access, 2022) §3.2.1 / Definition 2 / Appendix A.
 *
 * This is the *source model* half of the paper's transfer-learning
 * pipeline: a large, fixed, randomly-initialised recurrent reservoir whose
 * only job is to turn [RLFeatureSample.uT] (u_t) and the agent's own
 * lagged positions [RLFeatureSample.yHat] (ŷ_t) into a nonlinear feature
 * space x_t, which is then handed off (as the augmented state z_t, Eq. 5)
 * to whatever direct-RL readout trains w_out via the EKF update
 * (Algorithm 1). That readout - and the training loop that owns w_out and
 * differentiates the quadratic utility υ_t (Eq. 6) - is a distinct
 * model-training concern that consumes this class's output; it is not
 * implemented here, matching the boundary [RLFeatureVectorPipeline]
 * already draws around itself. See [DirectRLReadout] for that readout.
 *
 * W^input, W^hidden, and W^back are built once at construction and never
 * change afterwards - per §2.2, keeping them fixed is exactly what
 * guarantees the echo state property (Definition 2) holds for any input,
 * provided rho(W^hidden) < 1 (the sufficient condition stated there).
 */
class EchoStateReservoir(
    /** n_input - must match [RLFeatureVectorPipeline.FEATURE_COUNT] (u_t's dimensionality). */
    val nInput: Int = RLFeatureVectorPipeline.FEATURE_COUNT,
    /** n_hidden - paper §3.3's experiment uses 100. */
    val nHidden: Int = 100,
    /** n_back - paper §3.3's experiment uses 10; must match the pipeline's own `nBack`. */
    val nBack: Int = 10,
    /** alpha - reservoir sparsification probability, §3.2.1 step 4. Paper §3.3 uses 0.75. */
    private val sparsity: Double = 0.75,
    /**
     * Target rho(W^hidden) after step-2 rescaling. Must be strictly < 1 to
     * satisfy the echo-state sufficient condition (§2.2); left a shade
     * below 1 (rather than exactly 1) so floating-point error in the power
     * -iteration spectral-radius estimate can't accidentally push the
     * realised radius over the line.
     */
    private val spectralRadiusTarget: Double = 0.9,
    /** Fraction of W^hidden's (pre-sparsification) entries sign-flipped in step 3. */
    private val negativeFraction: Double = 0.5,
    seed: Long? = null,
) {
    /** Dimensionality of z_t = [u_t, x_t, ŷ_t] (Eq. 5) - what a direct-RL readout's w_out is sized against. */
    val augmentedSize: Int = nInput + nHidden + nBack

    private val random = if (seed != null) Random(seed) else Random.Default

    // Fixed weight matrices (§3.2.1) - built once below, mutated nowhere
    // else in this class. Package-private `val`s (not `private`) so a
    // readout implementation or a test can inspect them (e.g. to verify
    // rho(W^hidden) < 1) without this class needing to expose that check
    // itself.
    val wInput: Array<DoubleArray> = buildInputWeights()               // [nHidden][nInput], W^input
    val wHidden: Array<DoubleArray> = buildHiddenWeights()             // [nHidden][nHidden], W^hidden
    val wBack: Array<DoubleArray> = buildBackWeights()                 // [nHidden][nBack], W^back

    /** rho(W^hidden) as estimated at construction time - purely informational (e.g. logging/diagnostics). */
    val spectralRadius: Double = estimateSpectralRadius(wHidden)

    // x_0 = 0 (§3.2.1).
    private var state: DoubleArray = DoubleArray(nHidden)

    /** Read-only snapshot of x_t. Defensive copy so callers can't mutate reservoir state out from under [step]. */
    val currentState: DoubleArray
        get() = state.copyOf()

    /** Resets x_t back to x_0 = 0 - e.g. when starting a fresh online-learning run or switching instruments. */
    fun reset() {
        state = DoubleArray(nHidden)
    }

    /**
     * One reservoir tick:
     *
     * x_t = tanh(W^input u_t + W^hidden x_{t-1} + W^back ŷ_t)
     *
     * (§3.2.1's recurrent internal state equation, f_hidden = tanh), then
     * returns the augmented state z_t = [u_t, x_t, ŷ_t] (Eq. 5) that a
     * direct-RL readout consumes. Advances and retains x_t as this
     * instance's new state, so callers must invoke this once per time
     * step, in order - it is not safe to call out of sequence or
     * concurrently from multiple threads.
     */
    fun step(uT: FloatArray, yHat: FloatArray): DoubleArray {
        require(uT.size == nInput) { "uT has size ${uT.size}, expected nInput=$nInput" }
        require(yHat.size == nBack) { "yHat has size ${yHat.size}, expected nBack=$nBack" }

        val nextState = DoubleArray(nHidden)
        for (i in 0 until nHidden) {
            var acc = 0.0

            val wi = wInput[i]
            for (j in 0 until nInput) acc += wi[j] * uT[j]

            val wh = wHidden[i]
            for (j in 0 until nHidden) acc += wh[j] * state[j]

            val wb = wBack[i]
            for (j in 0 until nBack) acc += wb[j] * yHat[j]

            nextState[i] = tanh(acc)
        }
        state = nextState

        return augmentedState(uT, nextState, yHat)
    }

    /** Convenience overload driven directly off a pipeline sample, so callers don't have to unpack [RLFeatureSample] fields themselves. */
    fun step(sample: RLFeatureSample): DoubleArray =
        step(sample.uT.toFloatArray(), sample.yHat.toFloatArray())

    private fun augmentedState(uT: FloatArray, x: DoubleArray, yHat: FloatArray): DoubleArray {
        val z = DoubleArray(augmentedSize)
        var idx = 0
        for (v in uT) z[idx++] = v.toDouble()
        for (v in x) z[idx++] = v
        for (v in yHat) z[idx++] = v.toDouble()
        return z
    }

    // ---- Fixed weight initialisation (Yildiz et al. procedure, §3.2.1) ----

    /** W^input: random standard-normal input weights, per §3.2.1's initialisation list. */
    private fun buildInputWeights(): Array<DoubleArray> =
        Array(nHidden) { DoubleArray(nInput) { standardNormal() } }

    /** W^back: random standard-normal feedback weights, per §3.2.1's initialisation list. */
    private fun buildBackWeights(): Array<DoubleArray> =
        Array(nHidden) { DoubleArray(nBack) { standardNormal() } }

    /**
     * W^hidden, built exactly per §3.2.1's four-step Yildiz et al.
     * procedure:
     *  1. Initialise a random matrix with all non-negative entries.
     *  2. Scale so that rho(W^hidden) < 1.
     *  3. Flip the sign of a chosen fraction of entries, to introduce
     *     negative weights.
     *  4. Sparsify with probability alpha, zeroing selected entries.
     *
     * Steps 3 and 4 are each independent per-entry Bernoulli draws (the
     * paper doesn't specify a particular selection rule beyond "a chosen
     * number of entries" / "with probability alpha"), and step 2's scaling
     * is done last here so the final, sign-flipped-and-sparsified matrix
     * is the one actually measured and rescaled - rescaling the dense
     * pre-sparsification matrix could leave the sparser final matrix's
     * true spectral radius adrift of the target.
     */
    private fun buildHiddenWeights(): Array<DoubleArray> {
        // Step 1: non-negative random entries, uniform on [0, 1).
        val w = Array(nHidden) { DoubleArray(nHidden) { random.nextDouble() } }

        // Step 3: sign flips.
        for (i in 0 until nHidden) {
            for (j in 0 until nHidden) {
                if (random.nextDouble() < negativeFraction) w[i][j] = -w[i][j]
            }
        }

        // Step 4: sparsification.
        for (i in 0 until nHidden) {
            for (j in 0 until nHidden) {
                if (random.nextDouble() < sparsity) w[i][j] = 0.0
            }
        }

        // Step 2: rescale so rho(W^hidden) < 1 (echo-state sufficient condition, §2.2).
        val rho = estimateSpectralRadius(w)
        if (rho > 1e-12) {
            val scale = spectralRadiusTarget / rho
            for (i in 0 until nHidden) {
                for (j in 0 until nHidden) w[i][j] *= scale
            }
        }
        return w
    }

    /** Box-Muller standard-normal draw, used for W^input / W^back per §3.2.1 ("random (standard normal)"). */
    private fun standardNormal(): Double {
        var u1 = random.nextDouble()
        if (u1 <= 1e-12) u1 = 1e-12 // avoid ln(0)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * Power-iteration estimate of |dominant eigenvalue| for a real square
     * matrix, used only to size the step-2 rescaling above (and exposed
     * read-only as [spectralRadius] for diagnostics). n_hidden is small
     * (paper default 100) so this is cheap even with a generous iteration
     * count. For a random sparsified sign-mixed matrix the dominant
     * eigenvalue can in principle be a complex-conjugate pair rather than
     * a single real value, in which case plain power iteration doesn't
     * converge to a fixed vector - but the norm sequence it produces still
     * settles into a stable estimate of the dominant eigenvalue's
     * magnitude, which is all rho(W^hidden) requires here.
     */
    private fun estimateSpectralRadius(w: Array<DoubleArray>, iterations: Int = 300): Double {
        val n = w.size
        if (n == 0) return 0.0

        var v = DoubleArray(n) { random.nextDouble() - 0.5 }
        var norm = l2Norm(v)
        if (norm < 1e-12) return 0.0
        for (k in 0 until n) v[k] /= norm

        var estimate = 0.0
        repeat(iterations) {
            val wv = DoubleArray(n)
            for (i in 0 until n) {
                var acc = 0.0
                val row = w[i]
                for (j in 0 until n) acc += row[j] * v[j]
                wv[i] = acc
            }
            norm = l2Norm(wv)
            if (norm < 1e-12) return 0.0
            for (k in 0 until n) v[k] = wv[k] / norm
            estimate = norm
        }
        return estimate
    }

    private fun l2Norm(v: DoubleArray): Double {
        var sumSq = 0.0
        for (x in v) sumSq += x * x
        return sqrt(sumSq)
    }
}
