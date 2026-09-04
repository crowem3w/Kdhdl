package org.example.syncora.bitget

import android.content.Context
import io.objectbox.Box
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Aggregate stats over the locally archived 1m OHLCV history - see [Ohlcv1mArchiveStore.stats]. */
data class Ohlcv1mArchiveStats(
    val candleCount: Int,
    /** The newest candle currently stored, or null if the archive is empty. */
    val latest: Kline?,
    /** The oldest candle currently stored, or null if the archive is empty. */
    val deepest: Kline?,
)

/**
 * Owns the on-device, append-only archive of 1-minute OHLCV candles.
 *
 * This is deliberately separate from [KlineCacheStore]: that store is a
 * rolling snapshot ([TradingChartPipeline]'s in-memory buffer, capped at
 * `bufferCapacity` candles) used only to paint the chart instantly on cold
 * start. This archive is never trimmed - every 1m candle this device has
 * observed while recording stays here, keyed by [Kline.startTime], so the
 * "deepest historical candle" keeps getting older the longer the app has
 * been in use. Nothing here is ever sent anywhere; see [Ohlcv1mCsvExporter]
 * for the only way data leaves this store (an on-device CSV file the user
 * chooses to export).
 */
class Ohlcv1mArchiveStore(context: Context) {

    private val box: Box<Ohlcv1mCandleEntity> =
        ObjectBoxStore.get(context.applicationContext).boxFor(Ohlcv1mCandleEntity::class.java)

    /**
     * Upserts [candles] into the archive. A candle already stored for the
     * same [Kline.startTime] is overwritten in place (see the entity's
     * unique, replace-on-conflict `startTime` index) - this is what lets a
     * still-forming 1m bar keep refreshing to its latest close without
     * piling up duplicate rows.
     */
    suspend fun record(candles: List<Kline>) = withContext(Dispatchers.IO) {
        if (candles.isEmpty()) return@withContext
        box.put(candles.map { it.toEntity() })
    }

    suspend fun stats(): Ohlcv1mArchiveStats = withContext(Dispatchers.IO) {
        val count = box.count().toInt()
        if (count == 0) return@withContext Ohlcv1mArchiveStats(0, null, null)

        val latest = box.query()
            .order(Ohlcv1mCandleEntity_.startTime, QueryBuilder.DESCENDING)
            .build().use { it.findFirst() }
        val deepest = box.query()
            .order(Ohlcv1mCandleEntity_.startTime)
            .build().use { it.findFirst() }

        Ohlcv1mArchiveStats(
            candleCount = count,
            latest = latest?.toKline(),
            deepest = deepest?.toKline(),
        )
    }

    /** Every archived candle, oldest first - the shape a CSV export needs. */
    suspend fun exportAllOrderedByTime(): List<Kline> = withContext(Dispatchers.IO) {
        box.query()
            .order(Ohlcv1mCandleEntity_.startTime)
            .build().use { it.find() }
            .map { it.toKline() }
    }
}

private fun Kline.toEntity() = Ohlcv1mCandleEntity(
    startTime = startTime,
    open = open,
    high = high,
    low = low,
    close = close,
    baseVolume = baseVolume,
    quoteVolume = quoteVolume,
    usdtVolume = usdtVolume,
)

private fun Ohlcv1mCandleEntity.toKline() = Kline(
    startTime = startTime,
    open = open,
    high = high,
    low = low,
    close = close,
    baseVolume = baseVolume,
    quoteVolume = quoteVolume,
    usdtVolume = usdtVolume,
)
