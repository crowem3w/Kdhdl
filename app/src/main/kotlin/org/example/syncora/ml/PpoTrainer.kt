package org.example.syncora.ml

import android.util.Log
import org.example.syncora.agent.RolloutStep
import org.example.syncora.agent.RolloutWindow
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import kotlin.random.Random

/** Hyperparameters for one PPO run (design doc §3.3 step 3 / §4's "sweep H hyperparameter configurations"). */
data class PpoHyperparameters(
    val learningRate: Float = 3e-4f,
    /** The clipped surrogate objective's ε - design doc §3.3: "the standard clipped surrogate objective." */
    val clipEpsilon: Float = 0.2f,
    val epochs: Int = 4,
    val minibatchSize: Int = 64,
    val valueLossCoefficient: Float = 0.5f,
    val entropyCoefficient: Float = 0.01f,
)

/** What one [PpoTrainer.train]/`evaluate` minibatch step reports back - see [PpoTrainer]'s "Model I/O contract". */
private data class PpoStepOutputs(
    val policyLoss: Double,
    val valueLoss: Double,
    val entropy: Double,
    val clipFraction: Double,
)

/** Epoch-averaged diagnostics from one [PpoTrainer.train] call - for logging/telemetry only, not the promotion decision (that's [org.example.syncora.agent.ValidationGate]'s job, working off [PpoEvaluationResult] instead). */
data class PpoTrainingResult(
    val stepsTrained: Int,
    val meanPolicyLoss: Double,
    val meanValueLoss: Double,
    val meanEntropy: Double,
    val meanClipFraction: Double,
)

/** One no-gradient pass of [PpoTrainer.evaluate] over a fixed set of windows - both plain diagnostics and, via [score], a CPCV split's in-sample/out-of-sample score (design doc §4's OOS performance matrix entries). */
data class PpoEvaluationResult(
    val meanPolicyLoss: Double,
    val meanValueLoss: Double,
    val meanEntropy: Double,
    val meanClipFraction: Double,
) {
    /** Higher-is-better score for CPCV/PBO ranking - the negative of the same combined objective the graph itself trains against, so "trained well" and "generalizes well" are measured on one consistent scale. */
    fun score(hyperparameters: PpoHyperparameters): Double =
        -(meanPolicyLoss + hyperparameters.valueLossCoefficient * meanValueLoss - hyperparameters.entropyCoefficient * meanEntropy)
}

