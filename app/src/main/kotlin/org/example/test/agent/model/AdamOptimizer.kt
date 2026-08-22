package org.example.test.agent.model

import kotlin.math.sqrt

/**
 * Adam ([Kingma & Ba, 2014]), generic over any set of (parameter, gradient)
 * array pairs - it doesn't know or care whether they came from a
 * [GRUCell] or a linear head, it just walks whatever [GRUCell.ParamRef]s
 * it's handed. Used for [org.example.test.agent.pretrain.EncoderPretrainer]'s
 * server-side training loop; the on-device live path never touches this,
 * it only ever loads already-fit weights via [EncoderCheckpoint].
 *
 * Includes global gradient-norm clipping ([clipNorm]): the synthetic
 * curriculum deliberately forces rare, extreme regimes (design doc §4.2,
 * [org.example.test.agent.sim.MarketRegime.CRISIS]), and a jump-diffusion
 * bar landing in the loss right before a backward pass can otherwise produce
 * a gradient spike large enough to destabilize training for many steps
 * afterward.
 */
class AdamOptimizer(
    private val params: List<GRUCell.ParamRef>,
    private val learningRate: Double = 1e-3,
    private val beta1: Double = 0.9,
    private val beta2: Double = 0.999,
    private val eps: Double = 1e-8,
    private val clipNorm: Double = 5.0,
) {
    private val m: List<DoubleArray> = params.map { DoubleArray(it.values.size) }
    private val v: List<DoubleArray> = params.map { DoubleArray(it.values.size) }
    private var t: Int = 0

    /** Scales every gradient array in place so their combined L2 norm is at most [clipNorm]. */
    private fun clipGradients() {
        var sumSq = 0.0
        for (p in params) for (g in p.grads) sumSq += g * g
        val norm = sqrt(sumSq)
        if (norm > clipNorm && norm > 0.0) {
            val scale = clipNorm / norm
            for (p in params) for (i in p.grads.indices) p.grads[i] *= scale
        }
    }

    fun step() {
        clipGradients()
        t++
        val biasCorr1 = 1.0 - Math.pow(beta1, t.toDouble())
        val biasCorr2 = 1.0 - Math.pow(beta2, t.toDouble())
        for (pi in params.indices) {
            val p = params[pi]
            val mp = m[pi]
            val vp = v[pi]
            for (i in p.values.indices) {
                val g = p.grads[i]
                mp[i] = beta1 * mp[i] + (1 - beta1) * g
                vp[i] = beta2 * vp[i] + (1 - beta2) * g * g
                val mHat = mp[i] / biasCorr1
                val vHat = vp[i] / biasCorr2
                p.values[i] -= learningRate * mHat / (sqrt(vHat) + eps)
            }
        }
    }

    fun zeroGradAll() {
        for (p in params) p.grads.fill(0.0)
    }
}
