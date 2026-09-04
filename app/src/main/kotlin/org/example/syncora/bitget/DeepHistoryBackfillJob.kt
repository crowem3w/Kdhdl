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

    
    val isRunning: Boolean
        get() = _progress.value.state == BackfillState.RUNNING ||
            _progress.value.state == BackfillState.RATE_LIMITED_RETRYING

    





    fun start() {
        if (isRunning) return
        job?.cancel()
        job = scope.launch { run() }
    }

    




    suspend fun loadArchivedCandles(): List<Kline> = archiveStore.loadAll(cacheKey)

    




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
                
                
                
                
                archiveStore.clearResumeCursor(cacheKey)
                _progress.value = _progress.value.copy(state = BackfillState.COMPLETE, errorMessage = null)
                Log.d(TAG, "Deep history backfill complete for '$cacheKey': reached natural start of history")
            } else {
                
                
                
                _progress.value = _progress.value.copy(state = BackfillState.COMPLETE, errorMessage = null)
                Log.d(TAG, "Deep history backfill paused for '$cacheKey' at cursor=$finalCursor (safety ceiling)")
            }
        } catch (e: Exception) {
            
            
            
            
            
            
            Log.w(TAG, "Deep history backfill for '$cacheKey' stopped: ${e.message}")
            _progress.value = _progress.value.copy(
                state = BackfillState.FAILED,
                errorMessage = NetworkErrorClassifier.friendlyMessage(e),
            )
        }
    }
}