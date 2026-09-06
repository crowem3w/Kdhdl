package org.example.syncora.rrl

import org.json.JSONArray
import org.json.JSONObject

/**
 * A full, point-in-time snapshot of an [RrlAgentLayer]'s learned and cached
 * state, suitable for persisting via [RrlCheckpointStore] and restoring on a
 * later app launch (see [RrlAgentLayer.restoreFromCheckpoint]) so that
 * online learning resumes from where it left off, instead of restarting the
 * echo-state / extended-Kalman-filter cold start of [RecurrentReinforcementLearner.reset].
 *
 * [configFingerprint] records the [RrlAgentConfig] this checkpoint was
 * produced under. The reservoir's fixed W^input/W^hidden/W^back matrices are
 * *not* persisted here -- only [RrlAgentConfig.seed] plus the rest of the
 * config is needed to regenerate them deterministically -- so this
 * checkpoint's arrays are only meaningful when reloaded into an agent with a
 * matching fingerprint. [RrlAgentLayer] checks this before restoring.
 */
data class RrlAgentCheckpoint(
    val formatVersion: Int = FORMAT_VERSION,
    val configFingerprint: String,
    /** Wall-clock time (System.currentTimeMillis()) this checkpoint was captured. */
    val savedAtMs: Long,
    /** The learner's reservoir/EKF/recurrent state; see [RrlLearnerState]. */
    val learnerState: RrlLearnerState,
    /** [FundingSettlementGate.snapshot], so funding isn't double-paid or missed across a restore. */
    val fundingLastSettlementSeen: Long,
    /** The last kline timestamp processed, used to re-arm the funding settlement gate correctly. */
    val previousTimestampMs: Long,
    /** The last observed mid-price, used to compute the next bar's Delta p_t correctly. */
    val lastMidPrice: Double?,
    /** The funding rate most recently observed from the exchange. */
    val latestFundingRate: Double,
    /** The exchange taker fee rate most recently observed (an operational input, not learned). */
    val exchangeFeeRate: Double,
    /** Cumulative performance-to-date, so the UI doesn't reset to zero across a restore. */
    val performance: RrlPerformanceSummary,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("formatVersion", formatVersion)
        put("configFingerprint", configFingerprint)
        put("savedAtMs", savedAtMs)
        put("learnerState", learnerStateToJson(learnerState))
        put("fundingLastSettlementSeen", fundingLastSettlementSeen)
        put("previousTimestampMs", previousTimestampMs)
        put("lastMidPrice", lastMidPrice)
        put("latestFundingRate", latestFundingRate)
        put("exchangeFeeRate", exchangeFeeRate)
        put("performance", performanceToJson(performance))
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun fromJson(json: JSONObject): RrlAgentCheckpoint = RrlAgentCheckpoint(
            formatVersion = json.optInt("formatVersion", FORMAT_VERSION),
            configFingerprint = json.getString("configFingerprint"),
            savedAtMs = json.getLong("savedAtMs"),
            learnerState = learnerStateFromJson(json.getJSONObject("learnerState")),
            fundingLastSettlementSeen = json.optLong("fundingLastSettlementSeen", -1L),
            previousTimestampMs = json.optLong("previousTimestampMs", -1L),
            lastMidPrice = if (json.isNull("lastMidPrice")) null else json.getDouble("lastMidPrice"),
            latestFundingRate = json.optDouble("latestFundingRate", 0.0),
            exchangeFeeRate = json.optDouble("exchangeFeeRate", 0.0005),
            performance = json.optJSONObject("performance")?.let { performanceFromJson(it) } ?: RrlPerformanceSummary(),
        )
    }
}

private fun learnerStateToJson(state: RrlLearnerState): JSONObject = JSONObject().apply {
    put("reservoirState", state.reservoirState.toJsonArray())
    put("optimizerWeights", state.optimizerWeights.toJsonArray())
    put("optimizerPrecision", state.optimizerPrecision.toJsonMatrix())
    put("pastPositions", state.pastPositions.toJsonArray())
    put("previousPosition", state.previousPosition)
    put("previousZ", state.previousZ?.toJsonArray())
    put("previousDfDw", state.previousDfDw.toJsonArray())
    put("expectedReturn", state.expectedReturn)
    put("returnVariance", state.returnVariance)
}

private fun learnerStateFromJson(json: JSONObject): RrlLearnerState = RrlLearnerState(
    reservoirState = json.getJSONArray("reservoirState").toDoubleArray(),
    optimizerWeights = json.getJSONArray("optimizerWeights").toDoubleArray(),
    optimizerPrecision = json.getJSONArray("optimizerPrecision").toDoubleMatrix(),
    pastPositions = json.getJSONArray("pastPositions").toDoubleArray(),
    previousPosition = json.getDouble("previousPosition"),
    previousZ = if (json.isNull("previousZ")) null else json.getJSONArray("previousZ").toDoubleArray(),
    previousDfDw = json.getJSONArray("previousDfDw").toDoubleArray(),
    expectedReturn = json.getDouble("expectedReturn"),
    returnVariance = json.getDouble("returnVariance"),
)

private fun performanceToJson(summary: RrlPerformanceSummary): JSONObject = JSONObject().apply {
    put("steps", summary.steps)
    put("averagePosition", summary.averagePosition)
    put("cumulativePriceReturn", summary.cumulativePriceReturn)
    put("cumulativeExecutionCost", summary.cumulativeExecutionCost)
    put("cumulativeFundingCarry", summary.cumulativeFundingCarry)
    put("cumulativeReward", summary.cumulativeReward)
    put("informationRatio", summary.informationRatio)
}

private fun performanceFromJson(json: JSONObject): RrlPerformanceSummary = RrlPerformanceSummary(
    steps = json.optInt("steps", 0),
    averagePosition = json.optDouble("averagePosition", 0.0),
    cumulativePriceReturn = json.optDouble("cumulativePriceReturn", 0.0),
    cumulativeExecutionCost = json.optDouble("cumulativeExecutionCost", 0.0),
    cumulativeFundingCarry = json.optDouble("cumulativeFundingCarry", 0.0),
    cumulativeReward = json.optDouble("cumulativeReward", 0.0),
    informationRatio = json.optDouble("informationRatio", 0.0),
)

private fun DoubleArray.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }

private fun JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }

private fun Array<DoubleArray>.toJsonMatrix(): JSONArray = JSONArray().also { array -> forEach { row -> array.put(row.toJsonArray()) } }

private fun JSONArray.toDoubleMatrix(): Array<DoubleArray> = Array(length()) { getJSONArray(it).toDoubleArray() }
