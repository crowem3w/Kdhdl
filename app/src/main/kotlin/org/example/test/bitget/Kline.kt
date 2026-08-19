package org.example.test.bitget

data class Kline(
    val startTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val baseVolume: Double,
    val quoteVolume: Double,
    val usdtVolume: Double,
) {
    companion object {

        fun fromWsJsonArray(row: org.json.JSONArray): Kline = Kline(
            startTime = row.getString(0).toLong(),
            open = row.getString(1).toDouble(),
            high = row.getString(2).toDouble(),
            low = row.getString(3).toDouble(),
            close = row.getString(4).toDouble(),
            baseVolume = row.getString(5).toDouble(),
            quoteVolume = row.getString(6).toDouble(),
            usdtVolume = row.getString(7).toDouble(),
        )

        fun fromRestJsonArray(row: org.json.JSONArray): Kline {
            val quoteVolume = row.getString(6).toDouble()
            return Kline(
                startTime = row.getString(0).toLong(),
                open = row.getString(1).toDouble(),
                high = row.getString(2).toDouble(),
                low = row.getString(3).toDouble(),
                close = row.getString(4).toDouble(),
                baseVolume = row.getString(5).toDouble(),
                quoteVolume = quoteVolume,
                usdtVolume = quoteVolume,
            )
        }
    }
}
