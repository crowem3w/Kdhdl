package org.example.test.bitget

import kotlin.math.max

class LiquidityZoneTracker {
    private val lock = Any()
    private val active = HashMap<ZoneKey, MutableZoneState>()

    private var runningMaxVolume = 0.0
    private val maxVolumeDecayPerUpdate = 0.995

    private data class ZoneKey(val side: BookSide, val price: Double)
    private class MutableZoneState(var volume: Double, val firstSeenMs: Long, var lastUpdateMs: Long)

    fun update(snapshot: DepthSnapshot, nowMs: Long): List<LiquidityZone> = synchronized(lock) {
        var sampleMax = 0.0
        for (level in snapshot.bids) if (level.size > sampleMax) sampleMax = level.size
        for (level in snapshot.asks) if (level.size > sampleMax) sampleMax = level.size
        runningMaxVolume = max(runningMaxVolume * maxVolumeDecayPerUpdate, sampleMax)
        val maxVol = if (runningMaxVolume > 0.0) runningMaxVolume else 1.0

        val seenThisUpdate = HashSet<ZoneKey>(snapshot.bids.size + snapshot.asks.size)
        val result = ArrayList<LiquidityZone>(snapshot.bids.size + snapshot.asks.size)

        reconcileSide(BookSide.BID, snapshot.bids, nowMs, maxVol, seenThisUpdate, result)
        reconcileSide(BookSide.ASK, snapshot.asks, nowMs, maxVol, seenThisUpdate, result)

        active.keys.retainAll(seenThisUpdate)

        result
    }

    private fun reconcileSide(
        side: BookSide,
        levels: List<DepthLevel>,
        nowMs: Long,
        maxVol: Double,
        seenThisUpdate: MutableSet<ZoneKey>,
        result: MutableList<LiquidityZone>,
    ) {
        for (level in levels) {
            if (level.size <= 0.0) continue
            val key = ZoneKey(side, level.price)
            seenThisUpdate.add(key)

            val state = active.getOrPut(key) { MutableZoneState(level.size, firstSeenMs = nowMs, lastUpdateMs = nowMs) }
            state.volume = level.size
            state.lastUpdateMs = nowMs

            result.add(
                LiquidityZone(
                    price = level.price,
                    side = side,
                    volume = state.volume,
                    intensity = (level.size / maxVol).toFloat().coerceIn(0f, 1f),
                    firstSeenMs = state.firstSeenMs,
                    lastUpdateMs = state.lastUpdateMs,
                ),
            )
        }
    }

    fun reset() = synchronized(lock) {
        active.clear()
        runningMaxVolume = 0.0
    }
}
