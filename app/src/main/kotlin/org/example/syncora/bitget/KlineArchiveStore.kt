package org.example.syncora.bitget

import android.util.Log
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage for [DeepHistoryBackfillJob] - deliberately its own table/entity
 * rather than a reuse of [ObjectBoxKlineCacheStore]. That store's `save()`
 * deletes the entire table and reinserts every row on every persist, which
 * is fine for the live buffer's ~4,320 rows but would make every 5-second
 * (or here, every-page) persist cost O(total archive rows) - unworkable
 * once the archive holds millions of candles.
 *
 * [appendPage] instead upserts just the rows in the page just fetched, so a
 * persist costs O(page size) no matter how large the archive has grown.
 */
class KlineArchiveStore(private val boxStore: BoxStore) {
    private companion object {
        const val TAG = "KlineArchiveStore"
    }

    private val klineBox get() = boxStore.boxFor(KlineArchiveEntity::class.java)
    private val cursorBox get() = boxStore.boxFor(ArchiveCursorEntity::class.java)

    /**
     * Upserts one page of candles for [cacheKey]. Safe to call repeatedly
     * with overlapping pages (e.g. after a resume) - rows are keyed by the
     * unique `(cacheKey, startTime)` composite, so re-fetched candles
     * replace rather than duplicate the existing row.
     */
    suspend fun appendPage(cacheKey: String, candles: List<Kline>) = withContext(Dispatchers.IO) {
        if (candles.isEmpty()) return@withContext
        try {
            klineBox.put(candles.map { it.toArchiveEntity(cacheKey) })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append archive page for '$cacheKey' (${candles.size} candles): ${e.message}")
            throw e
        }
    }

    /** Total candle count stored for [cacheKey], for progress reporting. */
    suspend fun countStored(cacheKey: String): Long = withContext(Dispatchers.IO) {
        klineBox.query(KlineArchiveEntity_.cacheKey.equal(cacheKey)).build().use { it.count() }
    }

    /** Oldest `startTime` stored for [cacheKey], or `null` if nothing is stored yet. */
    suspend fun earliestStoredStartTime(cacheKey: String): Long? = withContext(Dispatchers.IO) {
        klineBox.query(KlineArchiveEntity_.cacheKey.equal(cacheKey))
            .order(KlineArchiveEntity_.startTime)
            .build()
            .use { it.findFirst()?.startTime }
    }

    /** Newest `startTime` stored for [cacheKey], or `null` if nothing is stored yet. */
    suspend fun latestStoredStartTime(cacheKey: String): Long? = withContext(Dispatchers.IO) {
        klineBox.query(KlineArchiveEntity_.cacheKey.equal(cacheKey))
            .orderDesc(KlineArchiveEntity_.startTime)
            .build()
            .use { it.findFirst()?.startTime }
    }

    /**
     * Every archived candle for [cacheKey], oldest first. Only intended for
     * the archive CSV export path - not the live chart, which never reads
     * from this store.
     */
    suspend fun loadAll(cacheKey: String): List<Kline> = withContext(Dispatchers.IO) {
        klineBox.query(KlineArchiveEntity_.cacheKey.equal(cacheKey))
            .order(KlineArchiveEntity_.startTime)
            .build()
            .use { it.find() }
            .map { it.toKline() }
    }

    /**
     * Persists where [DeepHistoryBackfillJob] should resume from if the
     * process dies mid-walk. Deliberately a tiny separate KV row rather
     * than a column on the candle table, so writing it never touches (or
     * risks contending with) the potentially millions-of-rows candle box.
     */
    suspend fun saveResumeCursor(cacheKey: String, cursor: Long) = withContext(Dispatchers.IO) {
        try {
            cursorBox.put(ArchiveCursorEntity(cacheKey = cacheKey, cursor = cursor))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save resume cursor for '$cacheKey': ${e.message}")
        }
    }

    suspend fun loadResumeCursor(cacheKey: String): Long? = withContext(Dispatchers.IO) {
        cursorBox.query(ArchiveCursorEntity_.cacheKey.equal(cacheKey)).build().use { it.findFirst()?.cursor }
    }

    /** Clears the resume cursor - called once a walk reaches the true start of history. */
    suspend fun clearResumeCursor(cacheKey: String) = withContext(Dispatchers.IO) {
        cursorBox.query(ArchiveCursorEntity_.cacheKey.equal(cacheKey)).build().use {
            val existing = it.find()
            if (existing.isNotEmpty()) cursorBox.remove(existing)
        }
    }
}
