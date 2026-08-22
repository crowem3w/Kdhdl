package org.example.test.agent

import org.example.test.bitget.Kline
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Realized volatility from a rolling window of kline closes - design doc
 * §5.7 "Derived / Computed Datasets": "Realized volatility at multiple
 * windows (5m, 1h, 1d)". Pure math, no I/O, so it's trivially unit
 * testable and reusable regardless of which timeframe the caller feeds it.
 */
object RealizedVolatility {

    /**
     * Standard deviation of consecutive log returns over the last [bars]
     * candles in [closesOldestFirst], annualized by [barsPerYear] (default
     * assumes 1-minute bars: 60 * 24 * 365). Returns null when there
     * aren't enough closes yet to form at least 2 returns - the honest
     * answer during cold start is "unknown", not a misleadingly precise 0.
     */
    fun annualized(
        closesOldestFirst: List<Double>,
        bars: Int,
        barsPerYear: Double = 60.0 * 24.0 * 365.0,
    ): Double? {
        val window = closesOldestFirst.takeLast(bars + 1)
        if (window.size < 3) return null

        val logReturns = ArrayList<Double>(window.size - 1)
        for (i in 1 until window.size) {
            val prev = window[i - 1]
            val curr = window[i]
            if (prev <= 0.0 || curr <= 0.0) continue
            logReturns.add(ln(curr / prev))
        }
        if (logReturns.size < 2) return null

        val mean = logReturns.sum() / logReturns.size
        val variance = logReturns.sumOf { (it - mean) * (it - mean) } / (logReturns.size - 1)
        return sqrt(variance) * sqrt(barsPerYear)
    }

    /** Convenience overload operating directly on [Kline.close]. */
    fun annualizedFromKlines(
        klinesOldestFirst: List<Kline>,
        bars: Int,
        barsPerYear: Double = 60.0 * 24.0 * 365.0,
    ): Double? = annualized(klinesOldestFirst.map { it.close }, bars, barsPerYear)
}
