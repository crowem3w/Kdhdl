package org.example.syncora.bitget



















class QueuePositionTracker {

    private data class TrackedOrder(
        val side: PositionSide,
        val limitPrice: Double,
        val queueAheadVolume: Double,
        var tradedThroughVolume: Double = 0.0,
    )

    private val lock = Any()
    private val tracked = linkedMapOf<String, TrackedOrder>()

    
    fun track(orderId: String, side: PositionSide, limitPrice: Double, queueAheadVolume: Double) {
        synchronized(lock) {
            tracked[orderId] = TrackedOrder(side = side, limitPrice = limitPrice, queueAheadVolume = queueAheadVolume)
        }
    }

    
    fun untrack(orderId: String) {
        synchronized(lock) { tracked.remove(orderId) }
    }

    fun untrackAll() {
        synchronized(lock) { tracked.clear() }
    }

    




    fun onTrade(trade: PublicTrade): List<String> {
        val nowFilled = mutableListOf<String>()
        synchronized(lock) {
            for ((orderId, order) in tracked) {
                
                
                
                
                
                
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

    
    fun progressFor(orderId: String): Double? = synchronized(lock) {
        val order = tracked[orderId] ?: return null
        if (order.queueAheadVolume <= 0.0) return 1.0
        return (order.tradedThroughVolume / order.queueAheadVolume).coerceIn(0.0, 1.0)
    }
}