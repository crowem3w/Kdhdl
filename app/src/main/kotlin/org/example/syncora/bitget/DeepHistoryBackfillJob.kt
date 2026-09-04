package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BackfillState {
    IDLE,
    RUNNING,
    RATE_LIMITED_RETRYING,
    COMPLETE,
    FAILED,
}

data class BackfillProgress(
    val state: BackfillState = BackfillState.IDLE,
    val candlesDownloaded: Long = 0,
    val oldestTimestampSoFar: Long? = null,
    val errorMessage: String? = null,
)

/**
 * One-time (or resumable) full-history download for a single instrument /
 * granularity, running completely independently of [TradingChartPipeline]'s
 * lifecycle - it never touches `KlineBuffer`, `_klines`, or the live cache.
 *
 * Persists candles into [KlineArchiveStore] as pages arrive rather than
 * holding the whole (multi-million-row) history in memory, and survives
 * process death via the store's resume cursor rather than by keeping this
 * job's coroutine alive - calling [run] again after a restart picks back up
 * wherever the last successfully-persisted page left off.
 */
class DeepHistoryBackfillJob(
    private val restClient: BitgetKlineRestClient,
    private val archiveStore: KlineArchiveStore,
    private val instId: String = "BTCUSDT",
    private val productType: String = "usdt-futures",
    private val granularity: String = "1m",
) {
    private companion object {
        const val TAG = "DeepHistoryBackfill"
    }

    val cacheKey: String = "${instId}_${productType}_$granularity"

    private val _progress = MutableStateFlow(BackfillProgress())
    val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** True while a run is actively in flight (including a rate-limit backoff pause). */
    val isRunning: Boolean
        get() = _progress.value.state == BackfillState.RUNNING ||
            _progress.value.state == BackfillState.RATE_LIMITED_RETRYING

    /**
     * Starts (or resumes) the walk on a background dispatcher. Safe to call
     * again after a prior [FAILED][BackfillState.FAILED] run - it resumes
     * from [KlineArchiveStore]'s persisted cursor rather than starting over.
     * A no-op if already running.
     */
    fun start() {
        if (isRunning) return
        job?.cancel()
        job = scope.launch { run() }
    }

    /**
     * Every candle stored in the archive so far, oldest first. Used by the
     * "Export full archive as CSV" path in [org.example.syncora.ui.HistoricalDataDialog] -
     * the only reader of [KlineArchiveStore] besides this job itself.
     */
    suspend fun loadArchivedCandles(): List<Kline> = archiveStore.loadAll(cacheKey)

    /**
     * Stops the in-flight walk without losing progress - the resume cursor
     * was already persisted after the last successful page, so a later
     * [start] picks back up from there.
     */
    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value.copy(state = BackfillState.IDLE)
    }

    suspend fun run() {
        val alreadyStored = archiveStore.countStored(cacheKey)
        val resumeCursor = archiveStore.loadResumeCursor(cacheKey)
        _progress.value = BackfillProgress(
            state = BackfillState.RUNNING,
            candlesDownloaded = alreadyStored,
            oldestTimestampSoFar = archiveStore.earliestStoredStartTime(cacheKey),
        )

        try {
            val finalCursor = restClient.walkFullHistory(
                instId = instId,
                productType = productType,
                granularity = granularity,
                resumeFromCursor = resumeCursor,
            ) { page ->
                archiveStore.appendPage(cacheKey, page)
                val oldest = page.first().startTime
                archiveStore.saveResumeCursor(cacheKey, oldest)
                _progress.value = _progress.value.copy(
                    state = BackfillState.RUNNING,
                    candlesDownloaded = _progress.value.candlesDownloaded + page.size,
                    oldestTimestampSoFar = oldest,
                )
            }

            if (finalCursor == null) {
                // walkFullHistory returned null because history-candles
                // started returning empty pages - the true start of
                // available history for this instrument/granularity (see
                // blueprint §6). Nothing left to resume.
                archiveStore.clearResumeCursor(cacheKey)
                _progress.value = _progress.value.copy(state = BackfillState.COMPLETE, errorMessage = null)
                Log.d(TAG, "Deep history backfill complete for '$cacheKey': reached natural start of history")
            } else {
                // Hit the page-count safety ceiling without exhausting
                // history - treat as done for this run; a subsequent
                // start() will resume and keep walking further back.
                _progress.value = _progress.value.copy(state = BackfillState.COMPLETE, errorMessage = null)
                Log.d(TAG, "Deep history backfill paused for '$cacheKey' at cursor=$finalCursor (safety ceiling)")
            }
        } catch (e: Exception) {
            // A page failed after exhausting its own retries (see
            // BitgetKlineRestClient.walkFullHistory) - the resume cursor
            // from the last *successful* page is already persisted, so at
            // most one page of progress is lost. Surface this distinctly
            // from "reached natural start of history" so the UI doesn't
            // conflate a network hiccup with actually being done.
            Log.w(TAG, "Deep history backfill for '$cacheKey' stopped: ${e.message}")
            _progress.value = _progress.value.copy(
                state = BackfillState.FAILED,
                errorMessage = NetworkErrorClassifier.friendlyMessage(e),
            )
        }
    }
}
