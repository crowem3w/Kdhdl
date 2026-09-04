package org.example.syncora.storage

import io.objectbox.annotation.ConflictStrategy
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * One OHLCV candle for a given symbol/granularity, persisted locally so
 * backfilled history survives process death and app restarts instead of
 * living only in the in-memory [org.example.syncora.bitget.KlineBuffer].
 *
 * [uniqueKey] ("$symbol|$granularity|$startTime") is the natural key for a
 * candle. It's marked `@Unique(onConflict = REPLACE)` so re-fetching a
 * candle we already have - an overlapping page during backfill, or the
 * still-forming latest candle being refreshed - overwrites the existing
 * row in place instead of throwing a unique-constraint violation or
 * creating a duplicate.
 */
@Entity
data class KlineEntity(
    @Id
    var id: Long = 0,

    @Unique(onConflict = ConflictStrategy.REPLACE)
    var uniqueKey: String = "",

    @Index
    var symbol: String = "",

    @Index
    var granularity: String = "",

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
        fun keyFor(symbol: String, granularity: String, startTime: Long) =
            "$symbol|$granularity|$startTime"
    }
}
