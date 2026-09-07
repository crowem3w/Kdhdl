package org.example.syncora.bitget

data class BookWalkResult(
    val vwapPrice: Double,
    val referencePrice: Double,
    val filledSize: Double,
    val requestedSize: Double,
    val levelsConsumed: Int,
    val isFullyFilled: Boolean,
) {
    val slippage: Double get() = vwapPrice - referencePrice

    val slippagePercent: Double get() = if (referencePrice != 0.0) (slippage / referencePrice) * 100.0 else 0.0
}

object OrderBookWalker {

    fun walk(snapshot: DepthSnapshot, side: PositionSide, size: Double): BookWalkResult? {
        if (size <= 0.0) return null
        val levels = when (side) {
            PositionSide.LONG -> snapshot.asks
            PositionSide.SHORT -> snapshot.bids
        }
        val referencePrice = levels.firstOrNull()?.price ?: return null

        var remaining = size
        var notionalAccum = 0.0
        var filled = 0.0
        var levelsConsumed = 0

        for (level in levels) {
            if (remaining <= 0.0) break
            if (level.size <= 0.0) continue
            val takenAtLevel = minOf(remaining, level.size)
            notionalAccum += takenAtLevel * level.price
            filled += takenAtLevel
            remaining -= takenAtLevel
            levelsConsumed++
        }

        if (filled <= 0.0) return null

        val vwap = notionalAccum / filled
        return BookWalkResult(
            vwapPrice = vwap,
            referencePrice = referencePrice,
            filledSize = filled,
            requestedSize = size,
            levelsConsumed = levelsConsumed,
            isFullyFilled = remaining <= 1e-12,
        )
    }

    fun queueAheadVolume(snapshot: DepthSnapshot, side: PositionSide, limitPrice: Double): Double {
        val levels = when (side) {
            PositionSide.LONG -> snapshot.bids
            PositionSide.SHORT -> snapshot.asks
        }
        return levels.firstOrNull { it.price == limitPrice }?.size ?: 0.0
    }
}
