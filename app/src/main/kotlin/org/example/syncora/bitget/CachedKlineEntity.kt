package org.example.syncora.bitget

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index


@Entity
data class CachedKlineEntity(
    @Id
    var id: Long = 0,

    @Index
    var cacheKey: String = "",

    var startTime: Long = 0,
    var open: Double = 0.0,
    var high: Double = 0.0,
    var low: Double = 0.0,
    var close: Double = 0.0,
    var baseVolume: Double = 0.0,
    var quoteVolume: Double = 0.0,
    var usdtVolume: Double = 0.0,
)

fun Kline.toCachedEntity(cacheKey: String): CachedKlineEntity = CachedKlineEntity(
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

fun CachedKlineEntity.toKline(): Kline = Kline(
    startTime = startTime,
    open = open,
    high = high,
    low = low,
    close = close,
    baseVolume = baseVolume,
    quoteVolume = quoteVolume,
    usdtVolume = usdtVolume,
)
