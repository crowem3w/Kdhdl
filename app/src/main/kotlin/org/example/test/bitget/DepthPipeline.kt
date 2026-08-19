package org.example.test.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DepthRenderTick(val snapshot: DepthSnapshot, val delta: DepthDelta?)

class DepthPipeline(
    private val instId: String = "BTCUSDT",
    instType: String = "USDT-FUTURES",
    private val depthLimit: Int = 200,

    private val renderLevels: Int? = null,
    private val socket: BitgetDepthSocket = BitgetDepthSocket(instId, instType),
    private val depthRestClient: BitgetDepthRestClient = BitgetDepthRestClient(),
) {
    private companion object {
        const val TAG = "DepthPipeline"

        const val MAX_CONSECUTIVE_MISMATCHES = 5

        const val REST_SEED_INTERVAL_MS = 20_000L
    }

    private val matrix = DepthMatrix(depthLimit)
    private val zoneTracker = LiquidityZoneTracker()

    private val _depth = MutableStateFlow(DepthSnapshot(emptyList(), emptyList(), 0L, -1L))

    val depth: StateFlow<DepthSnapshot> = _depth.asStateFlow()

    private val _liquidityZones = MutableStateFlow<List<LiquidityZone>>(emptyList())

    val liquidityZones: StateFlow<List<LiquidityZone>> = _liquidityZones.asStateFlow()

    private val _liquidityShelves = MutableStateFlow<List<LiquidityShelf>>(emptyList())

    val liquidityShelves: StateFlow<List<LiquidityShelf>> = _liquidityShelves.asStateFlow()

    private val _priceLevelDelta = MutableStateFlow<DepthDelta?>(null)

    val priceLevelDelta: StateFlow<DepthDelta?> = _priceLevelDelta.asStateFlow()

    private val _renderTicks = MutableStateFlow(DepthRenderTick(DepthSnapshot(emptyList(), emptyList(), 0L, -1L), null))

    val renderTicks: StateFlow<DepthRenderTick> = _renderTicks.asStateFlow()

    private val _driftError = MutableStateFlow<String?>(null)

    val driftError: StateFlow<String?> = _driftError.asStateFlow()

    val socketState: StateFlow<SocketState> = socket.state
    val socketError: StateFlow<String?> = socket.lastError

    private val recoveryLock = Mutex()
    private var recovering = false
    private var consecutiveMismatches = 0

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in DepthPipeline coroutine scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var updatesJob: Job? = null
    private var restSeedJob: Job? = null

    fun start() {
        stop()
        matrix.clear()
        zoneTracker.reset()
        _depth.value = DepthSnapshot(emptyList(), emptyList(), 0L, -1L)
        _liquidityZones.value = emptyList()
        _liquidityShelves.value = emptyList()
        _priceLevelDelta.value = null
        _renderTicks.value = DepthRenderTick(DepthSnapshot(emptyList(), emptyList(), 0L, -1L), null)
        _driftError.value = null
        consecutiveMismatches = 0

        updatesJob = socket.rawUpdates
            .onEach { update -> onUpdateArrived(update) }
            .catch { e -> Log.e(TAG, "Error processing depth update; dropping update", e) }
            .launchIn(scope)
        socket.connect()
        restSeedJob = scope.launch { runRestSeedLoop() }
    }

    fun stop() {
        updatesJob?.cancel()
        restSeedJob?.cancel()
        socket.disconnect()
    }

    private suspend fun runRestSeedLoop() {
        while (scope.isActive) {
            delay(REST_SEED_INTERVAL_MS)
            try {
                if (!matrix.isPrimed) continue
                val restSnapshot = depthRestClient.fetchAggregatedDepth(instId = instId)
                val delta = matrix.mergeRestLevels(restSnapshot.bids, restSnapshot.asks)
                if (delta != null) {
                    onDeltaApplied(update = null, delta = delta)
                }
            } catch (e: Exception) {
                Log.w(TAG, "REST depth seeding failed: ${e.message}")
            }
        }
    }

    private suspend fun onUpdateArrived(update: DepthUpdate) {
        if (update.isSnapshot) {
            matrix.applySnapshot(update)

            onDeltaApplied(update, delta = null)
            return
        }
        val delta = matrix.applyUpdate(update)
        if (delta != null) {
            onDeltaApplied(update, delta)
        }

    }

    private suspend fun onDeltaApplied(update: DepthUpdate?, delta: DepthDelta?) {
        val snapshot = matrix.snapshot(renderLevels)
        _depth.value = snapshot
        _priceLevelDelta.value = delta

        _renderTicks.value = DepthRenderTick(snapshot, delta)

        val zones = zoneTracker.update(snapshot, update?.timestampMs ?: System.currentTimeMillis())
        _liquidityZones.value = zones

        val referencePrice = midPriceOf(snapshot)
        _liquidityShelves.value = if (referencePrice != null) {
            LiquidityShelfMerger.merge(zones, referencePrice)
        } else {
            emptyList()
        }

        if (update == null) return

        if (matrix.verifyChecksum(update.checksum)) {
            consecutiveMismatches = 0
            _driftError.value = null
            return
        }

        consecutiveMismatches++
        Log.w(TAG, "Checksum mismatch (#$consecutiveMismatches) at seq=${update.seq}; requesting fresh snapshot")

        if (consecutiveMismatches > MAX_CONSECUTIVE_MISMATCHES) {
            _driftError.value = "Order book repeatedly failed to sync; check connection and retry."
            return
        }

        recoveryLock.withLock {
            if (recovering) return
            recovering = true
        }
        try {

            socket.resubscribe()
        } finally {
            recoveryLock.withLock { recovering = false }
        }
    }

    private fun midPriceOf(snapshot: DepthSnapshot): Double? {
        val bid = snapshot.bids.firstOrNull()?.price ?: return null
        val ask = snapshot.asks.firstOrNull()?.price ?: return null
        return (bid + ask) / 2.0
    }
}
