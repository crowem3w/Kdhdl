package org.example.test.bitget

data class DepthUpdate(
    val action: String,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
    val checksum: Long,
    val seq: Long,
    val timestampMs: Long,
) {
    val isSnapshot: Boolean get() = action == "snapshot"

    companion object {

        fun fromJson(obj: org.json.JSONObject, action: String): DepthUpdate {
            val bidsArr = obj.getJSONArray("bids")
            val asksArr = obj.getJSONArray("asks")
            return DepthUpdate(
                action = action,
                bids = buildList(bidsArr.length()) {
                    for (i in 0 until bidsArr.length()) add(OrderBookLevel.fromWsJsonArray(bidsArr.getJSONArray(i)))
                },
                asks = buildList(asksArr.length()) {
                    for (i in 0 until asksArr.length()) add(OrderBookLevel.fromWsJsonArray(asksArr.getJSONArray(i)))
                },

                checksum = obj.optString("checksum", "0").toLongOrNull() ?: 0L,
                seq = obj.optLong("seq", -1L),
                timestampMs = obj.optString("ts", "0").toLongOrNull() ?: 0L,
            )
        }
    }
}
