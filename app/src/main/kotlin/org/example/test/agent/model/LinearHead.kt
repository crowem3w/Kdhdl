package org.example.test.agent.model

import kotlin.random.Random

/**
 * A plain `y = W·h + b` layer, reused by both pretraining auxiliary heads in
 * `org.example.test.agent.pretrain` (regime classification, next-frame
 * reconstruction). Neither head is part of the deployed encoder - they only
 * exist to give the GRU's hidden state ([CompactLatentStateEncoder]) a
 * training signal, so they're deliberately factored out of the `model`
 * package's inference path and kept as thin, disposable wrappers here.
 */
class LinearHead(val inputDim: Int, val outputDim: Int, seed: Long) {
    val w = GRUCell.Matrix(outputDim, inputDim)
    val b = DoubleArray(outputDim)
    val dW = GRUCell.Matrix(outputDim, inputDim)
    val db = DoubleArray(outputDim)

    init {
        w.fillGlorot(Random(seed))
    }

    fun forward(x: DoubleArray): DoubleArray = DoubleArray(outputDim) { r -> w.dot(r, x) + b[r] }

    /** Given dL/dy, accumulates dW/db and returns dL/dx to chain further back (into the encoder's hidden state). */
    fun backward(x: DoubleArray, dy: DoubleArray): DoubleArray {
        val dx = DoubleArray(inputDim)
        for (r in 0 until outputDim) {
            db[r] += dy[r]
            for (c in 0 until inputDim) {
                dW.add(r, c, dy[r] * x[c])
                dx[c] += w.get(r, c) * dy[r]
            }
        }
        return dx
    }

    fun zeroGrad() {
        dW.zero()
        db.fill(0.0)
    }

    fun parameters(): List<GRUCell.ParamRef> = listOf(w.asParamRef(dW), GRUCell.ParamRef(b, db))
}
