package org.example.syncora.bitget

enum class PositionSide {
    LONG,
    SHORT,
    ;

    val bitgetHoldSide: String
        get() = if (this == LONG) "long" else "short"

    
    val openOrderSide: String
        get() = if (this == LONG) "buy" else "sell"

    
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
    
    
    
    
    val feesPaidSoFar: Double = 0.0,
    
    
    
    
    
    
    val fundingPaidSoFar: Double = 0.0,
) {
    val notionalValue: Double get() = total * markPrice
    val pnlPercentOfMargin: Double get() = if (marginSize != 0.0) (unrealizedPnl / marginSize) * 100.0 else 0.0
}














enum class FeeClassification { MAKER, TAKER }

data class PaperAccountBalance(
    val marginCoin: String,
    val available: Double,
    val equity: Double,
    val unrealizedPnl: Double,
)






data class PaperAccount(
    val id: String,
    val createdAt: Long,
    val lastDepositAt: Long?,
)







data class PendingLimitOrder(
    val id: String,
    val side: PositionSide,
    val sizeInBaseCoin: Double,
    val leverage: Int,
    val limitPrice: Double,
    val marginReserved: Double,
    val createdAt: Long,
    
    
    
    
    
    
    val queueAheadVolume: Double = 0.0,
)







data class ClosedPaperTrade(
    val symbol: String,
    val side: PositionSide,
    val size: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val leverage: Int,
    val realizedPnl: Double,
    val closedAt: Long,
    
    
    
    
    
    val totalFeesPaid: Double = 0.0,
    
    
    
    
    
    
    val totalFundingPaid: Double = 0.0,
)








data class FundingPayment(
    val symbol: String,
    val side: PositionSide,
    val fundingRate: Double,
    val positionNotional: Double,
    
    
    
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
    
    
    
    
    
    val fillInfo: BookWalkResult? = null,
    
    
    
    
    val appliedLatencyMs: Long = 0L,
)

sealed class PaperTradingResult<out T> {
    data class Success<T>(val data: T) : PaperTradingResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : PaperTradingResult<Nothing>()
}