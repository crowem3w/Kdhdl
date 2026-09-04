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



















class StopLossGuard(
    private val credentialsStore: BitgetLiveCredentialsStore,
    private val riskSettingsStore: RiskSettingsStore,
) {
    private companion object {
        const val TAG = "StopLossGuard"

        
        
        
        
        const val REVERIFY_INTERVAL_MS = 60_000L

        
        
        
        const val SIZE_DRIFT_TOLERANCE = 0.0005
    }

    
    
    
    
    
    
    private val client = BitgetTradingRestClient(
        environment = { BitgetEnvironment.LIVE },
        credentialsProvider = { credentialsStore.load() },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private data class GuardedState(val sizeInBaseCoin: Double, val verifiedAtMs: Long)

    
    
    private val guarded = mutableMapOf<PositionSide, GuardedState>()

    fun start(positions: StateFlow<List<PaperPosition>>) {
        stop()
        job = scope.launch {
            positions.collect { openPositions ->
                try {
                    reconcile(openPositions)
                } catch (e: Exception) {
                    
                    
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
        if (!sizeDrifted && !stale) return 

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

    
    
    
    
    private fun formatPrice(price: Double): String = String.format(Locale.US, "%.1f", price)

    private fun formatSize(size: Double): String = String.format(Locale.US, "%.3f", size)
}