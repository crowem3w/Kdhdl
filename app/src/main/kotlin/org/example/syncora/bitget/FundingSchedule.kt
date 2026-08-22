package org.example.syncora.bitget

import java.util.concurrent.TimeUnit

/**
 * Pure calendar math for Bitget's standard funding settlement grid - every
 * 8 hours, at 00:00, 08:00, and 16:00 (design doc §7). Deliberately
 * timezone-naive beyond that: epoch millis are already UTC, and an 8-hour
 * grid divides 24h evenly, so "00:00/08:00/16:00 UTC" and
 * "00:00/08:00/16:00 UTC+8" land on the exact same set of instants - no
 * conversion needed either way.
 *
 * `BTCUSDTP` settles on this standard interval; some other Bitget
 * contracts use a shorter one (see doc §7's caveat and
 * [BitgetFundingRateClient]'s `fundingRateInterval` field), but this app
 * only trades `BTCUSDTP`.
 */
object FundingSchedule {
    val INTERVAL_MS = TimeUnit.HOURS.toMillis(8)

    /** The most recent funding timestamp at or before [atMs]. */
    fun previousSettlement(atMs: Long): Long = atMs - Math.floorMod(atMs, INTERVAL_MS)

    /** The next funding timestamp strictly after [atMs]. */
    fun nextSettlement(atMs: Long): Long = previousSettlement(atMs) + INTERVAL_MS

    /**
     * Every funding timestamp strictly after [sinceMs] and at or before
     * [untilMs], oldest first - used to catch up on settlements that were
     * missed while the app wasn't running (see
     * [PaperTradingRepository]'s funding job). Capped at [maxCatchUp]
     * entries so a very long absence (or a fresh install with no prior
     * settlement recorded) doesn't try to replay months of history at
     * once - only the most recent [maxCatchUp] missed settlements are
     * applied.
     */
    fun settlementsBetween(sinceMs: Long, untilMs: Long, maxCatchUp: Int = 30): List<Long> {
        if (untilMs <= sinceMs) return emptyList()
        val first = previousSettlement(sinceMs) + INTERVAL_MS
        val last = previousSettlement(untilMs)
        if (last < first) return emptyList()
        val count = ((last - first) / INTERVAL_MS + 1).coerceAtMost(maxCatchUp.toLong()).toInt()
        // If more than maxCatchUp were missed, keep only the most recent
        // ones - the oldest missed settlements are dropped rather than
        // the newest, since accuracy on recent funding matters more than
        // reconstructing ancient history.
        val start = last - (count - 1) * INTERVAL_MS
        return (0 until count).map { start + it * INTERVAL_MS }
    }
}
