package org.example.test.bitget

/**
 * Estimates when a resting simulated limit order would actually get
 * matched on a real FIFO order book, instead of the naive "mark price
 * touched my limit price, so I'm filled" rule.
 *
 * The idea (design doc §5, final paragraph): when the order is placed, it
 * joins the back of the queue at its price level, behind whatever size is
 * already resting there ([OrderBookWalker.queueAheadVolume]). From then on,
 * every public trade print that trades *at or through* that price level
 * eats into that queue - once cumulative traded volume exceeds the size
 * that was ahead of it, the order's turn has come and it fills.
 *
 * This is a heuristic, not an exact replica of the matching engine (no
 * paper engine watching a public trade feed can be) - see the doc's §9
 * caveats - but it's materially closer to reality than "price reached my
 * limit, therefore instant fill regardless of how much volume actually
 * traded there."
 */
class QueuePositionTracker {

    private data class TrackedOrder(
        val side: PositionSide,
        val limitPrice: Double,
        val queueAheadVolume: Double,
        var tradedThroughVolume: Double = 0.0,
    )

    private val lock = Any()
    private val tracked = linkedMapOf<String, TrackedOrder>()

    /** Starts tracking [orderId], queued behind [queueAheadVolume] of resting size at [limitPrice]. */
    fun track(orderId: String, side: PositionSide, limitPrice: Double, queueAheadVolume: Double) {
        synchronized(lock) {
            tracked[orderId] = TrackedOrder(side = side, limitPrice = limitPrice, queueAheadVolume = queueAheadVolume)
        }
    }

    /** Stops tracking [orderId] - call when it's cancelled or filled through some other path. */
    fun untrack(orderId: String) {
        synchronized(lock) { tracked.remove(orderId) }
    }

    fun untrackAll() {
        synchronized(lock) { tracked.clear() }
    }

    /**
     * Feeds one public trade print to every tracked order. Returns the IDs
     * of orders whose queue has now been fully consumed and should be
     * filled (at their own limit price - a maker fill, never worse).
     */
    fun onTrade(trade: PublicTrade): List<String> {
        val nowFilled = mutableListOf<String>()
        synchronized(lock) {
            for ((orderId, order) in tracked) {
                // A LONG (buy) limit rests in the bids - it only gets eaten
                // into by trades printing at or below its price (aggressive
                // sellers hitting the bid down through that level). A SHORT
                // (sell) limit rests in the asks - only trades at or above
                // its price (aggressive buyers lifting the ask up through
                // it) count.
                val tradesThroughLevel = when (order.side) {
                    PositionSide.LONG -> trade.price <= order.limitPrice
                    PositionSide.SHORT -> trade.price >= order.limitPrice
                }
                if (!tradesThroughLevel) continue
                order.tradedThroughVolume += trade.size
                if (order.tradedThroughVolume >= order.queueAheadVolume) {
                    nowFilled.add(orderId)
                }
            }
            nowFilled.forEach { tracked.remove(it) }
        }
        return nowFilled
    }

    /** 0.0-1.0 fraction of the way through the queue - purely informational, e.g. for a "queue position" UI indicator. */
    fun progressFor(orderId: String): Double? = synchronized(lock) {
        val order = tracked[orderId] ?: return null
        if (order.queueAheadVolume <= 0.0) return 1.0
        return (order.tradedThroughVolume / order.queueAheadVolume).coerceIn(0.0, 1.0)
    }
}
