package org.example.syncora.ml

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages the on-disk model-versioning distinction the design doc's batch
 * training job depends on (§3.3): a `current_live_model.tflite` that
 * [PolicyInferenceEngine] actually runs inference against, and a
 * `candidate_model.tflite` staged by a freshly completed training run that
 * hasn't cleared the CPCV/PBO promotion gate (§4) yet.
 *
 * Deliberately has zero TFLite/`Interpreter` awareness - it's a thin
 * file-management layer, so the (not-yet-built) `WorkManager` training job
 * can stage/promote/discard model files without depending on
 * [PolicyInferenceEngine], and vice versa. "Swap in a newly trained model
 * without touching the app's other logic" is then just:
 *
 * 1. Training job writes the exported `.tflite` bytes to [candidateModelFile].
 * 2. Training job runs it through the CPCV/PBO gate (§4).
 * 3. On pass: call [promoteCandidateToLive], then [PolicyInferenceEngine.reload].
 *    On fail: call [discardCandidate]; the live model and everything reading
 *    it never sees a candidate that didn't clear the bar.
 *
 * All file operations happen under app-private storage
 * ([Context.getFilesDir]) - never external storage - since a trading
 * policy's weights are exactly the kind of thing another app on the device
 * shouldn't be able to read or, worse, overwrite.
 */
class PolicyModelStore(context: Context) {

    private val modelsDir: File = File(context.applicationContext.filesDir, "models").apply { mkdirs() }

    /** The model [PolicyInferenceEngine] should be running inference against right now. */
    val liveModelFile: File get() = File(modelsDir, LIVE_MODEL_FILENAME)

    /** A freshly trained model staged for promotion, or a half-written one mid-stage. */
    val candidateModelFile: File get() = File(modelsDir, CANDIDATE_MODEL_FILENAME)

    /** The live model as it was immediately before the most recent promotion - [rollbackToPreviousLive]'s source. */
    private val previousLiveModelFile: File get() = File(modelsDir, PREVIOUS_LIVE_MODEL_FILENAME)

    fun hasLiveModel(): Boolean = liveModelFile.exists() && liveModelFile.length() > 0L

    fun hasCandidateModel(): Boolean = candidateModelFile.exists() && candidateModelFile.length() > 0L

    fun hasPreviousLiveModel(): Boolean = previousLiveModelFile.exists() && previousLiveModelFile.length() > 0L

    /**
     * Promotes [candidateModelFile] to [liveModelFile] - design doc §3.3
     * step 5's "Promote on pass." The outgoing live model (if any) is kept
     * as [previousLiveModelFile] rather than deleted, so a promotion that
     * turns out bad in practice can be undone with [rollbackToPreviousLive]
     * instead of waiting for the next scheduled retrain.
     *
     * Returns `false` without changing anything if there's no staged
     * candidate to promote.
     */
    fun promoteCandidateToLive(): Boolean {
        if (!hasCandidateModel()) return false
        if (liveModelFile.exists()) {
            atomicMove(liveModelFile, previousLiveModelFile)
        }
        atomicMove(candidateModelFile, liveModelFile)
        return true
    }

    /**
     * Undoes the most recent promotion, restoring whatever was live before
     * it. Best-effort - returns `false` if there's nothing to roll back to
     * (e.g. this is the very first model ever promoted).
     */
    fun rollbackToPreviousLive(): Boolean {
        if (!hasPreviousLiveModel()) return false
        atomicMove(previousLiveModelFile, liveModelFile)
        return true
    }

    /** Deletes a staged candidate without promoting it - design doc §3.3 step 5's "discard" path. */
    fun discardCandidate() {
        candidateModelFile.delete()
    }

    /**
     * Atomic where the underlying filesystem supports it (true for a
     * single app's private storage on a single volume, which is always the
     * case here), so a process death mid-promotion can never leave
     * [liveModelFile] partially written or missing. Falls back to a plain
     * replace on the rare filesystem that doesn't support atomic moves.
     */
    private fun atomicMove(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val LIVE_MODEL_FILENAME = "current_live_model.tflite"
        const val CANDIDATE_MODEL_FILENAME = "candidate_model.tflite"
        const val PREVIOUS_LIVE_MODEL_FILENAME = "previous_live_model.tflite"
    }
}
