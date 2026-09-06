package org.example.syncora.rrl

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random




















class EchoStateReservoir(
    val nInput: Int,
    val nHidden: Int,
    val nBack: Int,
    sparsity: Double = 0.75,
    spectralRadius: Double = 0.9,
    signFlipProbability: Double = 0.5,
    seed: Long = 42L,
) {
    
    val augmentedSize: Int = nInput + nHidden + nBack

    private val random = Random(seed)

    
    private val wInput: Array<DoubleArray> =
        Array(nHidden) { DoubleArray(nInput) { nextGaussian(random) } }

    
    private val wHidden: Array<DoubleArray> =
        buildHiddenWeights(nHidden, sparsity, spectralRadius, signFlipProbability, random)

    
    private val wBack: Array<DoubleArray> =
        Array(nHidden) { DoubleArray(nBack) { nextGaussian(random) } }

    
    private var state: DoubleArray = DoubleArray(nHidden)

    
    fun reset() {
        state = DoubleArray(nHidden)
    }

    






    fun snapshotState(): DoubleArray = state.copyOf()

    





    fun restoreState(savedState: DoubleArray) {
        require(savedState.size == nHidden) {
            "expected reservoir state of size $nHidden, got ${savedState.size}"
        }
        state = savedState.copyOf()
    }

    






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

        
        private fun nextGaussian(random: Random): Double {
            val u1 = random.nextDouble().coerceAtLeast(1e-12)
            val u2 = random.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        }

        







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