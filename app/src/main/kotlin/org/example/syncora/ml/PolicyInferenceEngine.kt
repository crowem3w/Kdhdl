package org.example.syncora.ml

import android.content.Context
import android.util.Log
import org.example.syncora.bitget.MdpStateSnapshot
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Which physical model file is currently loaded - for logging/telemetry, not equality-sensitive control flow. */
data class LoadedModelVersion(val filePath: String, val lastModifiedMs: Long, val sizeBytes: Long)

/**
 * Runs forward inference through the on-device policy network - design doc
 * §3.3 ("Inference: runs on every relevant tick/kline close ... using the
 * current frozen TFLite policy"). Loads a `.tflite` model file from local
 * (app-private) storage via [PolicyModelStore] rather than from the APK's
 * assets, since the whole point is that the file backing this engine
 * changes over the app's lifetime as new models get trained and promoted.
 *
 * **Model swapping.** This engine only ever reads from
 * [PolicyModelStore.liveModelFile]. Promoting a newly trained candidate
 * (§3.3 step 5) is: the training job stages it via [PolicyModelStore],
 * calls [PolicyModelStore.promoteCandidateToLive], then calls this class's
 * [reload]. Nothing else in the app - the decision loop, the risk
 * guardrails (§5), the experience logger (§3.6) - needs to know a swap
 * happened; they all keep calling [infer] the same way before and after.
 *
 * **I/O contract.** The model is expected to take a single `float32` input
 * tensor shaped `[1, K]` (`K` = [expectedInputDimension], batch size 1) and
 * produce a single `float32` output tensor that reduces to one scalar - the
 * design doc's `a_t` (§3.2), assumed to already be squashed into `[-1, 1]`
 * by the model's own output layer (e.g. `tanh`). [infer] re-clamps to
 * `[-1, 1]` defensively before scaling by [actionLeverageCap], so a
 * mis-exported model can't hand back an unbounded leverage target that
 * skips the risk guardrails downstream.
 *
 * **Delegates.** Uses the interpreter's default CPU path, which has used
 * XNNPACK for float32 ops by default since TFLite 2.3 (design doc §1.2's
 * "CPU/XNNPACK ... delegate" - no extra wiring needed). NNAPI is attempted
 * as an additional delegate on top of that and is pure best-effort: it's
 * unavailable or flaky on some devices/emulators, so a failure to create or
 * apply it just falls back to the CPU/XNNPACK path rather than failing
 * model load.
 *
 * **Thread-safety.** [infer] and [reload] are synchronized against each
 * other so a model promotion can't swap the interpreter out from under an
 * in-flight inference call.
 */
