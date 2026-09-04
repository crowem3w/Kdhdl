package org.example.syncora.bitget

enum class PositionSide {
    LONG,
    SHORT,
    ;

    val bitgetHoldSide: String
        get() = if (this == LONG) "long" else "short"

    /** The `side` value used to *open or add to* a position of this direction. */
    val openOrderSide: String
        get() = if (this == LONG) "buy" else "sell"

    /** The `side` value used to *close* a position of this direction. */
    val closeOrderSide: String
        get() = if (this == LONG) "sell" else "buy"

    companion object {
        fun fromHoldSide(holdSide: String): PositionSide =
            if (holdSide.equals("short", ignoreCase = true)) SHORT else LONG
    }
}

data class PaperPosition(
    val symbol: String,
    val side: PositionSide,
    val total: Double,
    val available: Double,
    val entryPrice: Double,
    val markPrice: Double,
    val leverage: Int,
    val marginSize: Double,
    val unrealizedPnl: Double,
    // Sum of every maker/taker fee already charged to open or add to this
    // position (see [PaperTradingRepository]'s fee simulation). Not the fee
    // for closing it - that's charged only when the position actually
    // closes, since it depends on how the close order fills.
    val feesPaidSoFar: Double = 0.0,
    // Net funding paid (positive) or received (negative) on this position
    // across every funding settlement while it's been open (design doc
    // §7, "Funding Accrual") - kept separate from [feesPaidSoFar] since
    // funding isn't a trading fee, it's a periodic cash payment between
    // longs and shorts. See [FundingPayment] for the per-settlement
    // history this total is built from.
    val fundingPaidSoFar: Double = 0.0,
) {
    val notionalValue: Double get() = total * markPrice
    val pnlPercentOfMargin: Double get() = if (marginSize != 0.0) (unrealizedPnl / marginSize) * 100.0 else 0.0
}

/**
 * Whether a simulated order removed liquidity from the book (taker) or
 * added it and waited to be matched (maker) - the classification real
 * exchanges use to decide which of their two fee rates applies.
 *
 * - Market orders are always [TAKER]: they execute immediately against
 *   whatever is resting on the book.
 * - Limit orders are [TAKER] if they cross the book the instant they're
 *   submitted (a buy limit at/above the current price, or a sell limit
 *   at/below it) - Bitget fills these immediately, same as a market order.
 * - Limit orders that don't cross rest on the book and are [MAKER] once a
 *   later price move fills them.
 */
enum class FeeClassification { MAKER, TAKER }

data class PaperAccountBalance(
    val marginCoin: String,
    val available: Double,
    val equity: Double,
    val unrealizedPnl: Double,
)

/**
 * A local, on-device paper trading account. Nothing here is backed by an
 * exchange: [id] is generated on-device, and [lastDepositAt] is the only
 * thing that gates future deposits (see [PaperTradingRepository.deposit]).
 */
data class PaperAccount(
    val id: String,
    val createdAt: Long,
    val lastDepositAt: Long?,
)

/**
 * A resting limit order that hasn't filled yet. Its margin is reserved
 * against the wallet balance (see [PaperTradingRepository.availableBalance])
 * the moment it's placed, exactly as if the position were already open, so
 * the account can't over-commit funds across several pending orders.
 */
data class PendingLimitOrder(
    val id: String,
    val side: PositionSide,
    val sizeInBaseCoin: Double,
    val leverage: Int,
    val limitPrice: Double,
    val marginReserved: Double,
    val createdAt: Long,
    // How much size was already resting at this exact price level when the
    // order was placed (see OrderBookWalker.queueAheadVolume) - the order
    // joins the back of that queue, and only fills once this much volume
    // has traded through the level (see QueuePositionTracker). 0.0 if the
    // book wasn't available at placement time, meaning it fills on the
    // very next print at/through its price.
    val queueAheadVolume: Double = 0.0,
)

/**
 * A single closed trade, recorded the moment a position is fully closed
 * (see [PaperTradingRepository.closePosition]). This is the only durable
 * trading history this app keeps - it powers the "Account History" list and
 * the "Profitable Ratio" stat shown on the Paper Trading account screen.
 */
data class ClosedPaperTrade(
    val symbol: String,
    val side: PositionSide,
    val size: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val leverage: Int,
    val realizedPnl: Double,
    val closedAt: Long,
    // Every maker/taker fee charged across this trade's life - the entry
    // fill(s) that opened/added to the position, plus the exit fill that
    // closed it. Already netted out of [realizedPnl]; kept separately too
    // so the UI can show it as its own line item (see
    // PaperTradingHistoryPanel).
    val totalFeesPaid: Double = 0.0,
    // Net funding paid (positive) or received (negative) across this
    // trade's entire life - carried over from [PaperPosition.fundingPaidSoFar]
    // the moment the position closes. Already reflected in wallet balance
    // at the time each settlement happened (see [PaperTradingRepository]),
    // not re-applied here or netted into [realizedPnl] - kept purely so the
    // UI can show it as its own line item, same treatment as [totalFeesPaid].
    val totalFundingPaid: Double = 0.0,
)

/**
 * One funding settlement actually applied to a position that was open at
 * the time (design doc §7). Recorded the moment
 * [PaperTradingRepository]'s funding job processes a settlement timestamp
 * - this is the durable funding history, the funding-side counterpart of
 * [ClosedPaperTrade].
 */
data class FundingPayment(
    val symbol: String,
    val side: PositionSide,
    val fundingRate: Double,
    val positionNotional: Double,
    // Signed from the position's point of view: positive = paid out
    // (deducted from wallet balance at settlement), negative = received
    // (added to it).
    val amount: Double,
    val settledAt: Long,
)

data class OrderTicket(
    val symbol: String,
    val side: PositionSide,
    val sizeInBaseCoin: String,
    val leverage: Int,
    val marginMode: String = "crossed",
)

data class PlacedOrder(
    val orderId: String,
    val clientOrderId: String,
    // Set for taker fills that were priced by walking live order book
    // depth (see OrderBookWalker) rather than filled flat at the mark
    // price - null for maker fills (which always fill exactly at their
    // resting limit price, never worse) and for the rare case the book
    // wasn't available and a flat-mark-price fallback fill was used.
    val fillInfo: BookWalkResult? = null,
    // Wall-clock milliseconds actually injected by latency simulation (see
    // LatencySimulator, design doc §6) before this fill's price was looked
    // up - 0 for maker fills (which fill later, off the trade stream, not
    // at placement time) and whenever latency simulation is disabled.
    val appliedLatencyMs: Long = 0L,
)

sealed class PaperTradingResult<out T> {
    data class Success<T>(val data: T) : PaperTradingResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : PaperTradingResult<Nothing>()
}
