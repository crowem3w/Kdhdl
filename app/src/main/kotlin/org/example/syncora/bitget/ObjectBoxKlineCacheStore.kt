package org.example.syncora.bitget

import android.util.Log
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class ObjectBoxKlineCacheStore(
    private val boxStore: BoxStore,
    private val cacheKey: String,
) : KlineCacheStore {
    private companion object {
        const val TAG = "ObjectBoxKlineCache"
    }

    private val box get() = boxStore.boxFor(CachedKlineEntity::class.java)

    override suspend fun load(): List<Kline>? = withContext(Dispatchers.IO) {
        try {
            val rows = box.query(CachedKlineEntity_.cacheKey.equal(cacheKey))
                .build()
                .use { it.find() }
            if (rows.isEmpty()) null else rows.sortedBy { it.startTime }.map { it.toKline() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load kline cache for '$cacheKey': ${e.message}")
            null
        }
    }

    override suspend fun save(candles: List<Kline>) {
        withContext(Dispatchers.IO) {
            try {
                boxStore.runInTx {
                    val existing = box.query(CachedKlineEntity_.cacheKey.equal(cacheKey))
                        .build()
                        .use { it.find() }
                    if (existing.isNotEmpty()) box.remove(existing)
                    if (candles.isNotEmpty()) {
                        box.put(candles.map { it.toCachedEntity(cacheKey) })
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save kline cache for '$cacheKey': ${e.message}")
            }
        }
    }
}
