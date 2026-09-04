package org.example.syncora.storage

import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.syncora.bitget.Kline

/**
 * ObjectBox-backed persistence for OHLCV candles, keyed by (symbol,
 * granularity, startTime). Used by
 * [org.example.syncora.bitget.KlineBackfillManager] to write backfilled
 * pages as they arrive and to figure out where a backfill left off, so
 * resuming after the app is killed or the network drops doesn't
 * re-download candles already saved on disk.
 */
class ObjectBoxKlineStore(
    boxStore: BoxStore = ObjectBoxStore.boxStore,
) {
    private val box = boxStore.boxFor(KlineEntity::class.java)

    /** Inserts or replaces [candles] in a single transaction. */
    suspend fun upsertAll(symbol: String, granularity: String, candles: List<Kline>) {
        if (candles.isEmpty()) return
        withContext(Dispatchers.IO) {
            val entities = candles.map { k ->
                KlineEntity(
                    uniqueKey = KlineEntity.keyFor(symbol, granularity, k.startTime),
                    symbol = symbol,
                    granularity = granularity,
                    startTime = k.startTime,
                    open = k.open,
                    high = k.high,
                    low = k.low,
                    close = k.close,
                    baseVolume = k.baseVolume,
                    quoteVolume = k.quoteVolume,
                    usdtVolume = k.usdtVolume,
                )
            }
            // One put() call = one transaction for the whole page.
            // @Unique(onConflict = REPLACE) on uniqueKey means any candle we
            // already have gets overwritten in place, not duplicated.
            box.put(entities)
        }
    }

    /** Oldest stored candle's startTime for this symbol/granularity, or null if nothing stored yet. */
    suspend fun earliestStartTime(symbol: String, granularity: String): Long? =
        withContext(Dispatchers.IO) {
            box.query(scopedTo(symbol, granularity))
                .order(KlineEntity_.startTime)
                .build()
                .use { it.find(0, 1).firstOrNull()?.startTime }
        }

    /** Newest stored candle's startTime for this symbol/granularity, or null if nothing stored yet. */
    suspend fun latestStartTime(symbol: String, granularity: String): Long? =
        withContext(Dispatchers.IO) {
            box.query(scopedTo(symbol, granularity))
                .orderDesc(KlineEntity_.startTime)
                .build()
                .use { it.find(0, 1).firstOrNull()?.startTime }
        }

    suspend fun count(symbol: String, granularity: String): Long =
        withContext(Dispatchers.IO) {
            box.query(scopedTo(symbol, granularity)).build().use { it.count() }
        }

    /** Candles between [fromMs] and [toMs] inclusive, ascending by time. */
    suspend fun range(symbol: String, granularity: String, fromMs: Long, toMs: Long): List<Kline> =
        withContext(Dispatchers.IO) {
            box.query(scopedTo(symbol, granularity).and(KlineEntity_.startTime.between(fromMs, toMs)))
                .order(KlineEntity_.startTime)
                .build()
                .use { query -> query.find().map { it.toKline() } }
        }

    private fun scopedTo(symbol: String, granularity: String) =
        KlineEntity_.symbol.equal(symbol).and(KlineEntity_.granularity.equal(granularity))

    private fun KlineEntity.toKline() = Kline(
        startTime = startTime,
        open = open,
        high = high,
        low = low,
        close = close,
        baseVolume = baseVolume,
        quoteVolume = quoteVolume,
        usdtVolume = usdtVolume,
    )
}
