package org.example.test.bitget

enum class Timeframe(

    val label: String,
    val wsChannel: String,
    val restParam: String,
) {
    ONE_MINUTE(label = "1m", wsChannel = "candle1m", restParam = "1m"),
    FIVE_MINUTES(label = "5m", wsChannel = "candle5m", restParam = "5m"),
    FIFTEEN_MINUTES(label = "15m", wsChannel = "candle15m", restParam = "15m"),
    THIRTY_MINUTES(label = "30m", wsChannel = "candle30m", restParam = "30m"),
    ONE_HOUR(label = "1h", wsChannel = "candle1H", restParam = "1H"),
    ;

    val durationMillis: Long = Granularity.toMillis(wsChannel)

    companion object {
        val DEFAULT = ONE_MINUTE
    }
}
