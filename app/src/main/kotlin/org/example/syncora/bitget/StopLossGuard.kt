package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * The exchange-side "dead-man's switch" from the redesign doc (§2.2/§5):
 * for every open **live** position, makes sure a resting stop-loss trigger
 * order exists on Bitget's own book, independent of whether this app's
 * process - or its foreground service - is still alive.
 *
 * This deliberately does not depend on [org.example.syncora.service.MarketDataForegroundService]
 * staying alive to protect a position; it only depends on the *placement*
 * call succeeding once. After that, Bitget's matching engine owns the
 * trigger, same as the doc's framing: "any position left open when the
 * service is killed is protected by an exchange-native stop order, not by
 * the app."
 *
 * [start] should be fed [LiveTradingRepository.positions] and is safe to run
 * continuously alongside it - most ticks are a no-op (the cached
 * known-good state below skips re-verifying a position that hasn't grown,
 * shrunk, or flipped side since it was last confirmed protected).
 */
class StopLossGuard(
    private val credentialsStore: BitgetLiveCredentialsStore,
    private val riskSettingsStore: RiskSettingsStore,
) {
    private companion object {
        const val TAG = "StopLossGuard"

        // How often an already-confirmed stop is re-checked against the
        // exchange's own records, even if the position hasn't visibly
        // changed - cheap insurance against the stop having been cancelled
        // or triggered out-of-band (e.g. manually, from Bitget's own app).
        const val REVERIFY_INTERVAL_MS = 60_000L

        // Position-size changes below this fraction are treated as noise
        // (rounding in Bitget's reported `total`) rather than a real size
        // change that requires replacing the resting stop.
        const val SIZE_DRIFT_TOLERANCE = 0.0005
    }

    // Independent client/credentials read from the same encrypted store as
    // LiveTradingRepository, but this guard does not depend on
    // LiveTradingRepository being the one running - it only needs read
    // access to positions (via [start]'s StateFlow) and its own ability to
    // place/cancel orders, so it keeps working even if that repository's
    // polling is torn down first during a shutdown sequence.
    private val client = BitgetTradingRestClient(
        environment = { BitgetEnvironment.LIVE },
        credentialsProvider = { credentialsStore.load() },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private data class GuardedState(val sizeInBaseCoin: Double, val verifiedAtMs: Long)

    // One entry per currently-open side; cleared for a side once its
    // position closes (see [reconcile]).
    private val guarded = mutableMapOf<PositionSide, GuardedState>()

    fun start(positions: StateFlow<List<PaperPosition>>) {
        stop()
        job = scope.launch {
            positions.collect { openPositions ->
                try {
                    reconcile(openPositions)
                } catch (e: Exception) {
                    // Never let a guard failure take down the collector - the
                    // next emission (or the next poll cycle upstream) tries again.
                    Log.w(TAG, "Stop-loss reconcile failed: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun reconcile(openPositions: List<PaperPosition>) {
        val openSides = openPositions.mapTo(mutableSetOf()) { it.side }
        // A side no longer present means that position closed. Bitget cancels
        // a position's TP/SL trigger automatically when the position it's
        // attached to fully closes, so there's nothing to clean up on the
        // exchange side here - just drop our local bookkeeping for it.
        guarded.keys.retainAll(openSides)

        for (position in openPositions) {
            if (position.total <= 0.0) continue
            ensureProtected(position)
        }
    }

    private suspend fun ensureProtected(position: PaperPosition) {
        val now = System.currentTimeMillis()
        val cached = guarded[position.side]
        val sizeDrifted = cached == null ||
            abs(cached.sizeInBaseCoin - position.total) > position.total * SIZE_DRIFT_TOLERANCE
        val stale = cached == null || now - cached.verifiedAtMs > REVERIFY_INTERVAL_MS
        if (!sizeDrifted && !stale) return // known-good and recently confirmed; skip the API round-trip

        val percent = riskSettingsStore.stopLossPercent.coerceIn(0.001, 0.5)
        val triggerPrice = when (position.side) {
            PositionSide.LONG -> position.entryPrice * (1.0 - percent)
            PositionSide.SHORT -> position.entryPrice * (1.0 + percent)
        }
        if (triggerPrice <= 0.0 || position.entryPrice <= 0.0) return

        val existingStops = client.fetchPendingStopLossOrders(position.symbol)
            .filter { it.holdSide == position.side }

        val alreadyCorrect = existingStops.any {
            abs(it.sizeInBaseCoin - position.total) <= position.total * SIZE_DRIFT_TOLERANCE
        }
        if (alreadyCorrect) {
            guarded[position.side] = GuardedState(position.total, now)
            return
        }

        // Any resting stop(s) found are sized for a position that's since
        // grown or shrunk - replace rather than stack a second stop.
        existingStops.forEach { stale -> client.cancelStopLoss(position.symbol, stale.orderId) }

        client.placeStopLoss(
            symbol = position.symbol,
            holdSide = position.side,
            triggerPrice = formatPrice(triggerPrice),
            sizeInBaseCoin = formatSize(position.total),
        )
        guarded[position.side] = GuardedState(position.total, now)
        Log.i(TAG, "Placed dead-man's-switch stop-loss: ${position.side} ${position.total} @ $triggerPrice")
    }

    // NOTE: uses a fixed one-decimal price precision as a simplification.
    // A production build should size this off BTCUSDT's actual `pricePlace`
    // from Bitget's contract-config endpoint rather than hardcoding it, the
    // same way order size below assumes BTCUSDT's own base-coin precision.
    private fun formatPrice(price: Double): String = String.format(Locale.US, "%.1f", price)

    private fun formatSize(size: Double): String = String.format(Locale.US, "%.3f", size)
}
