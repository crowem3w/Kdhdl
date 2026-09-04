package org.example.syncora.bitget

import kotlinx.coroutines.delay
import kotlin.random.Random

























data class LatencyConfig(
    val enabled: Boolean = true,
    val baseDelayMs: Long = 150L,
    val jitterMs: Long = 100L,
) {
    




    fun coerced(): LatencyConfig = copy(
        baseDelayMs = baseDelayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS),
        jitterMs = jitterMs.coerceIn(MIN_JITTER_MS, MAX_JITTER_MS),
    )

    companion object {
        const val MIN_DELAY_MS = 0L
        const val MAX_DELAY_MS = 2_000L
        const val MIN_JITTER_MS = 0L
        const val MAX_JITTER_MS = 1_000L

        val DEFAULT = LatencyConfig()
    }
}









data class MarketStateAtFill(
    val markPrice: Double?,
    val depthSnapshot: DepthSnapshot?,
)







data class LatencyFillContext(
    val decisionTimeMs: Long,
    val fillTimeMs: Long,
    val marketState: MarketStateAtFill,
) {
    
    val appliedDelayMs: Long get() = fillTimeMs - decisionTimeMs
}















class LatencySimulator(
    private val markPriceProvider: () -> Double?,
    private val depthSnapshotProvider: () -> DepthSnapshot?,
    private val configProvider: () -> LatencyConfig,
    
    
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    









    suspend fun captureExecutionState(decisionTimeMs: Long = clock()): LatencyFillContext {
        val config = configProvider().coerced()
        val resolvedDelayMs = if (config.enabled) {
            val jitter = if (config.jitterMs > 0L) random.nextLong(0L, config.jitterMs + 1L) else 0L
            config.baseDelayMs + jitter
        } else {
            0L
        }
        if (resolvedDelayMs > 0L) {
            delay(resolvedDelayMs)
        }
        return LatencyFillContext(
            decisionTimeMs = decisionTimeMs,
            fillTimeMs = clock(),
            marketState = MarketStateAtFill(
                markPrice = markPriceProvider(),
                depthSnapshot = depthSnapshotProvider(),
            ),
        )
    }
}