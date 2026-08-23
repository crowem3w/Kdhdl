package org.example.syncora.risk

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Current read of the volatility circuit breaker. [PreTradeSafetyGate]
 * only cares about [shouldHaltEntries]/[shouldFlattenToCash] below, but the
 * richer status is kept for UI/telemetry - a breaker that silently blocks
 * orders with no visible reason is not trustworthy.
 */
sealed class CircuitBreakerStatus {
    /** DVOL is below the calibrated threshold and recent enough to trust. */
    data class Normal(val dvol: Double, val threshold: Double, val asOfMs: Long) : CircuitBreakerStatus()

    /** DVOL is at/above the calibrated threshold - the actual "circuit breaker tripped" case. */
    data class Tripped(val dvol: Double, val threshold: Double, val asOfMs: Long) : CircuitBreakerStatus()

    /**
     * We don't have a trustworthy recent reading (never polled successfully
     * yet, or the last successful poll is older than [VolatilityCircuitBreaker]'s
     * configured max data age). Fails **safe**: treated as blocking new
     * entries, same as [Tripped], but does *not* force a flatten - see
     * [VolatilityCircuitBreaker] kdoc for why those two are handled
     * differently.
     */
    data class DataUnavailable(val reason: String) : CircuitBreakerStatus()
}

/**
 * The design doc's §5 volatility circuit breaker: "if a chosen volatility
 * index exceeds a safety threshold, halt new entries and flatten to cash."
 *
 * This runs its **own** independent poll loop against [client]
 * ([VolatilityIndexClient], sourced from Deribit's DVOL - a different venue
 * than Bitget, on purpose) rather than being driven by
 * [org.example.syncora.agent.DecisionLoopScheduler]'s decision cadence.
 * That independence is the point: the breaker keeps evaluating on its own
 * schedule regardless of whether the policy is running, stuck, or being
 * bypassed by some other order-placing code path, and every order-placing
 * path is required to consult [status]/[shouldHaltEntries] rather than
 * trust that the policy already accounted for volatility.
 *
 * **Fail-safe, not fail-deadly.** Two distinct failure behaviors:
 * - Confirmed high volatility ([CircuitBreakerStatus.Tripped]): halt new
 *   entries **and** signal that open exposure should be flattened - this is
 *   the doc's explicit "flatten to cash" requirement.
 * - Unknown volatility ([CircuitBreakerStatus.DataUnavailable] - a poll
 *   failure or a stale reading): halt new entries (we can't confirm it's
 *   safe to add risk), but do **not** force a flatten. Forcing a market
 *   close based on the *absence* of data isn't obviously safer than leaving
 *   an existing, already-guarded (see [org.example.syncora.bitget.StopLossGuard])
 *   position alone, so this deliberately doesn't escalate a data outage
 *   into a forced trade.
 *
 * **Threshold calibration.** [threshold] defaults to a conservative
 * starting point and must be re-derived against Deribit's actual historical
 * DVOL distribution before this is relied on in production - exactly the
 * gap the design doc calls out about the original paper's unsourced 90.1
 * figure. DVOL has historically traded roughly in the 40-90 range in calm
 * conditions and spiked well above 100 during acute stress (e.g. the
 * November 2022 FTX collapse); the default here is deliberately set on the
 * conservative (lower) side of that range pending a proper calibration
 * pass, and is exposed via [org.example.syncora.risk.RiskLimitsStore] so it
 * can be tuned without a code change.
 */
class VolatilityCircuitBreaker(
    private val client: VolatilityIndexClient = VolatilityIndexClient(),
    private val thresholdProvider: () -> Double,
    private val pollIntervalMs: Long = 60_000L,
    /** A successful reading older than this is no longer trusted - see [CircuitBreakerStatus.DataUnavailable]. */
    private val maxDataAgeMs: Long = 5 * 60_000L,
) {
    private companion object {
        const val TAG = "VolatilityCircuitBreaker"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<CircuitBreakerStatus>(
        CircuitBreakerStatus.DataUnavailable("not polled yet"),
    )
    val status: StateFlow<CircuitBreakerStatus> = _status.asStateFlow()

    @Volatile
    private var lastSuccessfulReading: VolatilityReading? = null

    fun start() {
        stop()
        job = scope.launch {
            while (true) {
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Synchronous, non-suspending read for use right before an order is dispatched - never makes a network call itself. */
    fun shouldHaltEntries(): Boolean = currentStatusRefreshedForAge() !is CircuitBreakerStatus.Normal

    /** True only when volatility is confirmed above threshold, per the class kdoc's fail-safe/fail-deadly split. */
    fun shouldFlattenToCash(): Boolean = currentStatusRefreshedForAge() is CircuitBreakerStatus.Tripped

    private fun currentStatusRefreshedForAge(): CircuitBreakerStatus {
        val reading = lastSuccessfulReading
        val now = System.currentTimeMillis()
        if (reading != null && now - reading.fetchedAtMs > maxDataAgeMs) {
            // Last known-good reading has gone stale between poll ticks
            // (e.g. the poll loop itself died) - degrade to DataUnavailable
            // rather than keep trusting an old number.
            val stale = CircuitBreakerStatus.DataUnavailable("last DVOL reading is stale (${now - reading.fetchedAtMs}ms old)")
            _status.value = stale
            return stale
        }
        return _status.value
    }

    private suspend fun pollOnce() {
        try {
            val reading = client.fetchLatest()
            lastSuccessfulReading = reading
            val threshold = thresholdProvider()
            _status.value = if (reading.value >= threshold) {
                Log.w(TAG, "Volatility circuit breaker TRIPPED: DVOL=${reading.value} >= threshold=$threshold")
                CircuitBreakerStatus.Tripped(dvol = reading.value, threshold = threshold, asOfMs = reading.asOfMs)
            } else {
                CircuitBreakerStatus.Normal(dvol = reading.value, threshold = threshold, asOfMs = reading.asOfMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Volatility index poll failed, failing safe (blocking new entries): ${e.message}")
            _status.value = CircuitBreakerStatus.DataUnavailable(e.message ?: e.toString())
        }
    }
}
