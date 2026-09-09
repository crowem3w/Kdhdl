package org.example.syncora.bitget

import java.util.concurrent.TimeUnit

object FundingSchedule {
    val INTERVAL_MS = TimeUnit.HOURS.toMillis(8)

    fun previousSettlement(atMs: Long): Long = atMs - Math.floorMod(atMs, INTERVAL_MS)

    fun nextSettlement(atMs: Long): Long = previousSettlement(atMs) + INTERVAL_MS

    fun settlementsBetween(sinceMs: Long, untilMs: Long, maxCatchUp: Int = 30): List<Long> {
        if (untilMs <= sinceMs) return emptyList()
        val first = previousSettlement(sinceMs) + INTERVAL_MS
        val last = previousSettlement(untilMs)
        if (last < first) return emptyList()
        val count = ((last - first) / INTERVAL_MS + 1).coerceAtMost(maxCatchUp.toLong()).toInt()
        val start = last - (count - 1) * INTERVAL_MS
        return (0 until count).map { start + it * INTERVAL_MS }
    }
}
