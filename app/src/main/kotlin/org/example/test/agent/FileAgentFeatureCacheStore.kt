package org.example.test.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk [AgentFeatureCacheStore], same "write to a temp file then
 * rename" durability trick as
 * [org.example.test.bitget.FileKlineCacheStore]: a crash mid-write leaves
 * the previous good file intact instead of a half-written, corrupt one.
 */
class FileAgentFeatureCacheStore(
    context: Context,
    cacheKey: String,
) : AgentFeatureCacheStore {
    private companion object {
        const val TAG = "FileAgentFeatureCache"
    }

    private val file = File(context.applicationContext.filesDir, "agent_feature_cache_$cacheKey.json")

    override suspend fun load(): List<MarketFeatureFrame>? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val text = file.readText()
            if (text.isBlank()) return@withContext null
            val rows = JSONArray(text)
            val frames = buildList(rows.length()) {
                for (i in 0 until rows.length()) {
                    frameFromJson(rows.getJSONObject(i))?.let { add(it) }
                }
            }
            frames.sortedBy { it.timestampMs }.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load feature cache from ${file.name}: ${e.message}")
            null
        }
    }

    override suspend fun save(frames: List<MarketFeatureFrame>) {
        withContext(Dispatchers.IO) {
            try {
                val rows = JSONArray()
                for (frame in frames) rows.put(frameToJson(frame))

                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(rows.toString())
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save feature cache to ${file.name}: ${e.message}")
            }
        }
    }

    private fun frameToJson(frame: MarketFeatureFrame): JSONObject = JSONObject().apply {
        put("ts", frame.timestampMs)
        putOpt("lastPrice", frame.lastPrice)
        putOpt("markPrice", frame.markPrice)
        putOpt("indexPrice", frame.indexPrice)
        putOpt("basisBps", frame.basisBps)
        putOpt("bestBid", frame.bestBid)
        putOpt("bestAsk", frame.bestAsk)
        putOpt("midPrice", frame.midPrice)
        putOpt("spreadBps", frame.spreadBps)
        putOpt("orderBookImbalance", frame.orderBookImbalance)
        putOpt("openInterest", frame.openInterest)
        putOpt("openInterestChangePct15m", frame.openInterestChangePct15m)
        putOpt("fundingRate", frame.fundingRate)
        putOpt("nextFundingTimeMs", frame.nextFundingTimeMs)
        frame.tradeFlow?.let { tf ->
            put(
                "tradeFlow",
                JSONObject().apply {
                    put("buyVolume", tf.buyVolume)
                    put("sellVolume", tf.sellVolume)
                    put("tradeCount", tf.tradeCount)
                    putOpt("imbalance", tf.imbalance)
                    putOpt("vwap", tf.vwap)
                    put("windowMs", tf.windowMs)
                },
            )
        }
        putOpt("realizedVol5m", frame.realizedVol5m)
        putOpt("realizedVol1h", frame.realizedVol1h)
        put("klineBarCount", frame.klineBarCount)
    }

    private fun frameFromJson(obj: JSONObject): MarketFeatureFrame? {
        val timestampMs = obj.optLong("ts", -1L)
        if (timestampMs < 0L) return null
        val tradeFlow = obj.optJSONObject("tradeFlow")?.let { tf ->
            TradeFlowAggregator.Snapshot(
                buyVolume = tf.optDouble("buyVolume", 0.0),
                sellVolume = tf.optDouble("sellVolume", 0.0),
                tradeCount = tf.optInt("tradeCount", 0),
                imbalance = tf.optDoubleOrNull("imbalance"),
                vwap = tf.optDoubleOrNull("vwap"),
                windowMs = tf.optLong("windowMs", 60_000L),
            )
        }
        return MarketFeatureFrame(
            timestampMs = timestampMs,
            lastPrice = obj.optDoubleOrNull("lastPrice"),
            markPrice = obj.optDoubleOrNull("markPrice"),
            indexPrice = obj.optDoubleOrNull("indexPrice"),
            basisBps = obj.optDoubleOrNull("basisBps"),
            bestBid = obj.optDoubleOrNull("bestBid"),
            bestAsk = obj.optDoubleOrNull("bestAsk"),
            midPrice = obj.optDoubleOrNull("midPrice"),
            spreadBps = obj.optDoubleOrNull("spreadBps"),
            orderBookImbalance = obj.optDoubleOrNull("orderBookImbalance"),
            openInterest = obj.optDoubleOrNull("openInterest"),
            openInterestChangePct15m = obj.optDoubleOrNull("openInterestChangePct15m"),
            fundingRate = obj.optDoubleOrNull("fundingRate"),
            nextFundingTimeMs = if (obj.has("nextFundingTimeMs")) obj.optLong("nextFundingTimeMs") else null,
            tradeFlow = tradeFlow,
            realizedVol5m = obj.optDoubleOrNull("realizedVol5m"),
            realizedVol1h = obj.optDoubleOrNull("realizedVol1h"),
            klineBarCount = obj.optInt("klineBarCount", 0),
            // Reloaded frames are, by definition, not live - a fresh quality
            // reading gets attached once the ingestion service is running
            // again and starts emitting new snapshots.
            quality = MarketFeatureFrame.DataQuality(
                klineAgeMs = null,
                depthAgeMs = null,
                tickerAgeMs = null,
                staleThresholdMs = 10_000L,
            ),
        )
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null
}