/**
 * Runs on-device PPO fine-tuning (design doc §3.3 step 3: "Run several PPO
 * minibatch epochs over the assembled buffer to produce candidate
 * weights") and exports the result as a candidate `.tflite` file (§3.3
 * step 3 / [PolicyModelStore.candidateModelFile]).
 *
 * **Why this class doesn't implement backprop itself.** Nothing in this
 * codebase is an autodiff engine, and TFLite's Android runtime doesn't
 * expose arbitrary gradient computation from Kotlin - so the clipped
 * surrogate loss and its gradients have to already be baked into the
 * `.tflite` graph by whatever offline pipeline produced the base model.
 * This mirrors TensorFlow's own documented on-device-training pattern for
 * Android: a model exported with named `train`/`evaluate`/`export`/`infer`
 * `tf.function` signatures (kept through `TFLiteConverter`), invoked
 * purely by name via [Interpreter.runSignature] /
 * [Interpreter.getSignatureRunner]
 * (https://www.tensorflow.org/lite/examples/on_device_training/overview).
 * This class is the Kotlin-side half of that contract: it doesn't know or
 * care *how* the loss/gradients are computed inside the graph, only that
 * calling [SIGNATURE_TRAIN] with one minibatch applies exactly one
 * optimizer step to the interpreter's internal resource-variable weights
 * and reports back the scalar diagnostics below.
 *
 * **Model I/O contract** (required of every `.tflite` file passed to
 * [train]/[evaluate]/[estimateValue] - a model missing one of these
 * signatures fails loudly at the first [Interpreter.runSignature] call
 * rather than silently no-op-ing):
 * - [SIGNATURE_TRAIN]: inputs [INPUT_STATE] `float32[B,K]`, [INPUT_ACTION]
 *   `float32[B]` (the action actually dispatched - matches
 *   [org.example.syncora.agent.PendingExperienceEntry.action], not the
 *   policy mean), [INPUT_OLD_LOG_PROB] `float32[B]`, [INPUT_ADVANTAGE]
 *   `float32[B]` (already normalized by [normalizeAdvantages]),
 *   [INPUT_VALUE_TARGET] `float32[B]`, [INPUT_CLIP_EPSILON] /
 *   [INPUT_VALUE_COEFF] / [INPUT_ENTROPY_COEFF] / [INPUT_LEARNING_RATE]
 *   scalars; outputs [OUTPUT_POLICY_LOSS] / [OUTPUT_VALUE_LOSS] /
 *   [OUTPUT_ENTROPY] / [OUTPUT_CLIP_FRACTION] scalars, and applies one
 *   gradient step as a side effect.
 * - [SIGNATURE_EVALUATE]: identical inputs minus the learning rate,
 *   identical outputs, but never mutates weights - what [evaluate] (and
 *   the CPCV gate's in-sample/out-of-sample scoring) runs.
 * - [SIGNATURE_EXPORT]: no inputs; output [OUTPUT_MODEL_BYTES], a `uint8`
 *   tensor holding a complete, self-contained `.tflite` flatbuffer with
 *   the graph's *current* weights baked in - what lets [train] hand
 *   [PolicyModelStore] a single-file candidate model with no separate
 *   Python re-export step.
 * - [SIGNATURE_INFER]: input [INPUT_STATE] `float32[1,K]`; output
 *   [OUTPUT_VALUE] `float32[1]`, the critic's `V(s)` - what
 *   [estimateValue] exposes as
 *   [org.example.syncora.agent.RolloutWindowBuilder]'s `criticValueFn`.
 *
 * **Compatibility with [PolicyInferenceEngine].** The `.tflite` bytes
 * [SIGNATURE_EXPORT] produces are the *same file* that becomes
 * [PolicyModelStore.liveModelFile] after promotion, and
 * [PolicyInferenceEngine] talks to it via a plain, signature-agnostic
 * [Interpreter.run] call against the model's default single input/output
 * tensor pair - it has no idea any of the signatures above exist. That's a
 * requirement on the *offline* pipeline that builds the base trainable
 * model (its default subgraph I/O must stay the `[1,K]->[1,1]` shape
 * [PolicyInferenceEngine.validateShapes] checks), not something this class
 * can enforce - but a violation doesn't fail silently: `reload()` after
 * promotion validates shapes and returns `false`, and
 * [org.example.syncora.work.PolicyTrainingWorker] rolls the promotion back
 * when that happens.
 */
class PpoTrainer(private val random: Random = Random.Default) {

    fun train(
        baseModelFile: File,
        windows: List<RolloutWindow>,
        outputModelFile: File,
        hyperparameters: PpoHyperparameters = PpoHyperparameters(),
    ): PpoTrainingResult {
        val steps = normalizeAdvantages(windows.flatMap { it.steps })
        require(steps.isNotEmpty()) { "Cannot run PPO training on an empty rollout buffer" }

        Interpreter(mapModelFile(baseModelFile)).use { interpreter ->
            var stepsTrained = 0
            var sumPolicy = 0.0
            var sumValue = 0.0
            var sumEntropy = 0.0
            var sumClip = 0.0

            repeat(hyperparameters.epochs) {
                for (batch in minibatches(steps, hyperparameters.minibatchSize)) {
                    val out = runStep(interpreter, batch, hyperparameters, SIGNATURE_TRAIN, applyGradient = true)
                    sumPolicy += out.policyLoss
                    sumValue += out.valueLoss
                    sumEntropy += out.entropy
                    sumClip += out.clipFraction
                    stepsTrained++
                }
            }

            exportModel(interpreter, outputModelFile)

            return PpoTrainingResult(
                stepsTrained = stepsTrained,
                meanPolicyLoss = sumPolicy / stepsTrained,
                meanValueLoss = sumValue / stepsTrained,
                meanEntropy = sumEntropy / stepsTrained,
                meanClipFraction = sumClip / stepsTrained,
            )
        }
    }

