package org.example.test.bitget

object LiquidityShelfMerger {

    private const val nearIntensityThreshold = 0.35f

    private const val farIntensityThreshold = 0.15f
    private const val defaultMaxGapFraction = 0.0006

    private const val nearPriceRampFraction = 0.01

    fun merge(
        zones: List<LiquidityZone>,
        referencePrice: Double,
        maxGapFraction: Double = defaultMaxGapFraction,
    ): List<LiquidityShelf> {
        if (zones.isEmpty() || referencePrice <= 0.0) return emptyList()
        val maxGap = referencePrice * maxGapFraction
        val shelves = ArrayList<LiquidityShelf>()

        for (side in BookSide.values()) {
            val significant = zones
                .asSequence()
                .filter { it.side == side && it.intensity >= requiredIntensity(it.price, referencePrice) }
                .sortedBy { it.price }
                .toList()
            if (significant.isEmpty()) continue

            var runStart = 0
            for (i in 1..significant.size) {
                val runBroke = i == significant.size ||
                    (significant[i].price - significant[i - 1].price) > maxGap
                if (runBroke) {
                    shelves.add(buildShelf(side, significant.subList(runStart, i), referencePrice))
                    runStart = i
                }
            }
        }

        return shelves.sortedByDescending { it.priorityScore }
    }

    private fun requiredIntensity(price: Double, referencePrice: Double): Float {
        val distanceFraction = kotlin.math.abs(price - referencePrice) / referencePrice
        val proximity = kotlin.math.exp(-distanceFraction / nearPriceRampFraction)
        return (farIntensityThreshold + (nearIntensityThreshold - farIntensityThreshold) * proximity).toFloat()
    }

    private fun buildShelf(side: BookSide, run: List<LiquidityZone>, referencePrice: Double): LiquidityShelf {
        val peak = run.maxBy { it.intensity }
        val minPrice = run.minOf { it.price }
        val maxPrice = run.maxOf { it.price }
        val centerPrice = (minPrice + maxPrice) / 2.0

        val distanceFraction = (kotlin.math.abs(centerPrice - referencePrice) / referencePrice)

        val proximityWeight = 1.0 - kotlin.math.exp(-distanceFraction / nearPriceRampFraction)

        return LiquidityShelf(
            side = side,
            minPrice = minPrice,
            maxPrice = maxPrice,
            totalVolume = run.sumOf { it.volume },
            peakIntensity = peak.intensity,
            levelCount = run.size,
            firstSeenMs = run.minOf { it.firstSeenMs },
            distanceFraction = distanceFraction,
            proximityWeight = proximityWeight,
        )
    }
}
