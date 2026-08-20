package org.example.test.bitget

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
) {
    val notionalValue: Double get() = total * markPrice
    val pnlPercentOfMargin: Double get() = if (marginSize != 0.0) (unrealizedPnl / marginSize) * 100.0 else 0.0
}

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
)

sealed class PaperTradingResult<out T> {
    data class Success<T>(val data: T) : PaperTradingResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : PaperTradingResult<Nothing>()
}
