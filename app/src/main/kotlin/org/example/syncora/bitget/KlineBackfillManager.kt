package org.example.syncora.bitget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.syncora.storage.ObjectBoxKlineStore

sealed interface BackfillState {
    data object Idle : BackfillState

    data class Running(
        val pagesLoaded: Int,
        val candlesStored: Long,
        val oldestSoFar: Long?,
    ) : BackfillState

    data class Completed(
        val candlesStored: Long,
        val oldestTimestamp: Long?,
    ) : BackfillState

    data class Failed(
        val message: String,
        val candlesStoredBeforeFailure: Long,
    ) : BackfillState
}

/**
 * Walks Bitget's `history-candles` endpoint backwards in time - one page
 * at a time via [BitgetKlineHistoryClient] - and writes every page into
 * [store] as it arrives, so an interrupted backfill (app killed, network
 * drop, rate limit) resumes from wherever it left off instead of
 * re-downloading from scratch.
 *
 * Scope for now: 1-minute candles for a single symbol at a time. Nothing
 * here is 1m-specific though - [Timeframe] already carries a `restParam`
 * for every granularity the app supports, so widening this to other
 * timeframes later is just passing a different `granularity`.
 */
class KlineBackfillManager(
    private val store: ObjectBoxKlineStore,
    private val historyClient: BitgetKlineHistoryClient = BitgetKlineHistoryClient(),
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val _state = MutableStateFlow<BackfillState>(BackfillState.Idle)
    val state: StateFlow<BackfillState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Starts (or resumes) a backfill. A no-op if one is already running.
     *
     * @param targetStartTimeMs stop once candles at or before this time
     *   have been fetched. Null means "go as far back as Bitget has data" -
     *   keep paging until the API returns an empty or short page.
     * @param pageLimit candles requested per call.
     * @param throttleMs delay between calls, to stay well under Bitget's
     *   public rate limit instead of firing the pagination loop as fast as
     *   possible.
     */
    fun start(
        symbol: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = Timeframe.ONE_MINUTE.restParam,
        targetStartTimeMs: Long? = null,
        pageLimit: Int = 200,
        throttleMs: Long = 200,
    ) {
        if (job?.isActive == true) return

        job = externalScope.launch {
            var candlesStored = 0L
            var pagesLoaded = 0
            var oldestSoFar: Long? = null

            try {
                // Resume point: if candles are already stored, don't
                // re-download anything at or newer than the oldest one we
                // have - start paging from just before it. Otherwise start
                // from now and work backwards.
                var cursorEndTime = store.earliestStartTime(symbol, granularity)
                    ?.let { it - 1 }
                    ?: System.currentTimeMillis()

                _state.value = BackfillState.Running(pagesLoaded, candlesStored, oldestSoFar)

                while (true) {
                    val page = fetchWithRetry(symbol, productType, granularity, cursorEndTime, pageLimit)

                    if (page.isEmpty()) {
                        // Nothing older than cursorEndTime - reached the
                        // earliest history Bitget has for this contract.
                        break
                    }

                    store.upsertAll(symbol, granularity, page)
                    candlesStored += page.size
                    pagesLoaded += 1
                    oldestSoFar = page.first().startTime
                    _state.value = BackfillState.Running(pagesLoaded, candlesStored, oldestSoFar)

                    val reachedTarget = targetStartTimeMs != null && oldestSoFar <= targetStartTimeMs
                    val pageWasShort = page.size < pageLimit
                    if (reachedTarget || pageWasShort) break

                    // Step the cursor to just before the oldest candle we
                    // just received, so the next page picks up right before
                    // it with no gap and no re-fetched overlap.
                    cursorEndTime = oldestSoFar - 1
                    delay(throttleMs)
                }

                _state.value = BackfillState.Completed(candlesStored, oldestSoFar)
            } catch (e: Exception) {
                _state.value = BackfillState.Failed(e.message ?: "Unknown error", candlesStored)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun fetchWithRetry(
        symbol: String,
        productType: String,
        granularity: String,
        endTimeMs: Long,
        limit: Int,
        maxAttempts: Int = 3,
    ): List<Kline> {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return historyClient.fetchHistoryCandles(
                    instId = symbol,
                    productType = productType,
                    granularity = granularity,
                    endTimeMs = endTimeMs,
                    limit = limit,
                )
            } catch (e: Exception) {
                lastError = e
                delay(500L shl attempt) // 1000ms, 2000ms, 4000ms
            }
        }
        throw lastError ?: IllegalStateException("fetchWithRetry exhausted with no error recorded")
    }
}
