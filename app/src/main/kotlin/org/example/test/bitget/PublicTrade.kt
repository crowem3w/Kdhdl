package org.example.test.bitget

/** A single public trade print off Bitget's public trade stream - no account/order info, just what traded. */
data class PublicTrade(
    val price: Double,
    val size: Double,
    val side: BookSide,
    val timestampMs: Long,
) {
    companion object {
        fun fromJson(obj: org.json.JSONObject): PublicTrade = PublicTrade(
            price = obj.getString("price").toDouble(),
            size = obj.getString("size").toDouble(),
            side = if (obj.optString("side").equals("sell", ignoreCase = true)) BookSide.ASK else BookSide.BID,
            timestampMs = obj.optString("ts", "0").toLongOrNull() ?: 0L,
        )
    }
}
