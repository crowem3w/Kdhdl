package org.example.syncora.bitget











data class StopLossOrder(
    val orderId: String,
    val symbol: String,
    val holdSide: PositionSide,
    val triggerPrice: Double,
    val sizeInBaseCoin: Double,
)