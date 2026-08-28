package org.example.syncora.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything needed to resume a [ReadoutTrainer] exactly where it left
 * off: the trained readout weights `W_out` and the RLS covariance matrix
 * `P` (see [ReadoutTrainer.update]), plus the shape/hyperparameters needed
 * to reconstruct a *compatible* trainer - loading a checkpoint into a
 * trainer built with a different `nHidden` / `nOutputs` / bias
 * configuration would silently misinterpret the flat arrays' indexing, so
 * those are captured too, not just the two weight arrays.
 */
data class ReadoutCheckpoint(
    val nHidden: Int,
    val nOutputs: Int,
    val includeBias: Boolean,
    val forgettingFactor: Float,
    val wOut: FloatArray,
    val covariance: FloatArray,
) {
    // FloatArray has reference equality by default. This checkpoint exists
    // specifically to be compared for bit-identical save/load round trips
    // (Phase 3's second exit criterion), so equals()/hashCode() are
    // overridden to compare array *contents*.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReadoutCheckpoint) return false
        return nHidden == other.nHidden &&
            nOutputs == other.nOutputs &&
            includeBias == other.includeBias &&
            forgettingFactor == other.forgettingFactor &&
            wOut.contentEquals(other.wOut) &&
            covariance.contentEquals(other.covariance)
    }

    override fun hashCode(): Int {
        var result = nHidden
        result = 31 * result + nOutputs
        result = 31 * result + includeBias.hashCode()
        result = 31 * result + forgettingFactor.hashCode()
        result = 31 * result + wOut.contentHashCode()
        result = 31 * result + covariance.contentHashCode()
        return result
    }
}

/**
 * Builds a [ReadoutTrainer] that resumes from this checkpoint: the shape
 * it was saved with is reconstructed exactly, and the saved weights/
 * covariance are handed in as the trainer's initial state (see
 * [ReadoutTrainer]'s `initialWOut`/`initialCovariance` constructor params).
 */
fun ReadoutCheckpoint.toTrainer(): ReadoutTrainer = ReadoutTrainer(
    nHidden = nHidden,
    nOutputs = nOutputs,
    includeBias = includeBias,
    forgettingFactor = forgettingFactor,
    initialWOut = wOut,
    initialCovariance = covariance,
)

/** Captures this trainer's current `W_out`/`P` as a [ReadoutCheckpoint] ready to persist. */
fun ReadoutTrainer.toCheckpoint(): ReadoutCheckpoint = ReadoutCheckpoint(
    nHidden = nHidden,
    nOutputs = nOutputs,
    includeBias = includeBias,
    forgettingFactor = forgettingFactor,
    wOut = wOutSnapshot(),
    covariance = covarianceSnapshot(),
)

interface ReadoutCheckpointStore {
    suspend fun load(): ReadoutCheckpoint?

    suspend fun save(checkpoint: ReadoutCheckpoint)
}

object NoopReadoutCheckpointStore : ReadoutCheckpointStore {
    override suspend fun load(): ReadoutCheckpoint? = null
    override suspend fun save(checkpoint: ReadoutCheckpoint) = Unit
}

/**
 * Persists a [ReadoutCheckpoint] to a single JSON file on disk, following
 * the same convention [org.example.syncora.bitget.FileKlineCacheStore]
 * already established for [org.example.syncora.bitget.KlineCacheStore]:
 * plain `org.json` (no external serialization dependency), every step off
 * the main thread via [Dispatchers.IO], and an atomic
 * tmp-file-then-rename write so a process death mid-save can never leave a
 * half-written checkpoint for the next [load] to trip over.
 *
 * `W_out` and the covariance matrix are stored as JSON arrays of
 * [Float.toString] strings - exactly how
 * [org.example.syncora.bitget.FileKlineCacheStore] stores its `Double`
 * fields. `Float.toString` / `String.toFloat` are exact round-trip
 * inverses for every finite `Float` (the language spec guarantees the
 * printed decimal is the shortest one that parses back to the identical
 * bit pattern), so a save-then-load round trip reproduces `W_out` and the
 * covariance matrix bit-for-bit - this phase's second exit criterion.
 *
 * Takes the destination [file] directly (rather than only an Android
 * [Context]) so this is unit-testable on the plain JVM against a temp
 * directory, with no need to construct or mock a [Context]. The
 * [Context]-based secondary constructor is what production callers (the
 * `AgentOrchestrator` wired up in Phase 6) use, matching
 * [org.example.syncora.bitget.FileKlineCacheStore]'s constructor shape.
 */
class FileReadoutCheckpointStore(
    private val file: File,
) : ReadoutCheckpointStore {

    constructor(context: Context, checkpointKey: String) : this(
        File(context.applicationContext.filesDir, "readout_checkpoint_$checkpointKey.json"),
    )

    private companion object {
        const val TAG = "FileReadoutCheckpointStore"
    }

    override suspend fun load(): ReadoutCheckpoint? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val text = file.readText()
            if (text.isBlank()) return@withContext null
            val root = JSONObject(text)
            ReadoutCheckpoint(
                nHidden = root.getInt("nHidden"),
                nOutputs = root.getInt("nOutputs"),
                includeBias = root.getBoolean("includeBias"),
                forgettingFactor = root.getString("forgettingFactor").toFloat(),
                wOut = root.getJSONArray("wOut").toFloatArray(),
                covariance = root.getJSONArray("covariance").toFloatArray(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load readout checkpoint from ${file.name}: ${e.message}")
            null
        }
    }

    override suspend fun save(checkpoint: ReadoutCheckpoint) {
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject()
                root.put("nHidden", checkpoint.nHidden)
                root.put("nOutputs", checkpoint.nOutputs)
                root.put("includeBias", checkpoint.includeBias)
                root.put("forgettingFactor", checkpoint.forgettingFactor.toString())
                root.put("wOut", checkpoint.wOut.toJsonArray())
                root.put("covariance", checkpoint.covariance.toJsonArray())

                val parent = file.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save readout checkpoint to ${file.name}: ${e.message}")
            }
        }
    }

    private fun FloatArray.toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (v in this) arr.put(v.toString())
        return arr
    }

    private fun JSONArray.toFloatArray(): FloatArray =
        FloatArray(length()) { i -> getString(i).toFloat() }
}
