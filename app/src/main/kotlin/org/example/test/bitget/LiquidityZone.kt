package org.example.test.bitget

enum class BookSide { BID, ASK }

data class LiquidityZone(
    val price: Double,
    val side: BookSide,

    val volume: Double,

    val intensity: Float,

    val firstSeenMs: Long,

    val lastUpdateMs: Long,
) {

    val durationMs: Long get() = (lastUpdateMs - firstSeenMs).coerceAtLeast(0L)
}
