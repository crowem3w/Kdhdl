package org.example.test.bitget

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Configuration for the paper trading engine's latency simulation (design
 * doc §6, "Latency Simulation"). A real order never reaches the matching
 * engine the instant a trader/strategy decides to send it - there's always
 * some network round-trip plus exchange/engine processing time first, and
 * on a fast-moving market the book can shift meaningfully in that window.
 * Pricing every simulated fill against the book state at the *exact
 * instant of the decision* (as a naive paper engine does) skips that gap
 * entirely and makes fills look better than a real order would have
 * gotten.
 *
 * @property enabled Master on/off switch. When false, fills are priced
 *   against the book/mark price at decision time - same behavior as if
 *   this feature didn't exist.
 * @property baseDelayMs Fixed portion of the simulated delay, in
 *   milliseconds. The design doc suggests 50-300ms as a starting point for
 *   "network + engine processing delay" - tune to whatever infra a real
 *   strategy would actually run against.
 * @property jitterMs Upper bound of an additional *random* delay layered
 *   on top of [baseDelayMs] on every single fill, uniformly distributed
 *   over `[0, jitterMs]`. Real network latency is never perfectly
 *   constant tick to tick, so a fixed delay alone still understates how
 *   much the book can move; set to 0 for a purely fixed delay.
 */
data class LatencyConfig(
    val enabled: Boolean = true,
    val baseDelayMs: Long = 150L,
    val jitterMs: Long = 100L,
) {
    /**
     * Clamps every field into the range the settings dialog allows, so a
     * bad persisted value (or a stray edit) can never produce a runaway
     * delay that makes the app feel broken.
     */
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

/**
 * Live market state as observed at the moment a (possibly latency-delayed)
 * simulated order actually reaches "the book" - see
 * [LatencySimulator.captureExecutionState]. Either field can be null under
 * the same conditions the un-delayed providers could already return null
 * (feed not primed yet, no connectivity); callers should keep falling back
 * exactly as they did before this existed.
 */
data class MarketStateAtFill(
    val markPrice: Double?,
    val depthSnapshot: DepthSnapshot?,
)

/**
 * Everything a fill needs to know about how latency simulation shaped it:
 * when the decision was made, when the (simulated) order actually reached
 * the book, how much delay that was, and what the market looked like at
 * that later moment.
 */
data class LatencyFillContext(
    val decisionTimeMs: Long,
    val fillTimeMs: Long,
    val marketState: MarketStateAtFill,
) {
    /** Wall-clock milliseconds actually elapsed between decision and fill - 0 whenever latency simulation is disabled or resolves to no delay. */
    val appliedDelayMs: Long get() = fillTimeMs - decisionTimeMs
}

/**
 * Injects the configurable artificial delay described in the design doc's
 * §6 and evaluates order fills against the order book snapshot *after*
 * that delay, not the one available at the instant the trader decided to
 * trade.
 *
 * This is intentionally simple, and other than the [delay] call itself,
 * stateless: [markPriceProvider] and [depthSnapshotProvider] are always
 * read fresh, live off whatever market-data pipeline the app already has
 * running - so "the book at t_decision + delay" is just "whatever the live
 * feed's latest snapshot happens to be by the time the delay finishes".
 * There's no historical order-book buffer to maintain; real time passing
 * during the injected [delay] does the work.
 */
class LatencySimulator(
    private val markPriceProvider: () -> Double?,
    private val depthSnapshotProvider: () -> DepthSnapshot?,
    private val configProvider: () -> LatencyConfig,
    // Overridable purely so tests can fake elapsed time / make delays
    // deterministic - production always uses the real clock and RNG.
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    /**
     * Records `t_decision` (defaults to *now*), waits out the configured
     * fixed-plus-jitter delay - skipped entirely when latency simulation
     * is disabled or resolves to zero - and then reads the mark price and
     * order book fresh, exactly as doc §6 describes. Every simulated
     * market/marketable-limit fill in [PaperTradingRepository] routes its
     * price lookup through this instead of calling the providers directly,
     * so its fill reflects market state at (decision time + delay) rather
     * than at decision time.
     */
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
