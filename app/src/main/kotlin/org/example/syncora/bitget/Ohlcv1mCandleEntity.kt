package org.example.syncora.bitget

import io.objectbox.annotation.ConflictStrategy
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * On-device row for one archived 1-minute OHLCV candle - see
 * [Ohlcv1mArchiveStore], which is the only thing that should read or write
 * this entity.
 *
 * [startTime] (the bar-open timestamp, epoch millis - same field as
 * [Kline.startTime]) is the archive's natural key. It's indexed and marked
 * `@Unique(onConflict = REPLACE)` so re-recording the same bar - e.g. the
 * repeated close updates of a still-forming 1m candle - overwrites the
 * existing row in place instead of piling up duplicates.
 */
@Entity
data class Ohlcv1mCandleEntity(
    @Id var id: Long = 0,
    @Index @Unique(onConflict = ConflictStrategy.REPLACE) var startTime: Long = 0,
    var open: Double = 0.0,
    var high: Double = 0.0,
    var low: Double = 0.0,
    var close: Double = 0.0,
    var baseVolume: Double = 0.0,
    var quoteVolume: Double = 0.0,
    var usdtVolume: Double = 0.0,
)
