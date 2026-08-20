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
