package org.example.syncora.bitget

import io.objectbox.annotation.ConflictStrategy
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique



















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







@Entity
data class ArchiveCursorEntity(
    @Id
    var id: Long = 0,

    @Unique(onConflict = ConflictStrategy.REPLACE)
    var cacheKey: String = "",

    var cursor: Long = 0,
)