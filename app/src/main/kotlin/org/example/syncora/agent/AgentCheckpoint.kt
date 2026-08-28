package org.example.syncora.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything needed to resume a live [AgentOrchestrator] session exactly
 * where it left off - Prompt 7d's four required components, bundled into
 * one persistable unit: the reservoir's hidden state `x_t` ([ReservoirEngine]
 * / Phase 2), the readout's `W_out` and RLS covariance `P` (wrapped as the
 * existing [ReadoutCheckpoint] from Phase 3 - no need to re-derive that
 * shape here), and the policy's trained weights ([PolicyEngine] / Phase 5),
 * plus the shape parameters ([policyNHidden]/[policyNBack]) needed to
 * reconstruct a *compatible* [PolicyEngine] - same reasoning
 * [ReadoutCheckpoint]'s own kdoc gives for carrying its shape alongside its
 * weights.
 *
 * Deliberately does *not* include [PolicyEngine]'s own-output feedback
 * history or RTRL trace state (`pastPositions`/`traceHistory`) - Prompt 7d
 * names exactly four components ("reservoir state, `W_out`, RLS covariance,
 * and policy weights"), not the policy's short recurrent window, so this
 * checkpoint sticks to that list precisely rather than silently expanding
 * scope.
 */
data class AgentCheckpoint(
    /** Wall-clock time this checkpoint was captured, epoch millis - diagnostic only, not used to validate compatibility on load. */
    val savedAtMs: Long,
    /** [ReservoirEngine.currentState]'s `x_t` at the moment of capture. */
    val reservoirState: FloatArray,
    /** [ReadoutTrainer.toCheckpoint]'s `W_out` + RLS covariance, plus the shape needed to reconstruct a compatible trainer. */
    val readout: ReadoutCheckpoint,
    /** [PolicyEngine.weightsSnapshot]'s trained weights. */
    val policyWeights: FloatArray,
    /** [PolicyEngine.nHidden] the saved [policyWeights] were trained against - needed to reconstruct a compatible engine. */
    val policyNHidden: Int,
    /** [PolicyEngine.nBack] the saved [policyWeights] were trained against - needed to reconstruct a compatible engine. */
    val policyNBack: Int,
) {
    // FloatArray has reference equality by default. This checkpoint exists
    // specifically to be compared for bit-identical save/load round trips
    // (Prompt 7d's "matching in-memory state at the moment of the stop
    // signal" exit criterion), so equals()/hashCode() are overridden to
    // compare array *contents* - same reasoning as ReadoutCheckpoint.
    //
    // savedAtMs is deliberately excluded from both: it's diagnostic capture
    // metadata (see its own kdoc), not part of the agent's in-memory state,
    // so it has no business deciding whether two checkpoints represent the
    // same state. Including it also made this comparison spuriously
    // flaky in practice - AgentLiveSessionCheckpointSaveTest verifies a
    // stop()-persisted checkpoint against a *second*, independent
    // currentCheckpoint() call taken after the fact, and every call to
    // currentCheckpoint() stamps its own fresh System.currentTimeMillis()
    // by default, so two calls a moment apart almost never share a
    // millisecond - equals() would fail even when every real field
    // (reservoir state, W_out, covariance, policy weights) matched exactly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentCheckpoint) return false
        return reservoirState.contentEquals(other.reservoirState) &&
            readout == other.readout &&
            policyWeights.contentEquals(other.policyWeights) &&
            policyNHidden == other.policyNHidden &&
            policyNBack == other.policyNBack
    }

    override fun hashCode(): Int {
        var result = reservoirState.contentHashCode()
        result = 31 * result + readout.hashCode()
        result = 31 * result + policyWeights.contentHashCode()
        result = 31 * result + policyNHidden
        result = 31 * result + policyNBack
        return result
    }
}

interface AgentCheckpointStore {
    suspend fun load(): AgentCheckpoint?

    suspend fun save(checkpoint: AgentCheckpoint)
}

