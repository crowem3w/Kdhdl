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

        // Cap on how many candles a single reconnect-gap backfill will
        // request in one shot. Large enough to cover ordinary reconnects
        // (minutes to hours of missed 1m bars) without needing the full
        // multi-page pagination used for the initial deep backfill.
        const val GAP_BACKFILL_MAX_CANDLES = 1_000

        // Cap on how many candles the startup catch-up path will patch in
        // a single REST call. If the cache is staler than this (device was
        // offline/killed for a long stretch), a single request may not
        // reliably cover the gap - fall back to the paginated deep backfill
        // instead of trusting one big range request.
        const val STARTUP_CATCH_UP_MAX_BARS = 1_000
    }

    private val buffer = KlineBuffer(bufferCapacity)

    // Bitget's REST `productType` param is lowercase ("usdt-futures") while
    // the websocket `instType` is upper-snake ("USDT-FUTURES"); derive
    // rather than duplicate so the two can never drift apart.
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

    /**
     * Detects a hole between the newest buffered candle and an incoming
     * live batch - e.g. bars missed while the websocket was disconnected
     * or the process was backgrounded - and patches it with a targeted REST
     * fetch before the live batch is applied, so reconnects don't leave a
     * blank stretch on the chart.
     *
     * Must be called while holding [primeLock] and only once [primed], so
     * the buffer's "last candle" can't move out from under this check.
     */
    private suspend fun backfillGapIfNeeded(incomingBatch: List<Kline>) {
        val barMs = _barDurationMillis.value
        if (barMs <= 0) return

        val earliestIncoming = incomingBatch.minOfOrNull { it.startTime } ?: return
        val lastKnown = buffer.lastStartTimeOrNull() ?: return
        val expectedNext = lastKnown + barMs

        // Nothing missing: the batch picks up right where the buffer left
        // off (or even overlaps/replaces the trailing candle, which is
        // normal for in-progress-bar updates).
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
            // Best-effort: leave the gap for now rather than blocking the
            // live tick that triggered this check. A later reconnect or
            // timeframe switch gets another chance to fill it.
            Log.w(TAG, "Gap backfill failed for [$expectedNext, $earliestIncoming): ${e.message}")
        }
    }

    private suspend fun loadSnapshotWithRetry() {
        var attempt = 0
        while (true) {
            try {
                val historical = acquireStartupSnapshot()
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

    /**
     * Decides how to (re)seed the buffer on start()/timeframe switch.
     *
     * If there's a usable on-device cache for the current granularity, this
     * is real forward gap-filling: fetch only the candles broadcast during
     * whatever window the app was offline, backgrounded, or killed - one
     * targeted REST call bridging [lastCachedCandle, now] - and splice them
     * onto the cache. The websocket (already connecting/streaming via
     * `start()`) then only needs to carry the feed forward from "now"; it
     * never has to be the sole source for bars that arrived while nobody
     * was listening.
     *
     * Falls back to the full paginated deep backfill when there's no cache
     * yet, the cache is already current, the cache belongs to a different
     * granularity (spacing mismatch), or the offline gap is too large to
     * patch in a single request.
     */
    private suspend fun acquireStartupSnapshot(): List<Kline> {
        val barMs = _barDurationMillis.value
        val cached = cacheStore.load()?.filter { it.startTime > 0 }

        suspend fun deepBackfill() = restClient.backfillCandles(
            instId = instId,
            productType = productType,
            granularity = _currentTimeframe.value.restParam,
            targetCount = bufferCapacity,
        )

        if (cached.isNullOrEmpty() || barMs <= 0) {
            Log.d(TAG, "No usable cache for startup catch-up; running full deep backfill")
            return deepBackfill()
        }

        if (cached.size >= 2) {
            val spacing = cached.last().startTime - cached[cached.size - 2].startTime
            if (spacing != barMs) {
                Log.d(
                    TAG,
                    "Cached candle spacing (${spacing}ms) doesn't match current " +
                        "timeframe (${barMs}ms); cache is stale/wrong granularity, " +
                        "running full deep backfill",
                )
                return deepBackfill()
            }
        }

        val lastCachedTime = cached.last().startTime
        val expectedNext = lastCachedTime + barMs
        val now = System.currentTimeMillis()
        val missingBars = (now - expectedNext) / barMs

        if (missingBars <= 0) {
            Log.d(TAG, "Cache already current (last candle $lastCachedTime); no catch-up needed")
            return cached.takeLast(bufferCapacity)
        }

        if (missingBars > STARTUP_CATCH_UP_MAX_BARS) {
            Log.d(
                TAG,
                "Cache is $missingBars bar(s) stale, beyond single-request catch-up cap " +
                    "($STARTUP_CATCH_UP_MAX_BARS); running full deep backfill instead",
            )
            return deepBackfill()
        }

        Log.d(TAG, "Catching up on $missingBars candle(s) missed while offline since $lastCachedTime")
        val caughtUp = try {
            restClient.fetchCandleRange(
                instId = instId,
                productType = productType,
                granularity = _currentTimeframe.value.restParam,
                startTime = expectedNext,
                endTime = now,
                limit = missingBars.coerceAtMost(STARTUP_CATCH_UP_MAX_BARS.toLong()).toInt(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Startup catch-up REST call failed, falling back to deep backfill: ${e.message}")
            return deepBackfill()
        }

        val merged = LinkedHashMap<Long, Kline>()
        for (candle in cached) merged[candle.startTime] = candle
        for (candle in caughtUp) merged[candle.startTime] = candle
        val sorted = merged.values.sortedBy { it.startTime }
        return if (sorted.size > bufferCapacity) sorted.takeLast(bufferCapacity) else sorted
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
