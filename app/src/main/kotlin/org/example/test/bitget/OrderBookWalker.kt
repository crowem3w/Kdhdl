package org.example.test.bitget

/**
 * Result of walking a live order book to fill a simulated order, per the
 * "Slippage Simulation Logic (Order Book Walking)" design: instead of
 * `fill_price = mid_price * (1 ± fixed_slippage%)`, the fill price is
 * derived from how much of the order each real resting price level could
 * actually absorb.
 *
 * @property vwapPrice Volume-weighted average price across every level
 *   consumed to (attempt to) fill the order - the realistic fill price.
 * @property referencePrice Best bid/ask (top-of-book) at decision time,
 *   before any depth was consumed - the price a naive flat-slippage model
 *   would have used.
 * @property filledSize How much of the order the visible book could
 *   actually absorb. Equal to [requestedSize] unless the book is thinner
 *   than the order (see [isFullyFilled]).
 * @property requestedSize The order size that was walked against the book.
 * @property levelsConsumed How many distinct price levels were walked to
 *   build [vwapPrice] - purely informational (e.g. for surfacing "this
 *   order ate through 6 price levels" in the UI).
 * @property isFullyFilled False if the visible depth ran out before the
 *   full [requestedSize] could be matched (extremely thin book / oversized
 *   order for a paper account). Callers should decide how to handle a
 *   partial walk - e.g. fail the order rather than silently under-filling.
 */
data class BookWalkResult(
    val vwapPrice: Double,
    val referencePrice: Double,
    val filledSize: Double,
    val requestedSize: Double,
    val levelsConsumed: Int,
    val isFullyFilled: Boolean,
) {
    /** VWAP minus top-of-book reference price, signed exactly as the design doc defines it (§5.4). */
    val slippage: Double get() = vwapPrice - referencePrice

    /** [slippage] expressed as a percentage of [referencePrice], for display. */
    val slippagePercent: Double get() = if (referencePrice != 0.0) (slippage / referencePrice) * 100.0 else 0.0
}

/**
 * Walks live L2 order book depth to compute a realistic VWAP fill price for
 * a simulated market (or marketable-limit) order, instead of assuming a
 * flat percentage slippage regardless of order size or book depth.
 *
 * This is pure and stateless - it only reads the [DepthSnapshot] handed to
 * it, so callers control exactly which snapshot to walk against (e.g. one
 * fetched *after* a simulated latency delay - see the doc's §6).
 */
object OrderBookWalker {

    /**
     * Walks the book for a taker order of [side] and [size] (in base coin).
     *
     * A LONG (buy) order takes liquidity from the **ask** side, walking
     * from the best ask upward. A SHORT (sell) order takes liquidity from
     * the **bid** side, walking from the best bid downward - exactly how a
     * real market order eats through the opposing side of the book.
     *
     * Returns null if the relevant side of [snapshot] is empty (book not
     * primed yet / no connectivity) - callers should fall back to a plain
     * mark-price fill in that case rather than fail the order outright.
     */
    fun walk(snapshot: DepthSnapshot, side: PositionSide, size: Double): BookWalkResult? {
        if (size <= 0.0) return null
        val levels = when (side) {
            // Buying consumes resting sell orders (asks), cheapest first.
            PositionSide.LONG -> snapshot.asks
            // Selling consumes resting buy orders (bids), richest first.
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

    /**
     * The queue-ahead volume a new resting limit order would join behind:
     * whatever size is already resting at the *exact* price it's joining.
     * Real matching engines are (mostly) FIFO within a price level, so a
     * freshly placed order goes to the back of that level's queue - see
     * [QueuePositionTracker] for how that queue is then drained by the
     * public trade stream.
     */
    fun queueAheadVolume(snapshot: DepthSnapshot, side: PositionSide, limitPrice: Double): Double {
        val levels = when (side) {
            PositionSide.LONG -> snapshot.bids
            PositionSide.SHORT -> snapshot.asks
        }
        return levels.firstOrNull { it.price == limitPrice }?.size ?: 0.0
    }
}
