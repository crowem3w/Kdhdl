package org.example.test.bitget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class FileKlineCacheStore(
    context: Context,
    cacheKey: String,
) : KlineCacheStore {
    private companion object {
        const val TAG = "FileKlineCacheStore"
    }

    private val file = File(context.applicationContext.filesDir, "kline_cache_$cacheKey.json")

    override suspend fun load(): List<Kline>? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val text = file.readText()
            if (text.isBlank()) return@withContext null
            val rows = JSONArray(text)
            val candles = buildList(rows.length()) {
                for (i in 0 until rows.length()) {
                    add(Kline.fromWsJsonArray(rows.getJSONArray(i)))
                }
            }
            candles.sortedBy { it.startTime }.ifEmpty { null }
        } catch (e: Exception) {

            Log.w(TAG, "Failed to load kline cache from ${file.name}: ${e.message}")
            null
        }
    }

    override suspend fun save(candles: List<Kline>) {
        withContext(Dispatchers.IO) {
            try {
                val rows = JSONArray()
                for (c in candles) {
                    rows.put(
                        JSONArray().apply {
                            put(c.startTime.toString())
                            put(c.open.toString())
                            put(c.high.toString())
                            put(c.low.toString())
                            put(c.close.toString())
                            put(c.baseVolume.toString())
                            put(c.quoteVolume.toString())
                            put(c.usdtVolume.toString())
                        }
                    )
                }

                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(rows.toString())
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save kline cache to ${file.name}: ${e.message}")
            }
        }
    }
}
