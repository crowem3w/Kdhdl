package org.example.test.bitget

data class OrderBookLevel(
    val price: Double,
    val size: Double,
) {
    companion object {

        fun fromWsJsonArray(row: org.json.JSONArray): OrderBookLevel = OrderBookLevel(
            price = row.getString(0).toDouble(),
            size = row.getString(1).toDouble(),
        )
    }
}
