package org.example.syncora.bitget

/**
 * The minimal surface [StopLossGuard] needs from the exchange to place,
 * inspect, and cancel the resting stop-loss trigger order that protects a
 * live position independently of this app's own process (design doc
 * §2.2/§5, `docs/agent-design-contract.md` §2).
 *
 * Extracted from [BitgetTradingRestClient] (which implements this
 * interface directly, unchanged) so [StopLossGuard] depends on this small
 * contract rather than the full REST client - the same reasoning
 * [org.example.syncora.agent.PaperOrderSink] already applies to
 * [org.example.syncora.agent.PositionOrderEmitter]. This is what makes
 * Prompt 8b's exchange-stop-independence integration test possible without
 * a real network call: a test can supply a fake [StopLossOrderClient] that
 * records what it was asked to place, with no Bitget credentials, HTTP
 * client, or Android [android.content.Context] involved at all.
 */
interface StopLossOrderClient {
    /** See [BitgetTradingRestClient.placeStopLoss]. */
    suspend fun placeStopLoss(
        symbol: String,
        holdSide: PositionSide,
        triggerPrice: String,
        sizeInBaseCoin: String,
    ): String

    /** See [BitgetTradingRestClient.fetchPendingStopLossOrders]. */
    suspend fun fetchPendingStopLossOrders(symbol: String): List<StopLossOrder>

    /** See [BitgetTradingRestClient.cancelStopLoss]. */
    suspend fun cancelStopLoss(symbol: String, orderId: String)
}