    /** No-gradient pass over [windows] - what [org.example.syncora.agent.CpcvPboValidationGate] scores each split's train/test halves with. */
    fun evaluate(
        modelFile: File,
        windows: List<RolloutWindow>,
        hyperparameters: PpoHyperparameters = PpoHyperparameters(),
    ): PpoEvaluationResult {
        val steps = normalizeAdvantages(windows.flatMap { it.steps })
        require(steps.isNotEmpty()) { "Cannot evaluate the PPO objective on an empty rollout buffer" }

        Interpreter(mapModelFile(modelFile)).use { interpreter ->
            var n = 0
            var sumPolicy = 0.0
            var sumValue = 0.0
            var sumEntropy = 0.0
            var sumClip = 0.0
            for (batch in minibatches(steps, hyperparameters.minibatchSize)) {
                val out = runStep(interpreter, batch, hyperparameters, SIGNATURE_EVALUATE, applyGradient = false)
                sumPolicy += out.policyLoss
                sumValue += out.valueLoss
                sumEntropy += out.entropy
                sumClip += out.clipFraction
                n++
            }
            return PpoEvaluationResult(sumPolicy / n, sumValue / n, sumEntropy / n, sumClip / n)
        }
    }

    /** Single-state forward pass through [modelFile]'s critic head - see [org.example.syncora.agent.RolloutWindowBuilder]'s `criticValueFn` kdoc for why a fresh critic call is needed at all. */
    fun estimateValue(modelFile: File, state: DoubleArray): Double {
        Interpreter(mapModelFile(modelFile)).use { interpreter ->
            val inputs = mapOf<String, Any>(INPUT_STATE to arrayOf(FloatArray(state.size) { state[it].toFloat() }))
            val outputs = mutableMapOf<String, Any>(OUTPUT_VALUE to arrayOf(FloatArray(1)))
            interpreter.runSignature(inputs, outputs, SIGNATURE_INFER)
            @Suppress("UNCHECKED_CAST")
            return (outputs.getValue(OUTPUT_VALUE) as Array<FloatArray>)[0][0].toDouble()
        }
    }

    private fun runStep(
        interpreter: Interpreter,
        batch: List<RolloutStep>,
        hyperparameters: PpoHyperparameters,
        signatureKey: String,
        applyGradient: Boolean,
    ): PpoStepOutputs {
        val batchSize = batch.size
        val stateDim = batch.first().state.size

        val inputs = mutableMapOf<String, Any>(
            INPUT_STATE to Array(batchSize) { i -> FloatArray(stateDim) { d -> batch[i].state[d].toFloat() } },
            INPUT_ACTION to FloatArray(batchSize) { batch[it].action.toFloat() },
            INPUT_OLD_LOG_PROB to FloatArray(batchSize) { batch[it].logProb.toFloat() },
            INPUT_ADVANTAGE to FloatArray(batchSize) { batch[it].advantage.toFloat() },
            INPUT_VALUE_TARGET to FloatArray(batchSize) { batch[it].valueTarget.toFloat() },
            INPUT_CLIP_EPSILON to floatArrayOf(hyperparameters.clipEpsilon),
            INPUT_VALUE_COEFF to floatArrayOf(hyperparameters.valueLossCoefficient),
            INPUT_ENTROPY_COEFF to floatArrayOf(hyperparameters.entropyCoefficient),
        )
        // Only the training call needs a learning rate; evaluate() runs the
        // same forward+loss computation with no optimizer step behind it.
        if (applyGradient) {
            inputs[INPUT_LEARNING_RATE] = floatArrayOf(hyperparameters.learningRate)
        }

        val outputs = mutableMapOf<String, Any>(
            OUTPUT_POLICY_LOSS to floatArrayOf(0f),
            OUTPUT_VALUE_LOSS to floatArrayOf(0f),
            OUTPUT_ENTROPY to floatArrayOf(0f),
            OUTPUT_CLIP_FRACTION to floatArrayOf(0f),
        )

        interpreter.runSignature(inputs, outputs, signatureKey)

        return PpoStepOutputs(
            policyLoss = (outputs.getValue(OUTPUT_POLICY_LOSS) as FloatArray)[0].toDouble(),
            valueLoss = (outputs.getValue(OUTPUT_VALUE_LOSS) as FloatArray)[0].toDouble(),
            entropy = (outputs.getValue(OUTPUT_ENTROPY) as FloatArray)[0].toDouble(),
            clipFraction = (outputs.getValue(OUTPUT_CLIP_FRACTION) as FloatArray)[0].toDouble(),
        )
    }