class PolicyInferenceEngine(
    context: Context,
    private val modelStore: PolicyModelStore = PolicyModelStore(context),
    private val expectedInputDimension: Int = MdpStateSnapshot.STATE_DIMENSION,
    private val actionLeverageCap: Double = 3.0,
    private val numThreads: Int = 2,
) {
    private companion object {
        const val TAG = "PolicyInferenceEngine"
    }

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var loadedVersion: LoadedModelVersion? = null

    /** The model file currently backing [infer], or `null` before the first successful [reload]/[ensureLoaded]. */
    val currentVersion: LoadedModelVersion?
        get() = synchronized(lock) { loadedVersion }

    /** `true` once a model has been successfully loaded and is ready for [infer] calls. */
    fun isReady(): Boolean = synchronized(lock) { interpreter != null }

    /** [reload]s only if nothing is loaded yet. Cheap to call from every decision-boundary tick as a readiness check. */
    fun ensureLoaded(): Boolean {
        if (isReady()) return true
        return reload()
    }

    /**
     * (Re)loads the interpreter from [PolicyModelStore.liveModelFile].
     * Call this after [PolicyModelStore.promoteCandidateToLive] to actually
     * start serving the newly promoted model.
     *
     * Returns `false` and leaves any previously-loaded interpreter in place
     * if there's no live model file yet, or the file fails to load/validate
     * - a bad or corrupt promotion should never leave the engine without a
     * working policy mid-session. Check [currentVersion] before and after
     * to tell whether a call actually swapped anything.
     */
    fun reload(): Boolean = synchronized(lock) {
        val file = modelStore.liveModelFile
        if (!modelStore.hasLiveModel()) {
            Log.w(TAG, "No live model file at ${file.path} yet; keeping previously loaded interpreter, if any")
            return false
        }

        var candidateDelegate: NnApiDelegate? = null
        var candidateInterpreter: Interpreter? = null
        try {
            val buffer = mapModelFile(file)
            val options = Interpreter.Options().apply { setNumThreads(numThreads) }
            candidateDelegate = tryCreateNnApiDelegate()?.also { options.addDelegate(it) }

            candidateInterpreter = Interpreter(buffer, options)
            validateShapes(candidateInterpreter)

            // Only tear down the previously-loaded interpreter/delegate once
            // the replacement has loaded *and* validated successfully.
            interpreter?.close()
            nnApiDelegate?.close()

            interpreter = candidateInterpreter
            nnApiDelegate = candidateDelegate
            loadedVersion = LoadedModelVersion(
                filePath = file.path,
                lastModifiedMs = file.lastModified(),
                sizeBytes = file.length(),
            )
            Log.i(TAG, "Loaded live policy model: $loadedVersion")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load live model from ${file.path}; keeping previous interpreter", e)
            candidateInterpreter?.close()
            candidateDelegate?.close()
            false
        }
    }

    /**
     * Runs one forward pass and returns the scalar target-leverage action
     * `a_t ∈ [-L_max, +L_max]` (design doc §3.2), or `null` if no model is
     * loaded yet. Callers at a decision boundary should treat `null` the
     * same as `StateVectorBuilder` reporting `StateVectorUnavailable`: skip
     * this tick, don't act.
     */
    fun infer(state: DoubleArray): Double? = synchronized(lock) {
        val activeInterpreter = interpreter
        if (activeInterpreter == null) {
            Log.w(TAG, "infer() called with no model loaded")
            return null
        }
        require(state.size == expectedInputDimension) {
            "State vector has ${state.size} dims, this engine expects $expectedInputDimension"
        }

        val input = arrayOf(FloatArray(state.size) { i -> state[i].toFloat() })
        val output = arrayOf(FloatArray(1))
        return try {
            activeInterpreter.run(input, output)
            val bounded = output[0][0].toDouble().coerceIn(-1.0, 1.0)
            bounded * actionLeverageCap
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            null
        }
    }

    /** Convenience overload taking the state vector straight from [StateVectorBuilder]'s output. */
    fun infer(snapshot: MdpStateSnapshot): Double? = infer(snapshot.toDoubleArray())

    /** Releases the interpreter/delegate. Safe to call from a service's `onDestroy()`; [reload]/[ensureLoaded] after this works fine. */
    fun close(): Unit = synchronized(lock) {
        interpreter?.close()
        nnApiDelegate?.close()
        interpreter = null
        nnApiDelegate = null
        loadedVersion = null
    }

    private fun mapModelFile(file: File): MappedByteBuffer =
        FileInputStream(file).use { stream ->
            stream.channel.use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
        }

    private fun tryCreateNnApiDelegate(): NnApiDelegate? = try {
        NnApiDelegate()
    } catch (e: Exception) {
        Log.w(TAG, "NNAPI delegate unavailable on this device, using CPU/XNNPACK only: ${e.message}")
        null
    }

    /**
     * Fails loudly on a shape mismatch rather than letting a wrongly-shaped
     * model silently produce garbage actions. Accepts either a `[1, K]`
     * (batch-of-one) or flat `[K]` input tensor, matching how different
     * export pipelines (e.g. Keras vs. a hand-built TF graph) tend to shape
     * a single-example inference signature.
     */
    private fun validateShapes(candidate: Interpreter) {
        val inputShape = candidate.getInputTensor(0).shape()
        val totalInputSize = inputShape.fold(1) { acc, dim -> acc * dim }
        val featureDimMatches = inputShape.lastOrNull() == expectedInputDimension
        check(totalInputSize == expectedInputDimension || featureDimMatches) {
            "Model input shape ${inputShape.toList()} doesn't match expected state dimension $expectedInputDimension"
        }

        val outputShape = candidate.getOutputTensor(0).shape()
        val totalOutputSize = outputShape.fold(1) { acc, dim -> acc * dim }
        check(totalOutputSize == 1) {
            "Model output shape ${outputShape.toList()} doesn't reduce to a single scalar action"
        }
    }
}
