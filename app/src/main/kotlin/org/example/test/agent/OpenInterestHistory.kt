package org.example.test.agent

/**
 * Tiny ring buffer over recent (timestamp, open-interest) readings off the
 * ticker stream, so the feature frame can report *changes* in OI - a much
 * stronger regime signal than the raw level (design doc §5.1 "Open
 * interest (OI) time series", §5.7 derived datasets). Capacity is generous
 * relative to how often OI actually moves so [changePct] over a short
 * [lookbackMs] still has enough history behind it even though ticker
 * pushes arrive far more often than OI itself changes.
 */
class OpenInterestHistory(private val capacity: Int = 512) {
    private data class Reading(val timestampMs: Long, val openInterest: Double)

    private val lock = Any()
    private val readings = ArrayDeque<Reading>(capacity)

    fun record(timestampMs: Long, openInterest: Double) {
        synchronized(lock) {
            readings.addLast(Reading(timestampMs, openInterest))
            if (readings.size > capacity) readings.removeFirst()
        }
    }

    /** Percent change in OI from the oldest reading within [lookbackMs] to the latest. Null if not enough history. */
    fun changePct(lookbackMs: Long, nowMs: Long = System.currentTimeMillis()): Double? {
        synchronized(lock) {
            val latest = readings.lastOrNull() ?: return null
            val cutoff = nowMs - lookbackMs
            val baseline = readings.firstOrNull { it.timestampMs >= cutoff } ?: readings.firstOrNull() ?: return null
            if (baseline === latest || baseline.openInterest == 0.0) return null
            return (latest.openInterest - baseline.openInterest) / baseline.openInterest * 100.0
        }
    }

    fun clear() = synchronized(lock) { readings.clear() }
}
