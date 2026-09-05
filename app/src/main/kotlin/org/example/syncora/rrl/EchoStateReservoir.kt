package org.example.syncora.rrl

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Source model of Borrageiro, Firoozye & Barucca, "The Recurrent
 * Reinforcement Learning Crypto Agent" (section III-B1): a fixed, dynamic
 * reservoir feature space whose learning is transferred into the direct
 * recurrent reinforcement learner ([RecurrentReinforcementLearner]).
 *
 * The reservoir maintains an internal state
 *   x_t = tanh(W^input u_t + W^hidden x_{t-1} + W^back yhat_t)
 * and exposes the augmented state
 *   z_t = [u_t, x_t, yhat_t]                                   (eq. 5)
 * where yhat_t are the agent's own past desired positions fed back in
 * (eq. 11), which is how this implementation deviates from the traditional
 * echo state network / reinforcement-learning combination (section III-B,
 * paragraph 1): the model's own trading decisions become part of its next
 * input rather than a value-function estimate.
 *
 * W^input, W^hidden and W^back are all fixed after construction; only the
 * downstream agent's readout weights are learned.
 */
class EchoStateReservoir(
    val nInput: Int,
    val nHidden: Int,
    val nBack: Int,
    sparsity: Double = 0.75,
    spectralRadius: Double = 0.9,
    signFlipProbability: Double = 0.5,
    seed: Long = 42L,
) {
    /** Size of the augmented state z_t = [u_t, x_t, yhat_t]. */
    val augmentedSize: Int = nInput + nHidden + nBack

    private val random = Random(seed)

    /** W^input in R^(nHidden x nInput): fixed, standard-normal weights. */
    private val wInput: Array<DoubleArray> =
        Array(nHidden) { DoubleArray(nInput) { nextGaussian(random) } }

    /** W^hidden in R^(nHidden x nHidden): fixed, contractive, sparse, signed reservoir weights. */
    private val wHidden: Array<DoubleArray> =
        buildHiddenWeights(nHidden, sparsity, spectralRadius, signFlipProbability, random)

    /** W^back in R^(nHidden x nBack): fixed, standard-normal feedback weights. */
    private val wBack: Array<DoubleArray> =
        Array(nHidden) { DoubleArray(nBack) { nextGaussian(random) } }

    /** x_t, the internal dynamical state, initialised to the zero vector x_0. */
    private var state: DoubleArray = DoubleArray(nHidden)

    /** Resets the internal reservoir state x_0 = 0 (a fresh input history). */
    fun reset() {
        state = DoubleArray(nHidden)
    }

    /**
     * Advances the reservoir one step and returns the augmented state
     * z_t = [u_t, x_t, yhat_t] (eq. 5).
     *
     * @param input u_t: external market features (order book, transaction and funding information).
     * @param pastPositions yhat_t = [f_{t-nBack}, ..., f_{t-1}] (eq. 11): the agent's past desired positions.
     */
    fun step(input: DoubleArray, pastPositions: DoubleArray): DoubleArray {
        require(input.size == nInput) { "expected $nInput external inputs, got ${input.size}" }
        require(pastPositions.size == nBack) { "expected $nBack back-connections, got ${pastPositions.size}" }

        val fromInput = Matrix.multiply(wInput, input)
        val fromHidden = Matrix.multiply(wHidden, state)
        val fromBack = Matrix.multiply(wBack, pastPositions)
        val preActivation = Matrix.add(Matrix.add(fromInput, fromHidden), fromBack)
        state = Matrix.tanh(preActivation)

        return Matrix.concat(input, state, pastPositions)
    }

    companion object {

        /** Box-Muller transform; kotlin.random.Random has no built-in Gaussian sampler. */
        private fun nextGaussian(random: Random): Double {
            val u1 = random.nextDouble().coerceAtLeast(1e-12)
            val u2 = random.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        }

        /**
         * Implements the procedure of Yildiz et al. [38] referenced in section
         * III-B1, which guarantees the echo state property for any input:
         *  1. initialise a random matrix with non-negative entries;
         *  2. scale it so its spectral radius (largest absolute eigenvalue) is < 1;
         *  3. flip the sign of a desired fraction of entries;
         *  4. sparsify with probability `sparsity`, zeroing those entries.
         */
        private fun buildHiddenWeights(
            n: Int,
            sparsity: Double,
            targetSpectralRadius: Double,
            signFlipProbability: Double,
            random: Random,
        ): Array<DoubleArray> {
            val w = Array(n) { DoubleArray(n) { random.nextDouble() } }

            val currentRadius = estimateSpectralRadius(w)
            if (currentRadius > 1e-9) {
                val factor = targetSpectralRadius / currentRadius
                for (i in 0 until n) for (j in 0 until n) w[i][j] *= factor
            }

            for (i in 0 until n) for (j in 0 until n) {
                if (random.nextDouble() < signFlipProbability) w[i][j] = -w[i][j]
            }

            for (i in 0 until n) for (j in 0 until n) {
                if (random.nextDouble() < sparsity) w[i][j] = 0.0
            }

            return w
        }

        /** Power-iteration estimate of the dominant |eigenvalue| of a real square matrix. */
        private fun estimateSpectralRadius(matrix: Array<DoubleArray>, iterations: Int = 200): Double {
            val n = matrix.size
            var v = DoubleArray(n) { 1.0 / sqrt(n.toDouble()) }
            var eigenvalue = 0.0
            repeat(iterations) {
                val next = Matrix.multiply(matrix, v)
                val norm = sqrt(Matrix.dot(next, next))
                if (norm < 1e-12) return@repeat
                for (i in next.indices) next[i] = next[i] / norm
                eigenvalue = Matrix.dot(next, Matrix.multiply(matrix, next))
                v = next
            }
            return abs(eigenvalue)
        }
    }
}
