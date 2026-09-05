package org.example.syncora.bitget

/**
 * A resting "position TP/SL" trigger order on Bitget's own book - the
 * exchange-side dead-man's-switch described in the redesign doc (§2.2/§5).
 *
 * Unlike a normal limit/market order, this doesn't depend on the app process
 * being alive to fire: Bitget's matching engine itself watches [triggerPrice]
 * against the live mark price and executes the close once it's crossed, so
 * the position stays protected even if [org.example.syncora.service.MarketDataForegroundService]
 * is killed by the OS.
 */
data class StopLossOrder(
    val orderId: String,
    val symbol: String,
    val holdSide: PositionSide,
    val triggerPrice: Double,
    val sizeInBaseCoin: Double,
)
