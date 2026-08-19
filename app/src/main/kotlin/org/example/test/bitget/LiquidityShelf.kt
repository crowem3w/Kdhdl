package org.example.test.bitget

data class LiquidityShelf(
    val side: BookSide,
    val minPrice: Double,
    val maxPrice: Double,

    val totalVolume: Double,

    val peakIntensity: Float,

    val levelCount: Int,

    val firstSeenMs: Long,

    val distanceFraction: Double,

    val proximityWeight: Double,
) {

    val centerPrice: Double get() = (minPrice + maxPrice) / 2.0

    val priorityScore: Double get() = totalVolume * proximityWeight
}
