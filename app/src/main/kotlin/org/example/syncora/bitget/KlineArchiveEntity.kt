package org.example.syncora.bitget

import io.objectbox.annotation.ConflictStrategy
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * Storage row for the deep-history archive - deliberately a separate
 * `@Entity` from [CachedKlineEntity] rather than a bigger version of it.
 *
 * [CachedKlineEntity] / [ObjectBoxKlineCacheStore] are sized for the live
 * chart's rolling buffer (a few thousand rows) and its `save()` deletes the
 * whole table and reinserts everything on every persist - fine at that
 * scale, unusable once the table holds millions of 1m candles going back to
 * 2019. This entity's store ([KlineArchiveStore]) instead upserts one page
 * at a time, so a persist costs O(page size), not O(total rows).
 *
 * ObjectBox doesn't support a composite unique index across two properties,
 * so [uniqueKey] packs `(cacheKey, startTime)` into a single indexed,
 * `@Unique` string. Combined with [ConflictStrategy.REPLACE], calling
 * `box.put(entity)` with `id = 0` still upserts correctly on re-fetch or
 * resume overlap - ObjectBox looks up the existing row by [uniqueKey] and
 * replaces it - instead of inserting a duplicate row every time.
 */
@Entity
data class KlineArchiveEntity(
    @Id
    var id: Long = 0,

    @Unique(onConflict = ConflictStrategy.REPLACE)
    var uniqueKey: String = "",

    @Index
    var cacheKey: String = "",

    @Index
    var startTime: Long = 0,

    var open: Double = 0.0,
    var high: Double = 0.0,
    var low: Double = 0.0,
    var close: Double = 0.0,
    var baseVolume: Double = 0.0,
    var quoteVolume: Double = 0.0,
    var usdtVolume: Double = 0.0,
) {
    companion object {
        fun uniqueKeyFor(cacheKey: String, startTime: Long): String = "$cacheKey|$startTime"
    }
}

fun Kline.toArchiveEntity(cacheKey: String): KlineArchiveEntity = KlineArchiveEntity(
    uniqueKey = KlineArchiveEntity.uniqueKeyFor(cacheKey, startTime),
    cacheKey = cacheKey,
    startTime = startTime,
    open = open,
    high = high,
    low = low,
    close = close,
    baseVolume = baseVolume,
    quoteVolume = quoteVolume,
    usdtVolume = usdtVolume,
)

fun KlineArchiveEntity.toKline(): Kline = Kline(
    startTime = startTime,
    open = open,
    high = high,
    low = low,
    close = close,
    baseVolume = baseVolume,
    quoteVolume = quoteVolume,
    usdtVolume = usdtVolume,
)

/**
 * Tiny standalone KV row for the resume cursor - kept as its own entity
 * rather than a column on [KlineArchiveEntity] so reading/writing "where did
 * we leave off" never touches the (potentially millions-of-rows) candle
 * table.
 */
@Entity
data class ArchiveCursorEntity(
    @Id
    var id: Long = 0,

    @Unique(onConflict = ConflictStrategy.REPLACE)
    var cacheKey: String = "",

    var cursor: Long = 0,
)
