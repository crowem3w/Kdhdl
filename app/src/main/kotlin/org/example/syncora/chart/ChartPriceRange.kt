package org.example.syncora.chart

import org.example.syncora.bitget.Kline

data class ChartPriceRange(val minPrice: Double, val maxPrice: Double) {
    companion object {
        private const val PADDING_FRACTION = 0.08

        fun from(candles: List<Kline>, bollingerPoints: List<BollingerBands.Point?>? = null): ChartPriceRange? {
            if (candles.isEmpty()) return null

            var minPrice = Double.MAX_VALUE
            var maxPrice = -Double.MAX_VALUE
            for (c in candles) {
                if (c.low < minPrice) minPrice = c.low
                if (c.high > maxPrice) maxPrice = c.high
            }
            bollingerPoints?.forEach { point ->
                if (point != null) {
                    if (point.upper > maxPrice) maxPrice = point.upper
                    if (point.lower < minPrice) minPrice = point.lower
                }
            }
            if (minPrice == maxPrice) {

                minPrice -= 1.0
                maxPrice += 1.0
            }
            val padding = (maxPrice - minPrice) * PADDING_FRACTION
            return ChartPriceRange(minPrice - padding, maxPrice + padding)
        }
    }
}