    /**
     * Standard PPO trick: normalize advantages to zero mean / unit
     * variance across the *whole* buffer being trained/evaluated on, not
     * per-minibatch, so [INPUT_CLIP_EPSILON] means the same thing across
     * batches whose raw GAE advantage scale can otherwise vary a lot
     * (design doc §3.6's windows are bootstrapped independently, so
     * different windows' raw advantage magnitudes aren't directly
     * comparable before this step).
     */
    private fun normalizeAdvantages(steps: List<RolloutStep>): List<RolloutStep> {
        if (steps.isEmpty()) return steps
        val mean = steps.sumOf { it.advantage } / steps.size
        val variance = steps.sumOf { (it.advantage - mean) * (it.advantage - mean) } / steps.size
        val stdDev = sqrt(variance).takeIf { it > MIN_STD_DEV } ?: 1.0
        return steps.map { it.copy(advantage = (it.advantage - mean) / stdDev) }
    }

    private fun minibatches(steps: List<RolloutStep>, minibatchSize: Int): List<List<RolloutStep>> =
        steps.shuffled(random).chunked(minibatchSize)

    private fun exportModel(interpreter: Interpreter, outputModelFile: File) {
        val runner = interpreter.getSignatureRunner(SIGNATURE_EXPORT)
        runner.run()
        val buffer = runner.getOutputTensor(OUTPUT_MODEL_BYTES).buffer()
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        outputModelFile.parentFile?.mkdirs()
        outputModelFile.writeBytes(bytes)
        Log.i(TAG, "Exported candidate model (${bytes.size} bytes) to ${outputModelFile.path}")
    }

    private fun mapModelFile(file: File): MappedByteBuffer =
        FileInputStream(file).use { stream -> stream.channel.use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) } }

    companion object {
        private const val TAG = "PpoTrainer"
        private const val MIN_STD_DEV = 1e-8

        const val SIGNATURE_TRAIN = "train"
        const val SIGNATURE_EVALUATE = "evaluate"
        const val SIGNATURE_EXPORT = "export"
        const val SIGNATURE_INFER = "infer"

        const val INPUT_STATE = "state"
        const val INPUT_ACTION = "action"
        const val INPUT_OLD_LOG_PROB = "old_log_prob"
        const val INPUT_ADVANTAGE = "advantage"
        const val INPUT_VALUE_TARGET = "value_target"
        const val INPUT_CLIP_EPSILON = "clip_epsilon"
        const val INPUT_VALUE_COEFF = "value_coeff"
        const val INPUT_ENTROPY_COEFF = "entropy_coeff"
        const val INPUT_LEARNING_RATE = "learning_rate"

        const val OUTPUT_POLICY_LOSS = "policy_loss"
        const val OUTPUT_VALUE_LOSS = "value_loss"
        const val OUTPUT_ENTROPY = "entropy"
        const val OUTPUT_CLIP_FRACTION = "clip_fraction"
        const val OUTPUT_MODEL_BYTES = "model_bytes"
        const val OUTPUT_VALUE = "value"
    }
}
