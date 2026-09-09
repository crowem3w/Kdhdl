package org.example.syncora.chart

import org.example.syncora.bitget.Kline
import kotlin.math.sqrt

/**
 * Bollinger Bands: a rolling simple-moving-average of closes ("middle"),
 * plus an upper/lower band offset by a multiple of the rolling standard
 * deviation of those same closes.
 *
 * The distance between the upper and lower band widens when recent price
 * action is volatile and narrows when it's calm, so plotting the bands
 * doubles as a volatility read - no separate sub-indicator required.
 */
object BollingerBands {

    data class Point(val middle: Double, val upper: Double, val lower: Double)

    const val MIN_PERIOD = 2
    const val MAX_PERIOD = 200
    const val MIN_STD_DEV_MULTIPLIER = 0.5
    const val MAX_STD_DEV_MULTIPLIER = 5.0
    const val DEFAULT_PERIOD = 20
    const val DEFAULT_STD_DEV_MULTIPLIER = 2.0

    /**
     * Returns one [Point] per candle in [candles], index-aligned with it.
     * Candles before [period] closes are available return null - an
     * under-filled average would be misleading rather than merely partial.
     *
     * Runs in O(n) via a running sum/sum-of-squares rather than
     * recomputing the window from scratch for every candle.
     */
    fun compute(candles: List<Kline>, period: Int, stdDevMultiplier: Double): List<Point?> {
        val safePeriod = period.coerceIn(MIN_PERIOD, MAX_PERIOD)
        if (candles.isEmpty()) return emptyList()

        val result = arrayOfNulls<Point>(candles.size)
        var sum = 0.0
        var sumSq = 0.0

        for (i in candles.indices) {
            val close = candles[i].close
            sum += close
            sumSq += close * close

            val dropIndex = i - safePeriod
            if (dropIndex >= 0) {
                val dropped = candles[dropIndex].close
                sum -= dropped
                sumSq -= dropped * dropped
            }

            if (i >= safePeriod - 1) {
                val mean = sum / safePeriod
                // Clamp against tiny negative values from floating-point drift.
                val variance = (sumSq / safePeriod - mean * mean).coerceAtLeast(0.0)
                val stdDev = sqrt(variance)
                result[i] = Point(
                    middle = mean,
                    upper = mean + stdDevMultiplier * stdDev,
                    lower = mean - stdDevMultiplier * stdDev,
                )
            }
        }
        return result.asList()
    }
}
