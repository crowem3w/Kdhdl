package org.example.test.agent

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.test.bitget.BitgetTickerSocket
import org.example.test.bitget.DepthSnapshot
import org.example.test.bitget.Kline
import org.example.test.bitget.PublicTrade
import org.example.test.bitget.SocketState

/**
 * The "Data ingestion service" box from design doc §7.4's separation-of-
 * concerns diagram: an always-on process (§7.1) that fans the raw Bitget
 * streams the app already maintains for the chart/order book - klines,
 * depth, public trades - plus its own new [BitgetTickerSocket] (mark/index
 * price, funding rate, open interest), into [AgentFeatureStore], and
 * publishes the result as [features] on a fixed cadence decoupled from how
 * often any individual source actually ticks (§7.2/§7.6 - inference-side
 * consumers shouldn't have to care whether a frame was triggered by a
 * trade print or a depth delta).
 *
 * Deliberately does *not* own the kline/depth/trade sockets themselves -
 * those already live at application scope
 * ([org.example.test.SyncoraApplication]) so the chart and this service
 * share one connection each instead of doubling up sockets for the same
 * public data. Only the ticker socket is new, since nothing else in the
 * app currently subscribes to it.
 *
 * This is the ingestion half of §7.4's pipeline only. The regime-detector
 * and agent-inference services that would consume [features] next don't
 * exist yet (§9 open follow-ups) - [features] and [recentFrames] are the
 * hand-off point for them.
 */
class AgentDataIngestionService(
    instId: String = "BTCUSDT",
    instType: String = "USDT-FUTURES",
    private val klines: StateFlow<List<Kline>>,
    private val depth: StateFlow<DepthSnapshot>,
    private val trades: SharedFlow<PublicTrade>,
    private val tickerSocket: BitgetTickerSocket = BitgetTickerSocket(instId, instType),
    private val featureStore: AgentFeatureStore = AgentFeatureStore(),
    private val cacheStore: AgentFeatureCacheStore = NoopAgentFeatureCacheStore,
    private val historyCapacity: Int = 500,
    private val emitIntervalMs: Long = 1_000L,
    private val persistIntervalMs: Long = 15_000L,
    private val staleThresholdMs: Long = 10_000L,
) {
    private companion object {
        const val TAG = "AgentDataIngestion"
    }

    private val _features = MutableStateFlow<MarketFeatureFrame?>(null)

    /** Latest assembled feature frame - the shared feature store's current head. Null until the first emission tick. */
    val features: StateFlow<MarketFeatureFrame?> = _features.asStateFlow()

    private val _usingCache = MutableStateFlow(false)

    /** True while [features] is still showing a reloaded-from-disk frame rather than a live one. */
    val usingCache: StateFlow<Boolean> = _usingCache.asStateFlow()

    val tickerSocketState: StateFlow<SocketState> = tickerSocket.state
    val tickerSocketError: StateFlow<String?> = tickerSocket.lastError

    private val historyLock = Any()
    private val history = ArrayDeque<MarketFeatureFrame>(historyCapacity)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in AgentDataIngestionService coroutine scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private var klinesJob: Job? = null
    private var depthJob: Job? = null
    private var tradesJob: Job? = null
    private var tickerJob: Job? = null
    private var emitJob: Job? = null
    private var persistJob: Job? = null
    private var cacheLoadJob: Job? = null

    fun start() {
        stop()
        featureStore.reset()
        synchronized(historyLock) { history.clear() }
        _features.value = null
        _usingCache.value = false

        cacheLoadJob = scope.launch { loadCacheAndPrime() }

        klinesJob = klines.onEach { featureStore.onKlines(it) }
            .catch { e -> Log.e(TAG, "Error ingesting klines; dropping update", e) }
            .launchIn(scope)

        depthJob = depth.onEach { featureStore.onDepth(it) }
            .catch { e -> Log.e(TAG, "Error ingesting depth; dropping update", e) }
            .launchIn(scope)

        tradesJob = trades.onEach { featureStore.onTrade(it) }
            .catch { e -> Log.e(TAG, "Error ingesting trade; dropping update", e) }
            .launchIn(scope)

        tickerJob = tickerSocket.ticker.onEach { snapshot ->
            snapshot?.let { featureStore.onTicker(it) }
        }
            .catch { e -> Log.e(TAG, "Error ingesting ticker; dropping update", e) }
            .launchIn(scope)
        tickerSocket.connect()

        emitJob = scope.launch { runEmitLoop() }
        persistJob = scope.launch { runPersistLoop() }
    }

    fun stop() {
        cacheLoadJob?.cancel()
        klinesJob?.cancel()
        depthJob?.cancel()
        tradesJob?.cancel()
        tickerJob?.cancel()
        emitJob?.cancel()
        persistJob?.cancel()
        tickerSocket.disconnect()

        val finalHistory = synchronized(historyLock) { history.toList() }
        if (finalHistory.isNotEmpty()) {
            scope.launch { cacheStore.save(finalHistory) }
        }
    }

    /** Snapshot of the recent rolling history (oldest first), capped at [historyCapacity] - the hand-off surface for a future regime detector / replay buffer seeder. */
    fun recentFrames(): List<MarketFeatureFrame> = synchronized(historyLock) { history.toList() }

    private suspend fun loadCacheAndPrime() {
        val cached = cacheStore.load() ?: return
        if (cached.isEmpty()) return
        synchronized(historyLock) {
            history.clear()
            history.addAll(cached.takeLast(historyCapacity))
        }
        _features.value = cached.last()
        _usingCache.value = true
    }

    private suspend fun runEmitLoop() {
        while (scope.isActive) {
            delay(emitIntervalMs)
            val frame = featureStore.snapshot(staleThresholdMs = staleThresholdMs)
            _features.value = frame
            _usingCache.value = false
            synchronized(historyLock) {
                history.addLast(frame)
                if (history.size > historyCapacity) history.removeFirst()
            }
        }
    }

    private suspend fun runPersistLoop() {
        while (scope.isActive) {
            delay(persistIntervalMs)
            val snapshot = synchronized(historyLock) { history.toList() }
            if (snapshot.isNotEmpty()) cacheStore.save(snapshot)
        }
    }
}
