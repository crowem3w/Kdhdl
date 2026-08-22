package org.example.test.agent.pretrain

import org.example.test.agent.model.AdamOptimizer
import org.example.test.agent.model.CompactLatentStateEncoder
import org.example.test.agent.model.LinearHead
import org.example.test.agent.sim.MarketRegime
import org.example.test.agent.sim.SyntheticEpisode
import kotlin.math.exp
import kotlin.math.ln

/**
 * The "Server Pretraining" half of Model Development: fits
 * [CompactLatentStateEncoder]'s GRU weights against the synthetic
 * curriculum ([org.example.test.agent.sim.SyntheticExperienceSource]) so the
 * belief state it produces is actually informative before any policy,
 * regime detector, or live capital ever depends on it. Runs offline/
 * server-side (design doc §7.6: "a separate asynchronous training loop
 * updates weights; the live inference service periodically picks up new
 * checkpoints") - nothing in this file is on the on-device latency path.
 *
 * The GRU itself has no loss function of its own; it only produces a
 * hidden state. Two auxiliary heads borrow the labels the synthetic
 * generator happens to hand out for free (design doc §5.7: "Historical
 * regime labels... stored for supervised pretraining") to give that hidden
 * state something concrete to be *for*:
 *
 * 1. **Regime classification** (supervised): predict [MarketRegime] from
 *    h_t, cross-entropy against [org.example.test.agent.sim.LabeledFrame]'s
 *    ground truth. Directly targets design doc §3.1's stated purpose - "the
 *    policy conditions on inferred context" - context here operationalized
 *    as "which regime are we in."
 * 2. **Next-frame prediction** (self-supervised): predict the *next*
 *    timestep's normalized feature vector from h_t, MSE loss. Regime labels
 *    alone would let the encoder collapse to a 5-way regime indicator and
 *    discard everything else in the frame; forcing it to also predict raw
 *    market dynamics keeps it from throwing away information a downstream
 *    policy or risk head might still need.
 *
 * Both heads are discarded after pretraining - only the GRU weights (plus
 * the vectorizer's normalizer stats) get checkpointed and shipped to live
 * inference ([org.example.test.agent.model.EncoderCheckpoint]).
 */
