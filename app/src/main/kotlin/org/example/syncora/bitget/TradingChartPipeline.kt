package org.example.syncora.bitget

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

enum class PipelineState {
    IDLE,

    STREAMING_COLD,

    SNAPSHOT_RETRYING,

    LIVE,
    STOPPED,
}

class TradingChartPipeline(
    private val instId: String = "BTCUSDT",
    private val instType: String = "USDT-FUTURES",
    initialTimeframe: Timeframe = Timeframe.DEFAULT,
    private val bufferCapacity: Int = 100,
    private val socket: BitgetKlineSocket = BitgetKlineSocket(instId, instType, initialTimeframe.wsChannel),
    private val restClient: BitgetKlineRestClient = BitgetKlineRestClient(),
    private val cacheStore: KlineCacheStore = NoopKlineCacheStore,
) {
    private companion object {
        const val TAG = "TradingChartPipeline"
        const val SNAPSHOT_BASE_RETRY_DELAY_MS = 1_500L
        const val SNAPSHOT_MAX_RETRY_DELAY_MS = 20_000L

        const val MAX_QUEUED_TICKS = 2_000
        const val CACHE_PERSIST_INTERVAL_MS = 5_000L

        
        
        
        
        const val GAP_BACKFILL_MAX_CANDLES = 1_000
    }

    private val buffer = KlineBuffer(bufferCapacity)

    
    
    
    private val productType: String = instType.lowercase()

    private val _klines = MutableStateFlow<List<Kline>>(emptyList())

    val klines: StateFlow<List<Kline>> = _klines.asStateFlow()

    private val _pipelineState = MutableStateFlow(PipelineState.IDLE)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _usingCache = MutableStateFlow(false)

    val usingCache: StateFlow<Boolean> = _usingCache.asStateFlow()

    val socketState: StateFlow<SocketState> = socket.state

    private val _currentTimeframe = MutableStateFlow(initialTimeframe)

    val currentTimeframe: StateFlow<Timeframe> = _currentTimeframe.asStateFlow()

    private val _barDurationMillis = MutableStateFlow(initialTimeframe.durationMillis)

    val barDurationMillis: StateFlow<Long> = _barDurationMillis.asStateFlow()

    private val _snapshotError = MutableStateFlow<String?>(null)

    val snapshotError: StateFlow<String?> = _snapshotError.asStateFlow()

    val socketError: StateFlow<String?> = socket.lastError

    private val primeLock = Mutex()
    private var primed = false
    private val tempQueue = ArrayDeque<Kline>()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in TradingChartPipeline coroutine scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var rawUpdatesJob: Job? = null
    private var snapshotJob: Job? = null
    private var cacheLoadJob: Job? = null
    private var cachePersistJob: Job? = null
    private var switchJob: Job? = null

    fun start() {
        stop()
        primed = false
        tempQueue.clear()
        buffer.clear()
        _klines.value = emptyList()
        _usingCache.value = false
        _snapshotError.value = null
        _pipelineState.value = PipelineState.STREAMING_COLD

        cacheLoadJob = scope.launch { loadCacheAndPaint() }

        rawUpdatesJob = socket.rawUpdates
            .onEach { batch -> onTicksArrived(batch) }
            .catch { e -> Log.e(TAG, "Error processing kline tick batch; dropping batch", e) }
            .launchIn(scope)
        socket.connect()

        snapshotJob = scope.launch { loadSnapshotWithRetry() }
    }

    fun stop() {

        if (primed) {
            val finalSnapshot = buffer.snapshot()
            if (finalSnapshot.isNotEmpty()) {
                scope.launch { cacheStore.save(finalSnapshot) }
            }
        }
        switchJob?.cancel()
        cacheLoadJob?.cancel()
        cachePersistJob?.cancel()
        snapshotJob?.cancel()
        rawUpdatesJob?.cancel()
        socket.disconnect()
        _pipelineState.value = PipelineState.STOPPED
    }

    fun switchTimeframe(timeframe: Timeframe) {
        if (timeframe == _currentTimeframe.value) return
        switchJob?.cancel()
        switchJob = scope.launch { performTimeframeSwitch(timeframe) }
    }

    private suspend fun performTimeframeSwitch(timeframe: Timeframe) {

        rawUpdatesJob?.cancel()
        cacheLoadJob?.cancel()
        cachePersistJob?.cancel()
        snapshotJob?.cancel()

        primeLock.withLock {
            primed = false
            tempQueue.clear()
        }
        buffer.clear()
        _klines.value = emptyList()
        _usingCache.value = false
        _snapshotError.value = null
        _pipelineState.value = PipelineState.STREAMING_COLD

        _currentTimeframe.value = timeframe
        _barDurationMillis.value = timeframe.durationMillis

        socket.switchGranularity(timeframe.wsChannel)

        rawUpdatesJob = socket.rawUpdates
            .onEach { batch -> onTicksArrived(batch) }
            .catch { e -> Log.e(TAG, "Error processing kline tick batch; dropping batch", e) }
            .launchIn(scope)
        snapshotJob = scope.launch { loadSnapshotWithRetry() }
    }

    private suspend fun loadCacheAndPaint() {
        val cached = cacheStore.load() ?: return
        primeLock.withLock {
            if (!primed) {
                _klines.value = cached.takeLast(bufferCapacity)
                _usingCache.value = true
            }
        }
        catchUpFromCacheIfNeeded(cached)
    }

    


















    private suspend fun catchUpFromCacheIfNeeded(cached: List<Kline>) {
        if (_currentTimeframe.value != Timeframe.ONE_MINUTE) return
        val lastCachedStart = cached.lastOrNull()?.startTime ?: return

        val barMs = Timeframe.ONE_MINUTE.durationMillis
        val expectedNext = lastCachedStart + barMs
        val now = System.currentTimeMillis()

        
        
        if (expectedNext > now - barMs) return

        val missingBars = (now - expectedNext) / barMs
        Log.d(TAG, "Startup catch-up: cache is ~$missingBars 1m candle(s) behind; backfilling via REST")

        
        
        
        
        
        
        
        
        
        val refreshed = try {
            restClient.backfillCandles(
                instId = instId,
                productType = productType,
                granularity = Timeframe.ONE_MINUTE.restParam,
                targetCount = bufferCapacity,
            )
        } catch (e: Exception) {
            
            
            
            Log.w(TAG, "Startup catch-up backfill failed: ${e.message}")
            return
        }
        if (refreshed.isEmpty()) return

        primeLock.withLock {
            
            
            if (primed || _currentTimeframe.value != Timeframe.ONE_MINUTE) return@withLock

            val merged = LinkedHashMap<Long, Kline>()
            for (candle in cached) merged[candle.startTime] = candle
            for (candle in refreshed) merged[candle.startTime] = candle

            _klines.value = merged.values.sortedBy { it.startTime }.takeLast(bufferCapacity)
            _usingCache.value = true
        }
        Log.d(TAG, "Startup catch-up refreshed ${refreshed.size} candle(s) from REST")
    }

    private suspend fun persistCachePeriodically() {
        while (true) {
            delay(CACHE_PERSIST_INTERVAL_MS)
            cacheStore.save(buffer.snapshot())
        }
    }

    private suspend fun onTicksArrived(batch: List<Kline>) {
        primeLock.withLock {
            if (primed) {
                backfillGapIfNeeded(batch)
                applyLive(batch)
            } else {
                tempQueue.addAll(batch)
                if (tempQueue.size > MAX_QUEUED_TICKS) {
                    repeat(tempQueue.size - MAX_QUEUED_TICKS) { tempQueue.removeFirst() }
                    Log.w(TAG, "Cold-start queue exceeded safety cap; trimmed oldest entries")
                }
            }
        }
    }

    









    private suspend fun backfillGapIfNeeded(incomingBatch: List<Kline>) {
        val barMs = _barDurationMillis.value
        if (barMs <= 0) return

        val earliestIncoming = incomingBatch.minOfOrNull { it.startTime } ?: return
        val lastKnown = buffer.lastStartTimeOrNull() ?: return
        val expectedNext = lastKnown + barMs

        
        
        
        if (earliestIncoming <= expectedNext) return

        val missingBars = (earliestIncoming - expectedNext) / barMs
        Log.d(TAG, "Detected gap of $missingBars candle(s) before live tick; backfilling")

        try {
            val missing = restClient.fetchCandleRange(
                instId = instId,
                productType = productType,
                granularity = _currentTimeframe.value.restParam,
                startTime = expectedNext,
                endTime = earliestIncoming - 1,
                limit = GAP_BACKFILL_MAX_CANDLES,
            )
            if (missing.isNotEmpty()) {
                _klines.value = buffer.applyUpdates(missing)
            }
        } catch (e: Exception) {
            
            
            
            Log.w(TAG, "Gap backfill failed for [$expectedNext, $earliestIncoming): ${e.message}")
        }
    }

    private suspend fun loadSnapshotWithRetry() {
        var attempt = 0
        while (true) {
            try {
                
                
                
                
                val historical = restClient.backfillCandles(
                    instId = instId,
                    productType = productType,
                    granularity = _currentTimeframe.value.restParam,
                    targetCount = bufferCapacity,
                )
                applySnapshot(historical)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot fetch failed (attempt ${attempt + 1}): ${e.message}")
                _snapshotError.value = NetworkErrorClassifier.friendlyMessage(e)
                _pipelineState.value = PipelineState.SNAPSHOT_RETRYING
                val delayMs = min(
                    SNAPSHOT_BASE_RETRY_DELAY_MS * (1 shl min(attempt, 4)),
                    SNAPSHOT_MAX_RETRY_DELAY_MS,
                )
                attempt++
                delay(delayMs)
            }
        }
    }

    private suspend fun applySnapshot(historical: List<Kline>) {
        primeLock.withLock {
            buffer.applyUpdates(historical)

            val queued = tempQueue.toList()
            tempQueue.clear()
            buffer.applyUpdates(queued)

            primed = true
            _klines.value = buffer.snapshot()
            _usingCache.value = false
            _snapshotError.value = null
            _pipelineState.value = PipelineState.LIVE
        }
        cachePersistJob?.cancel()
        cachePersistJob = scope.launch { persistCachePeriodically() }
        Log.d(
            TAG,
            "Snapshot merged: ${historical.size} historical + ${_klines.value.size} " +
                "candles in buffer after replaying queued ticks",
        )
    }

    private fun applyLive(batch: List<Kline>) {
        val snapshot = buffer.applyUpdates(batch)
        _klines.value = snapshot
    }
}