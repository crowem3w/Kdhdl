package org.example.syncora.rrl



















class RrlWeightOptimizer(
    dimension: Int,
    private val ridgePenalty: Double = 1.0,
    private val decayFactor: Double = 0.999,
) {
    private val dimension: Int = dimension

    var weights: DoubleArray = DoubleArray(dimension)
        private set

    
    var lastUpdateNorm: Double = 0.0
        private set

    
    private var precision: Array<DoubleArray> = Matrix.identity(dimension, 1.0 / ridgePenalty)

    
    fun update(utilityGradient: DoubleArray): DoubleArray {
        require(utilityGradient.size == dimension) {
            "expected gradient of size $dimension, got ${utilityGradient.size}"
        }

        val pPrev = precision
        val gradient = utilityGradient

        
        val q = 1.0 + Matrix.quadraticForm(gradient, pPrev) / decayFactor

        
        val pGrad = Matrix.multiply(pPrev, gradient)
        val k = Matrix.scale(pGrad, 1.0 / (q * decayFactor))

        
        weights = Matrix.add(weights, k)
        lastUpdateNorm = Matrix.norm(k)

        
        val n = dimension
        val updated = Array(n) { i -> DoubleArray(n) { j -> pPrev[i][j] / decayFactor - k[i] * k[j] * q } }
        for (i in 0 until n) for (j in 0 until n) updated[i][j] *= decayFactor
        precision = updated

        return weights
    }

    fun reset() {
        weights = DoubleArray(dimension)
        lastUpdateNorm = 0.0
        precision = Matrix.identity(dimension, 1.0 / ridgePenalty)
    }

    
    fun snapshotPrecision(): Array<DoubleArray> = Array(dimension) { precision[it].copyOf() }

    



    fun restoreState(savedWeights: DoubleArray, savedPrecision: Array<DoubleArray>) {
        require(savedWeights.size == dimension) {
            "expected weights of size $dimension, got ${savedWeights.size}"
        }
        require(savedPrecision.size == dimension && savedPrecision.all { it.size == dimension }) {
            "expected a ${dimension}x$dimension precision matrix"
        }
        weights = savedWeights.copyOf()
        precision = Array(dimension) { savedPrecision[it].copyOf() }
    }
}