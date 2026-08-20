package org.example.test.agent

data class MarketObservation(
    val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double,
    val bidPrices: DoubleArray, val bidSizes: DoubleArray, val askPrices: DoubleArray, val askSizes: DoubleArray,
    val positionTarget: Double, val unrealizedPnlPercent: Double, val liquidationDistancePercent: Double,
    val markPrice: Double, val fundingRate: Double = 0.0, val fundingSecondsRemaining: Long = 0L,
)

data class PolicyDecision(val positionTarget: Double, val quantiles: DoubleArray) {
    val cvar5: Double get() = quantiles.take(maxOf(1, quantiles.size / 20)).average()
    val quantileSpread: Double get() = if (quantiles.size < 2) Double.POSITIVE_INFINITY else quantiles.last() - quantiles.first()
}

fun interface RecurrentPolicyRunner { fun infer(window: List<MarketObservation>): PolicyDecision }

object SafeFlatPolicyRunner : RecurrentPolicyRunner {
    override fun infer(window: List<MarketObservation>) = PolicyDecision(0.0, doubleArrayOf(-1.0, -0.4, 0.0, 0.2, 0.4))
}

data class PolicyProvenance(val version: String, val trainedAtMs: Long, val validationWindow: String, val validationResult: String, val promotedAtMs: Long = System.currentTimeMillis())
