package org.example.syncora.rrl

/**
 * Minimal dense linear-algebra helpers used by [EchoStateReservoir] and
 * [RrlWeightOptimizer]. The dimensions involved (a few hundred at most: the
 * paper uses nHidden = 100, nBack = 10) do not warrant an external
 * linear-algebra dependency.
 */
internal object Matrix {

    fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** Matrix-vector product: (rows x cols) * (cols) -> (rows) */
    fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val rows = matrix.size
        val out = DoubleArray(rows)
        for (i in 0 until rows) {
            val row = matrix[i]
            var sum = 0.0
            for (j in row.indices) sum += row[j] * vector[j]
            out[i] = sum
        }
        return out
    }

    /** Quadratic form v^T * M * v. */
    fun quadraticForm(vector: DoubleArray, matrix: Array<DoubleArray>): Double =
        dot(vector, multiply(matrix, vector))

    fun scale(vector: DoubleArray, factor: Double): DoubleArray =
        DoubleArray(vector.size) { vector[it] * factor }

    fun add(a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(a.size) { a[it] + b[it] }

    fun subtract(a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(a.size) { a[it] - b[it] }

    fun concat(vararg parts: DoubleArray): DoubleArray {
        val total = parts.sumOf { it.size }
        val out = DoubleArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    fun tanh(vector: DoubleArray): DoubleArray = DoubleArray(vector.size) { kotlin.math.tanh(vector[it]) }

    fun identity(n: Int, diagonalValue: Double = 1.0): Array<DoubleArray> =
        Array(n) { i -> DoubleArray(n) { j -> if (i == j) diagonalValue else 0.0 } }
}
