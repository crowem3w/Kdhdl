package org.example.test.agent

import org.example.test.bitget.BookSide
import org.example.test.bitget.PublicTrade

/**
 * A rolling read on aggressor flow (design doc §5.1 tick trades → §5.7
 * derived features): how much volume traded at the bid vs the ask over the
 * last [windowMs], and the resulting imbalance. This is the same kind of
 * short-horizon "who's hitting the tape" signal [QueuePositionTracker]
 * already leans on for fill estimation, just aggregated for the agent's
 * feature frame instead of a single price level.
 *
 * Not thread-safe internally beyond a single lock, mirroring
 * [org.example.test.bitget.KlineBuffer]'s own synchronized-deque shape -
 * callers (here, [AgentFeatureStore]) are expected to serialize access to
 * one instance.
 */
class TradeFlowAggregator(private val windowMs: Long = 60_000L) {
    private val lock = Any()
    private val trades = ArrayDeque<PublicTrade>()

    data class Snapshot(
        val buyVolume: Double,
        val sellVolume: Double,
        val tradeCount: Int,
        /** (buyVolume - sellVolume) / (buyVolume + sellVolume), in [-1, 1]. Null with zero volume. */
        val imbalance: Double?,
        val vwap: Double?,
        val windowMs: Long,
    )

    fun record(trade: PublicTrade) {
        synchronized(lock) {
            trades.addLast(trade)
            evictOldLocked(trade.timestampMs)
        }
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        synchronized(lock) {
            evictOldLocked(nowMs)
            var buyVolume = 0.0
            var sellVolume = 0.0
            var notional = 0.0
            for (t in trades) {
                if (t.side == BookSide.BID) buyVolume += t.size else sellVolume += t.size
                notional += t.price * t.size
            }
            val totalVolume = buyVolume + sellVolume
            return Snapshot(
                buyVolume = buyVolume,
                sellVolume = sellVolume,
                tradeCount = trades.size,
                imbalance = if (totalVolume > 0.0) (buyVolume - sellVolume) / totalVolume else null,
                vwap = if (totalVolume > 0.0) notional / totalVolume else null,
                windowMs = windowMs,
            )
        }
    }

    fun clear() = synchronized(lock) { trades.clear() }

    private fun evictOldLocked(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (trades.isNotEmpty() && trades.first().timestampMs < cutoff) {
            trades.removeFirst()
        }
    }
}
