package org.example.syncora.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How one [PolicyTrainingWorker][org.example.syncora.work.PolicyTrainingWorker] attempt ended -
 * mirrors [org.example.syncora.work.TrainingProgress.Stage]'s terminal states, but flattened to
 * just the outcomes a history view cares about (an in-flight run has nothing to record yet).
 */
enum class TrainingRunOutcome { PASSED, REJECTED, SKIPPED_INSUFFICIENT_DATA, SKIPPED_INSUFFICIENT_SPLITS, FAILED }

/**
 * One completed [PolicyTrainingWorker][org.example.syncora.work.PolicyTrainingWorker] attempt,
 * as kept by [TrainingRunHistoryStore]. [pboProbability]/[splitsEvaluated]/[meanOutOfSampleScore]
 * are only ever non-null for [TrainingRunOutcome.PASSED] and [TrainingRunOutcome.REJECTED] - a
 * skip or failure never reaches the CPCV/PBO gate, so there's nothing to report for those fields.
 */
data class TrainingRunRecord(
    val timestampMs: Long,
    val outcome: TrainingRunOutcome,
    val pboProbability: Double? = null,
    val splitsEvaluated: Int? = null,
    /** [ConfigPerformance.meanOutOfSampleScore] of the winning config, for a [TrainingRunOutcome.PASSED] run only - lets a trend view plot "how good was the winner," not just "did it pass." */
    val meanOutOfSampleScore: Double? = null,
    val summary: String,
)

/**
 * Small append-only ring buffer of the last [MAX_ENTRIES]
 * [PolicyTrainingWorker][org.example.syncora.work.PolicyTrainingWorker] outcomes, so the Agent
 * tab can show a trend (is PBO drifting up, are runs passing more or less often) instead of only
 * [TrainingRunStore]'s single latest-run snapshot. Same plain-`SharedPreferences`-backed-JSON
 * pattern as the rest of the `agent` package's stores - this is diagnostic history, not anything
 * a decision is made from, so there's no need for a real database here.
 */
class TrainingRunHistoryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Most recent first, capped at [limit] (which itself never exceeds [MAX_ENTRIES] worth of stored history). */
    fun recent(limit: Int = MAX_ENTRIES): List<TrainingRunRecord> = readAll().takeLast(limit).asReversed()

    /** Appends one completed run, evicting the oldest entry once [MAX_ENTRIES] is exceeded. */
    fun append(record: TrainingRunRecord) {
        val all = readAll() + record
        writeAll(if (all.size > MAX_ENTRIES) all.takeLast(MAX_ENTRIES) else all)
    }

    private fun readAll(): List<TrainingRunRecord> {
        val raw = prefs.getString(KEY_RUNS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toRecord() }
        } catch (e: Exception) {
            // Corrupt/unreadable history is purely cosmetic loss - never worth crashing a
            // training run or the UI over, just start the trend view over from empty.
            emptyList()
        }
    }

    private fun writeAll(records: List<TrainingRunRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RUNS, arr.toString()).apply()
    }

    private fun TrainingRunRecord.toJson(): JSONObject = JSONObject().apply {
        put("t", timestampMs)
        put("o", outcome.name)
        put("pbo", pboProbability ?: JSONObject.NULL)
        put("splits", splitsEvaluated ?: JSONObject.NULL)
        put("oos", meanOutOfSampleScore ?: JSONObject.NULL)
        put("summary", summary)
    }

    private fun JSONObject.toRecord(): TrainingRunRecord = TrainingRunRecord(
        timestampMs = getLong("t"),
        outcome = TrainingRunOutcome.valueOf(getString("o")),
        pboProbability = if (isNull("pbo")) null else getDouble("pbo"),
        splitsEvaluated = if (isNull("splits")) null else getInt("splits"),
        meanOutOfSampleScore = if (isNull("oos")) null else getDouble("oos"),
        summary = optString("summary", ""),
    )

    private companion object {
        const val PREFS_NAME = "training_run_history"
        const val KEY_RUNS = "runs_json"
        const val MAX_ENTRIES = 30
    }
}
