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

/**
 * Prompt 7e's load path - the counterpart to [AgentOrchestrator.currentCheckpoint]
 * (Prompt 7d's save path). Builds a *brand-new* [AgentOrchestrator] whose
 * reservoir state, `W_out`/RLS covariance, and policy weights are restored
 * from this checkpoint rather than freshly initialized.
 *
 * [featureAssembler], [reservoirWeights], and [rewardEngine] are supplied by
 * the caller, not the checkpoint, for the same reason [ReadoutCheckpoint]
 * doesn't carry the reservoir's own weights alongside `W_out`: none of the
 * three hold any state a checkpoint would ever need to reproduce -
 * [ReservoirWeights] are fixed at construction and never trained, and
 * [FeatureAssembler]/[RewardEngine] are stateless per-bar transforms. Only
 * `x_t`, `W_out`/`P`, and the policy's trained weights are ever mutated
 * after construction ([AgentCheckpoint]'s own class doc), so those are the
 * only three components this restores.
 *
 * @throws IllegalArgumentException if [reservoirWeights]' shape doesn't
 *   match what this checkpoint was saved against - the same "a checkpoint
 *   loaded into a trainer built with a different shape would silently
 *   misinterpret the flat arrays' indexing" hazard [ReadoutCheckpoint]'s own
 *   kdoc already calls out, extended here to the reservoir/policy shapes
 *   too, rather than assuming the caller's current configuration still
 *   matches whatever was true when this checkpoint was saved. Callers that
 *   want a clean fallback instead of a crash on a stale/mismatched
 *   checkpoint should use [AgentCheckpointStore.restoreOrFreshOrchestrator],
 *   which catches exactly this.
 */
fun AgentCheckpoint.toOrchestrator(
    featureAssembler: FeatureAssembler,
    reservoirWeights: ReservoirWeights,
    rewardEngine: RewardEngine,
    policyBeta: Float = EkfWeightUpdater.DEFAULT_BETA,
    policyTau: Float = EkfWeightUpdater.DEFAULT_TAU,
    policyWeightClip: Float = PolicyEngine.DEFAULT_WEIGHT_CLIP,
): AgentOrchestrator {
    require(reservoirWeights.nHidden == reservoirState.size) {
        "reservoirWeights.nHidden ${reservoirWeights.nHidden} != checkpoint reservoirState size ${reservoirState.size}"
    }
    require(reservoirWeights.nHidden == readout.nHidden) {
        "reservoirWeights.nHidden ${reservoirWeights.nHidden} != checkpoint readout.nHidden ${readout.nHidden}"
    }
    require(reservoirWeights.nHidden == policyNHidden) {
        "reservoirWeights.nHidden ${reservoirWeights.nHidden} != checkpoint policyNHidden $policyNHidden"
    }

    val reservoir = ReservoirEngine(reservoirWeights, initialState = reservoirState)
    val readoutTrainer = readout.toTrainer()
    val policy = PolicyEngine(
        nHidden = policyNHidden,
        nBack = policyNBack,
        beta = policyBeta,
        tau = policyTau,
        weightClip = policyWeightClip,
        initialWeights = policyWeights,
    )
    return AgentOrchestrator(
        featureAssembler = featureAssembler,
        reservoir = reservoir,
        readoutTrainer = readoutTrainer,
        rewardEngine = rewardEngine,
        policyEngine = policy,
    )
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
 * Prompt 7e's "on app start" entry point: loads this store's most recent
 * checkpoint and restores it into a fresh [AgentOrchestrator] via
 * [AgentCheckpoint.toOrchestrator] - or, on any of the three ways that can
 * fail to produce a usable checkpoint, falls back to a fresh orchestrator
 * with default-initialized reservoir state, `W_out`/covariance, and policy
 * weights (the same fresh state [AgentOrchestrator]'s own constructor
 * already produces when nothing is passed in):
 *
 * 1. **No checkpoint exists** - [load] returns `null` (e.g. first-ever run).
 * 2. **The checkpoint file fails to parse** - [FileAgentCheckpointStore.load]
 *    already catches that and also returns `null` (see
 *    `AgentCheckpointStoreTest`'s corrupt-file case), so this looks
 *    identical to case 1 from here.
 * 3. **The checkpoint parses fine but doesn't match this run's
 *    configuration** - e.g. [reservoirWeights]/[policyNHidden] changed since
 *    the checkpoint was saved (a reservoir-size config change, say).
 *    [AgentCheckpoint.toOrchestrator] throws [IllegalArgumentException] for
 *    exactly this rather than silently misinterpreting the flat arrays -
 *    caught here and treated the same as a missing checkpoint, never
 *    propagated to crash the caller.
 *
 * Meant to be called once, before the first live bar of a session - the
 * agent's-state counterpart to
 * [org.example.syncora.bitget.TradingChartPipeline.start]'s "load cache,
 * then start streaming" shape.
 */
suspend fun AgentCheckpointStore.restoreOrFreshOrchestrator(
    featureAssembler: FeatureAssembler,
    reservoirWeights: ReservoirWeights,
    rewardEngine: RewardEngine,
    policyNHidden: Int = reservoirWeights.nHidden,
    policyNBack: Int = PolicyEngine.DEFAULT_N_BACK,
    policyBeta: Float = EkfWeightUpdater.DEFAULT_BETA,
    policyTau: Float = EkfWeightUpdater.DEFAULT_TAU,
    policyWeightClip: Float = PolicyEngine.DEFAULT_WEIGHT_CLIP,
): AgentOrchestrator {
    val restored = load()?.let { checkpoint ->
        try {
            checkpoint.toOrchestrator(
                featureAssembler = featureAssembler,
                reservoirWeights = reservoirWeights,
                rewardEngine = rewardEngine,
                policyBeta = policyBeta,
                policyTau = policyTau,
                policyWeightClip = policyWeightClip,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(
                "AgentCheckpointStore",
                "Checkpoint shape didn't match this run's configuration; starting fresh: ${e.message}",
            )
            null
        }
    }

    return restored ?: AgentOrchestrator(
        featureAssembler = featureAssembler,
        reservoir = ReservoirEngine(reservoirWeights),
        readoutTrainer = ReadoutTrainer(nHidden = reservoirWeights.nHidden),
        rewardEngine = rewardEngine,
        policyEngine = PolicyEngine(
            nHidden = policyNHidden,
            nBack = policyNBack,
            beta = policyBeta,
            tau = policyTau,
            weightClip = policyWeightClip,
        ),
    )
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
