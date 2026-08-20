package org.example.test.agent

import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Drop-in replacement for [OnlineQLearningPolicy] with the same public
 * surface (decide / learn / exportWeights / restoreFrom / reset), but with
 * two structural changes:
 *
 *  1. Q(s, a) is now a 2-layer MLP (input -> hidden(tanh) -> actions)
 *     instead of a linear map, so it can represent feature *interactions*
 *     ("high vol AND wide spread AND negative imbalance" behaves
 *     differently than the linear sum of those three) - which is the
 *     actual ceiling a linear model can't get past no matter how it's
 *     tuned.
 *  2. The learning rate decays over the number of learn() calls instead
 *     of staying fixed, so the policy can settle into a stable point
 *     instead of perpetually chasing the last tick at full step size.
 *     (RlAgentController's per-tick volatility damping still applies on
 *     top of this - the two solve different problems: damping protects
 *     against single-tick outliers, decay lets the policy stop chasing
 *     over the long run.)
 *
 * Still zero ML runtime / zero native deps, same as the original - this is
 * ~200 lines of array math, not a TF-Lite/ONNX integration.
 */
class MlpQLearningPolicy(
    private val featureCount: Int = MarketFeatureExtractor.FEATURE_COUNT,
    private val hiddenSize: Int = 16,
    private val discountFactor: Double = 0.95,
    private val random: Random = Random.Default,
    private val tdErrorClip: Double = 5.0,
    // Learning-rate decay: effective_lr = baseLr / (1 + decayRate * stepsSoFar),
    // floored so it never fully stalls out.
    private val lrDecayRate: Double = 0.0005,
    private val lrFloorFraction: Double = 0.1,
) {
    val actions = AgentAction.values()
    private val actionCount = actions.size

    // ---- parameters ----
    // Layer 1: hidden = tanh(W1 . s + b1)
    private val w1: Array<DoubleArray> = Array(hiddenSize) { randInit(featureCount) }
    private val b1: DoubleArray = DoubleArray(hiddenSize)
    // Layer 2: Q = W2 . hidden + b2  (linear output - Q-values are unbounded)
    private val w2: Array<DoubleArray> = Array(actionCount) { randInit(hiddenSize) }
    private val b2: DoubleArray = DoubleArray(actionCount)

    private var learnSteps: Long = 0

    private fun randInit(size: Int): DoubleArray {
        // Small Xavier-ish init - big enough to break symmetry between
        // hidden units, small enough not to saturate tanh immediately.
        val scale = 1.0 / kotlin.math.sqrt(size.toDouble())
        return DoubleArray(size) { (random.nextDouble() * 2.0 - 1.0) * scale }
    }

    data class Decision(
        val action: AgentAction,
        val qValues: DoubleArray,
        val explored: Boolean,
        val confidence: Double,
    )

    data class LearnResult(
        val predictedQ: Double,
        val target: Double,
        val tdErrorRaw: Double,
        val tdErrorClipped: Double,
        val bootstrapValue: Double,
        val effectiveLearningRate: Double,
    )

    /** Forward pass, keeping the hidden activations around for backprop in [learn]. */
    private data class Forward(val hidden: DoubleArray, val qValues: DoubleArray)

    private fun forward(state: DoubleArray): Forward {
        val hidden = DoubleArray(hiddenSize) { h ->
            var sum = b1[h]
            for (i in state.indices) sum += w1[h][i] * state[i]
            kotlin.math.tanh(sum)
        }
        val q = DoubleArray(actionCount) { a ->
            var sum = b2[a]
            for (h in 0 until hiddenSize) sum += w2[a][h] * hidden[h]
            sum
        }
        return Forward(hidden, q)
    }

    private fun argmax(values: DoubleArray): Int {
        var bestIndex = 0
        var bestValue = values[0]
        for (i in 1 until values.size) if (values[i] > bestValue) { bestValue = values[i]; bestIndex = i }
        return bestIndex
    }

    private fun maxValue(values: DoubleArray): Double {
        var best = values[0]
        for (i in 1 until values.size) if (values[i] > best) best = values[i]
        return best
    }

    private fun softmaxConfidence(qValues: DoubleArray, actionIndex: Int, temperature: Double = 0.5): Double {
        val maxQ = maxValue(qValues)
        var sum = 0.0
        var actionExp = 0.0
        for (i in qValues.indices) {
            val e = exp((qValues[i] - maxQ) / temperature)
            sum += e
            if (i == actionIndex) actionExp = e
        }
        return if (sum > 0.0) actionExp / sum else 1.0 / qValues.size
    }

    /** Pure forward pass - identical contract to the original: never mutates parameters. */
    fun decide(state: DoubleArray, explorationRate: Double): Decision {
        val qValues = forward(state).qValues
        val explored = random.nextDouble() < explorationRate.coerceIn(0.0, 1.0)
        val actionIndex = if (explored) random.nextInt(actionCount) else argmax(qValues)
        val confidence = softmaxConfidence(qValues, actionIndex)
        return Decision(actions[actionIndex], qValues, explored, confidence)
    }

    /**
     * One online update via manual backprop through both layers. Same
     * TD-target / clipping logic as the linear version; the only new part
     * is that the TD-error gradient now flows through the hidden layer
     * (chain rule through tanh) instead of directly onto the input.
     */
    fun learn(
        previousState: DoubleArray,
        previousAction: AgentAction,
        reward: Double,
        nextState: DoubleArray,
        learningRate: Double,
    ): LearnResult {
        val nextQ = forward(nextState).qValues
        val bootstrap = maxValue(nextQ)
        val target = reward + discountFactor * bootstrap

        val fwd = forward(previousState)
        val actionIdx = previousAction.ordinal
        val predicted = fwd.qValues[actionIdx]

        val rawError = target - predicted
        val error = rawError.coerceIn(-tdErrorClip, tdErrorClip)

        learnSteps++
        val decay = 1.0 / (1.0 + lrDecayRate * learnSteps)
        val effectiveLr = learningRate * max(decay, lrFloorFraction)

        // --- output layer gradient (only the taken action's row updates -
        // this mirrors the original: Q-learning only has a target for the
        // (state, action) pair actually experienced) ---
        // dLoss/dQ[actionIdx] = -error  (we ascend on error directly, same
        // sign convention as the original linear update)
        val dHidden = DoubleArray(hiddenSize)
        for (h in 0 until hiddenSize) {
            dHidden[h] = w2[actionIdx][h] * error // gradient flowing back through W2
            w2[actionIdx][h] += effectiveLr * error * fwd.hidden[h]
        }
        b2[actionIdx] += effectiveLr * error

        // --- hidden layer gradient, through tanh' = (1 - tanh^2) ---
        for (h in 0 until hiddenSize) {
            val tanhGrad = 1.0 - fwd.hidden[h] * fwd.hidden[h]
            val g = dHidden[h] * tanhGrad
            for (i in previousState.indices) {
                w1[h][i] += effectiveLr * g * previousState[i]
            }
            b1[h] += effectiveLr * g
        }

        return LearnResult(
            predictedQ = predicted,
            target = target,
            tdErrorRaw = rawError,
            tdErrorClipped = error,
            bootstrapValue = bootstrap,
            effectiveLearningRate = effectiveLr,
        )
    }

    fun reset() {
        for (row in w1) for (i in row.indices) row[i] = randInit(1)[0]
        b1.fill(0.0)
        for (row in w2) for (i in row.indices) row[i] = randInit(1)[0]
        b2.fill(0.0)
        learnSteps = 0
    }

    /** Flat export: [w1, b1, w2, b2] - shape-checked on restore, same philosophy as the original. */
    fun exportWeights(): MlpWeights = MlpWeights(
        w1 = Array(w1.size) { w1[it].copyOf() },
        b1 = b1.copyOf(),
        w2 = Array(w2.size) { w2[it].copyOf() },
        b2 = b2.copyOf(),
    )

    fun restoreFrom(saved: MlpWeights): Boolean {
        if (saved.w1.size != w1.size || saved.w2.size != w2.size) return false
        if (saved.w1.any { it.size != featureCount } || saved.w2.any { it.size != hiddenSize }) return false
        if (saved.b1.size != hiddenSize || saved.b2.size != actionCount) return false
        for (h in w1.indices) saved.w1[h].copyInto(w1[h])
        saved.b1.copyInto(b1)
        for (a in w2.indices) saved.w2[a].copyInto(w2[a])
        saved.b2.copyInto(b2)
        return true
    }

    data class MlpWeights(
        val w1: Array<DoubleArray>,
        val b1: DoubleArray,
        val w2: Array<DoubleArray>,
        val b2: DoubleArray,
    )
}
