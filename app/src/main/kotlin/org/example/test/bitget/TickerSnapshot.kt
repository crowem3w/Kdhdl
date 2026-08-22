package org.example.test.bitget

/**
 * One push off Bitget's public `ticker` channel for a USDT-margined perpetual.
 *
 * This single channel is the cheapest way to get several design-doc §5.1
 * "must-have" fields in one place without opening a socket per field:
 * mark price (what liquidations actually trigger off, not last price),
 * index price (the spot-composite fair-value anchor), the live/predicted
 * funding rate, and open interest ([holdingAmount] - Bitget's field name
 * for it). [lastPrice]/[bestBid]/[bestAsk] are included too since they
 * arrive for free on every tick, but [DepthPipeline] remains the source of
 * truth for the order book itself - this is just a convenient fallback
 * when depth hasn't primed yet.
 */
data class TickerSnapshot(
    val symbol: String,
    val lastPrice: Double?,
    val markPrice: Double?,
    val indexPrice: Double?,
    val fundingRate: Double?,
    val nextFundingTimeMs: Long?,
    val openInterest: Double?,
    val bestBid: Double?,
    val bestAsk: Double?,
    val baseVolume24h: Double?,
    val quoteVolume24h: Double?,
    val timestampMs: Long,
) {
    /** (mark - index) / index, in basis points - a live proxy for futures-spot basis (design doc §5.4). */
    val basisBps: Double?
        get() {
            val mark = markPrice ?: return null
            val index = indexPrice ?: return null
            if (index == 0.0) return null
            return (mark - index) / index * 10_000.0
        }

    companion object {
        fun fromJson(obj: org.json.JSONObject): TickerSnapshot = TickerSnapshot(
            symbol = obj.optString("symbol").ifBlank { obj.optString("instId") },
            lastPrice = obj.optString("lastPr").toDoubleOrNull(),
            markPrice = obj.optString("markPrice").toDoubleOrNull(),
            indexPrice = obj.optString("indexPrice").toDoubleOrNull(),
            fundingRate = obj.optString("fundingRate").toDoubleOrNull(),
            nextFundingTimeMs = obj.optString("nextFundingTime").toLongOrNull(),
            openInterest = obj.optString("holdingAmount").toDoubleOrNull(),
            bestBid = obj.optString("bidPr").toDoubleOrNull(),
            bestAsk = obj.optString("askPr").toDoubleOrNull(),
            baseVolume24h = obj.optString("baseVolume").toDoubleOrNull(),
            quoteVolume24h = obj.optString("quoteVolume").toDoubleOrNull(),
            timestampMs = obj.optString("ts", "0").toLongOrNull() ?: System.currentTimeMillis(),
        )
    }
}
