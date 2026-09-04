package org.example.syncora.bitget

import android.util.Log
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ObjectBox-backed [KlineCacheStore]: persists a timeframe's candle
 * snapshot as rows in the shared on-device ObjectBox database instead of
 * one JSON file per cache key (see the old FileKlineCacheStore).
 *
 * Keeps the same contract the file store had - [save] atomically replaces
 * every row scoped to this [cacheKey] with the given snapshot, [load]
 * returns them back sorted by time (or null if nothing's cached yet) -
 * just backed by a real embedded database instead of hand-rolled JSON I/O,
 * so it gets ObjectBox's transactional writes, indexed lookups, and no
 * more full-file read/parse on every load.
 */
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
