package org.example.test.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Plain snapshot of everything [MlpQLearningPolicy] needs to resume exactly where it left off. */
data class PersistedPolicyState(
    val weights: MlpQLearningPolicy.MlpWeights,
    val savedAtMs: Long,
)

/**
 * Persists the agent's learned Q-function weights to this app's private
 * on-device storage - same mechanism as [org.example.test.bitget.LocalPaperTradingStore]
 * (SharedPreferences + a JSON blob), scaled down for a tiny payload: a
 * couple hundred doubles for a 16-unit hidden layer, not an account
 * history.
 *
 * This is what makes "close the app, reopen it, the agent still knows what
 * it knew" true. Nothing here is ever sent anywhere - it's local-only, same
 * as the paper trading account.
 *
 * Format note: this replaces the old flat weights/biases shape (one
 * [OnlineQLearningPolicy] layer) with the four-array MLP shape (w1/b1/w2/b2).
 * [load] returns null on a shape mismatch from an old save rather than
 * attempting to migrate it - the caller falls back to a fresh (untrained)
 * policy in that case, same as any other restore failure.
 */
class AgentPolicyStore(context: Context) {
    private companion object {
        const val TAG = "AgentPolicyStore"
        const val PREFS_NAME = "agent_policy"
        const val KEY_STATE = "policy_state_json"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedPolicyState? {
        val json = prefs.getString(KEY_STATE, null) ?: return null
        return try {
            val root = JSONObject(json)
            if (!root.has("w1")) {
                // Old linear-policy save from before the MLP swap - not
                // migratable (different parameter shape entirely), so
                // discard it and let the caller start fresh.
                Log.i(TAG, "Discarding pre-MLP policy save (incompatible shape) - starting fresh")
                return null
            }
            val w1 = read2d(root.getJSONArray("w1"))
            val b1 = read1d(root.getJSONArray("b1"))
            val w2 = read2d(root.getJSONArray("w2"))
            val b2 = read1d(root.getJSONArray("b2"))
            PersistedPolicyState(
                weights = MlpQLearningPolicy.MlpWeights(w1 = w1, b1 = b1, w2 = w2, b2 = b2),
                savedAtMs = root.optLong("savedAtMs", 0L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted policy state, starting fresh", e)
            null
        }
    }

    fun save(weights: MlpQLearningPolicy.MlpWeights) {
        try {
            val root = JSONObject()
            root.put("w1", write2d(weights.w1))
            root.put("b1", write1d(weights.b1))
            root.put("w2", write2d(weights.w2))
            root.put("b2", write1d(weights.b2))
            root.put("savedAtMs", System.currentTimeMillis())
            prefs.edit().putString(KEY_STATE, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist policy state", e)
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun read2d(arr: JSONArray): Array<DoubleArray> =
        Array(arr.length()) { i -> read1d(arr.getJSONArray(i)) }

    private fun read1d(arr: JSONArray): DoubleArray =
        DoubleArray(arr.length()) { i -> arr.getDouble(i) }

    private fun write2d(rows: Array<DoubleArray>): JSONArray {
        val out = JSONArray()
        for (row in rows) out.put(write1d(row))
        return out
    }

    private fun write1d(values: DoubleArray): JSONArray {
        val out = JSONArray()
        for (v in values) out.put(v)
        return out
    }
}