class EncoderPretrainer(
    private val encoder: CompactLatentStateEncoder,
    private val learningRate: Double = 1e-3,
    private val regimeLossWeight: Double = 1.0,
    private val frameLossWeight: Double = 1.0,
    seed: Long = 7L,
) {
    private val regimeHead = LinearHead(inputDim = encoder.config.hiddenDim, outputDim = MarketRegime.entries.size, seed = seed)
    private val frameHead = LinearHead(inputDim = encoder.config.hiddenDim, outputDim = encoder.config.inputDim, seed = seed xor 0x2545F4914F6CDD1DL)

    private val optimizer = AdamOptimizer(
        params = encoder.gru.parameters() + regimeHead.parameters() + frameHead.parameters(),
        learningRate = learningRate,
    )

    data class EpochMetrics(
        val meanRegimeLoss: Double,
        val meanFrameLoss: Double,
        val regimeAccuracy: Double,
        val episodeCount: Int,
    )

    /**
     * One pass over [episodes]: BPTT within each episode (hidden state reset
     * per episode - episodes are independent synthetic runs, design doc
     * §4.1/§4.2), gradients from every episode in the pass accumulated
     * together before a single [AdamOptimizer.step] (mini-batch = one epoch's
     * worth of episodes; simple, and the synthetic generator makes "more
     * data" cheap enough that batch-size tuning isn't the first lever worth
     * pulling per design doc §4.1's "Cheap, infinite data").
     *
     * @param fitNormalizer True during early epochs so the vectorizer's
     *   running mean/std actually reflect the corpus; typically switched to
     *   false for later epochs so normalization stats stop drifting under
     *   the model that's being fit against them.
     */
    fun runEpoch(episodes: List<SyntheticEpisode>, fitNormalizer: Boolean): EpochMetrics {
        val metrics = forwardBackward(episodes, fitNormalizer, applyGradients = true)
        return metrics
    }

    /**
     * Forward-only pass: computes the same loss/accuracy metrics as
     * [runEpoch] but never calls [AdamOptimizer.step], so scoring a held-out
     * set here cannot leak into the trained weights. Still runs
     * [org.example.test.agent.model.GRUCell.backwardStep] internally because
     * that's also where this file's loss bookkeeping lives, but the
     * resulting gradients are discarded (zeroed) instead of stepped -
     * cheap enough at "compact" model sizes that a separate forward-only
     * code path isn't worth the duplication.
     */
    fun evaluate(episodes: List<SyntheticEpisode>): EpochMetrics {
        val metrics = forwardBackward(episodes, fitNormalizer = false, applyGradients = false)
        return metrics
    }

    private fun forwardBackward(episodes: List<SyntheticEpisode>, fitNormalizer: Boolean, applyGradients: Boolean): EpochMetrics {
        encoder.gru.zeroGrad()
        regimeHead.zeroGrad()
        frameHead.zeroGrad()

        var regimeLossSum = 0.0
        var frameLossSum = 0.0
        var correct = 0
        var stepCount = 0

        for (episode in episodes) {
            val frames = episode.frames.map { it.frame }
            val encoding = encoder.encodeEpisode(frames, fitNormalizer = fitNormalizer)
            val caches = encoding.caches
            val states = encoding.hiddenStates
            val steps = frames.size
            var dhFuture = DoubleArray(encoder.config.hiddenDim)

            for (t in steps - 1 downTo 0) {
                val h = states[t]
                var dh = dhFuture.copyOf()

                // --- Regime classification head ---
                val trueRegime = episode.frames[t].regime
                val logits = regimeHead.forward(h)
                val probs = softmax(logits)
                val trueIdx = trueRegime.ordinal
                regimeLossSum += -ln(probs[trueIdx].coerceAtLeast(1e-12))
                if (probs.indices.maxByOrNull { probs[it] } == trueIdx) correct++
                val dLogits = DoubleArray(probs.size) { i -> (probs[i] - if (i == trueIdx) 1.0 else 0.0) * regimeLossWeight }
                dh = addInPlace(dh, regimeHead.backward(h, dLogits))

                // --- Next-frame prediction head (skip the last step: no "next" target within this episode) ---
                if (t < steps - 1) {
                    val target = caches[t + 1].x
                    val pred = frameHead.forward(h)
                    var mse = 0.0
                    val dPred = DoubleArray(pred.size)
                    for (i in pred.indices) {
                        val diff = pred[i] - target[i]
                        mse += diff * diff
                        dPred[i] = 2.0 * diff / pred.size * frameLossWeight
                    }
                    frameLossSum += mse / pred.size
                    dh = addInPlace(dh, frameHead.backward(h, dPred))
                }

                dhFuture = encoder.gru.backwardStep(caches[t], dh)
                stepCount++
            }
        }

        if (applyGradients) optimizer.step()

        return EpochMetrics(
            meanRegimeLoss = if (stepCount > 0) regimeLossSum / stepCount else 0.0,
            meanFrameLoss = if (stepCount > 0) frameLossSum / stepCount else 0.0,
            regimeAccuracy = if (stepCount > 0) correct.toDouble() / stepCount else 0.0,
            episodeCount = episodes.size,
        )
    }

    private fun softmax(logits: DoubleArray): DoubleArray {
        val max = logits.max()
        val exps = DoubleArray(logits.size) { exp(logits[it] - max) }
        val sum = exps.sum()
        return DoubleArray(logits.size) { exps[it] / sum }
    }

    private fun addInPlace(a: DoubleArray, b: DoubleArray): DoubleArray {
        for (i in a.indices) a[i] += b[i]
        return a
    }
}