object NoopAgentCheckpointStore : AgentCheckpointStore {
    override suspend fun load(): AgentCheckpoint? = null
    override suspend fun save(checkpoint: AgentCheckpoint) = Unit
}

/**
 * Persists an [AgentCheckpoint] to a single JSON file on disk, following
 * the same convention already established by
 * [org.example.syncora.bitget.FileKlineCacheStore] and
 * [FileReadoutCheckpointStore]: plain `org.json` (no external serialization
 * dependency), every step off the main thread via [Dispatchers.IO], and an
 * atomic tmp-file-then-rename write so a process death mid-save can never
 * leave a half-written checkpoint for the next [load] to trip over.
 *
 * `W_out`, the covariance matrix, and the policy weights are stored as JSON
 * arrays of [Float.toString] strings - exactly how
 * [FileReadoutCheckpointStore] stores its own float arrays, for the same
 * exact-round-trip reason: `Float.toString`/`String.toFloat` are exact
 * inverses for every finite `Float`, so a save-then-load round trip
 * reproduces every array bit-for-bit.
 *
 * Takes the destination [file] directly (rather than only an Android
 * [Context]) so this is unit-testable on the plain JVM against a temp
 * directory, with no need to construct or mock a [Context] - same shape as
 * [FileReadoutCheckpointStore] and
 * [org.example.syncora.bitget.FileKlineCacheStore]. The [Context]-based
 * secondary constructor is what production callers use.
 */
class FileAgentCheckpointStore(
    private val file: File,
) : AgentCheckpointStore {

    constructor(context: Context, checkpointKey: String) : this(
        File(context.applicationContext.filesDir, "agent_checkpoint_$checkpointKey.json"),
    )

    private companion object {
        const val TAG = "FileAgentCheckpointStore"
    }

    override suspend fun load(): AgentCheckpoint? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val text = file.readText()
            if (text.isBlank()) return@withContext null
            val root = JSONObject(text)
            val readoutJson = root.getJSONObject("readout")
            AgentCheckpoint(
                savedAtMs = root.getLong("savedAtMs"),
                reservoirState = root.getJSONArray("reservoirState").toFloatArray(),
                readout = ReadoutCheckpoint(
                    nHidden = readoutJson.getInt("nHidden"),
                    nOutputs = readoutJson.getInt("nOutputs"),
                    includeBias = readoutJson.getBoolean("includeBias"),
                    forgettingFactor = readoutJson.getString("forgettingFactor").toFloat(),
                    wOut = readoutJson.getJSONArray("wOut").toFloatArray(),
                    covariance = readoutJson.getJSONArray("covariance").toFloatArray(),
                ),
                policyWeights = root.getJSONArray("policyWeights").toFloatArray(),
                policyNHidden = root.getInt("policyNHidden"),
                policyNBack = root.getInt("policyNBack"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load agent checkpoint from ${file.name}: ${e.message}")
            null
        }
    }

    override suspend fun save(checkpoint: AgentCheckpoint) {
        withContext(Dispatchers.IO) {
            try {
                val readoutJson = JSONObject().apply {
                    put("nHidden", checkpoint.readout.nHidden)
                    put("nOutputs", checkpoint.readout.nOutputs)
                    put("includeBias", checkpoint.readout.includeBias)
                    put("forgettingFactor", checkpoint.readout.forgettingFactor.toString())
                    put("wOut", checkpoint.readout.wOut.toJsonArray())
                    put("covariance", checkpoint.readout.covariance.toJsonArray())
                }

                val root = JSONObject()
                root.put("savedAtMs", checkpoint.savedAtMs)
                root.put("reservoirState", checkpoint.reservoirState.toJsonArray())
                root.put("readout", readoutJson)
                root.put("policyWeights", checkpoint.policyWeights.toJsonArray())
                root.put("policyNHidden", checkpoint.policyNHidden)
                root.put("policyNBack", checkpoint.policyNBack)

                val parent = file.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save agent checkpoint to ${file.name}: ${e.message}")
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
