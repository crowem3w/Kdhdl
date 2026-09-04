package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

class BitgetKlineRestClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetKlineRest"
        const val BASE_URL = "https://api.bitget.com/api/v2/mix/market/candles"
        const val HISTORY_URL = "https://api.bitget.com/api/v2/mix/market/history-candles"
        const val HISTORY_PAGE_LIMIT = 200
        const val BACKFILL_PAGE_BUDGET_SLACK = 5
        const val MAX_BACKFILL_PAGES_HARD_CEILING = 2_000

        // Outer safety valve for walkFullHistory - NOT a functional limit
        // the way MAX_BACKFILL_PAGES_HARD_CEILING is for backfillCandles.
        // ~3.77M 1m candles / 200 per page ≈ 18,850 pages for the full
        // 2019-to-now walk; this just stops a runaway loop (e.g. a cursor
        // that somehow stops decreasing) well beyond that.
        const val WALK_FULL_HISTORY_PAGE_CEILING = 50_000

        // Per-page retry policy for walkFullHistory: a single transient
        // failure should cost that page a few retries, not the whole
        // multi-hour job (see backfillCandles' catch { break }, which is
        // fine for a small bounded backfill but wrong here).
        const val WALK_RETRY_MAX_ATTEMPTS = 5
        const val WALK_RETRY_BASE_DELAY_MS = 1_000L
        const val WALK_RETRY_MAX_DELAY_MS = 4_000L

        // Spacing between page requests so an ~18,850-request walk stays
        // well under Bitget's public REST rate limit.
        const val WALK_INTER_PAGE_DELAY_MS = 150L
    }

    suspend fun fetchRecentCandles(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = "1m",
        limit: Int = 100,
    ): List<Kline> = fetchCandlePage(
        baseUrl = BASE_URL,
        instId = instId,
        productType = productType,
        granularity = granularity,
        limit = limit,
        endTime = null,
    )

    
    suspend fun backfillCandles(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = "1m",
        targetCount: Int,
    ): List<Kline> {
        val collected = LinkedHashMap<Long, Kline>()

        val recent = fetchCandlePage(
            baseUrl = BASE_URL,
            instId = instId,
            productType = productType,
            granularity = granularity,
            limit = targetCount.coerceAtMost(1000),
            endTime = null,
        )
        for (candle in recent) collected[candle.startTime] = candle

        var cursor = collected.keys.minOrNull()
        var pages = 0
        val maxPages = ((targetCount / HISTORY_PAGE_LIMIT) + BACKFILL_PAGE_BUDGET_SLACK)
            .coerceAtMost(MAX_BACKFILL_PAGES_HARD_CEILING)

        while (collected.size < targetCount && cursor != null && pages < maxPages) {
            pages++
            val page = try {
                fetchCandlePage(
                    baseUrl = HISTORY_URL,
                    instId = instId,
                    productType = productType,
                    granularity = granularity,
                    limit = HISTORY_PAGE_LIMIT,
                    endTime = cursor - 1,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Backfill page $pages failed at cursor=$cursor: ${e.message}")
                break
            }
            if (page.isEmpty()) {
                Log.d(TAG, "Backfill reached start of available history after $pages page(s)")
                break
            }
            for (candle in page) collected[candle.startTime] = candle
            val oldestInPage = page.first().startTime
            if (oldestInPage >= cursor) {
                break
            }
            cursor = oldestInPage
        }

        val sorted = collected.values.sortedBy { it.startTime }
        return if (sorted.size > targetCount) sorted.takeLast(targetCount) else sorted
    }
    /**
     * Unbounded, streaming, resumable walk back through `/history-candles`,
     * distinct from [backfillCandles] (which stays as-is for the small,
     * `targetCount`-bounded live-buffer use case).
     *
     * Differences from [backfillCandles]:
     * - No `targetCount` - the loop runs purely on `cursor != null &&
     *   page.isNotEmpty()`, terminating naturally when Bitget's
     *   `history-candles` endpoint starts returning empty pages (the real
     *   start of available history for this instrument/granularity - see
     *   blueprint §6, that may or may not land on the instrument's listing
     *   date).
     * - Pages are handed off to [onPage] as they arrive instead of being
     *   accumulated in memory - millions of candles never sit in a single
     *   `LinkedHashMap` at once.
     * - A failed page is retried with exponential backoff
     *   ([WALK_RETRY_MAX_ATTEMPTS] attempts) before giving up on *that page
     *   only*; if every retry fails, this throws rather than silently
     *   ending the walk, so the caller can tell "transient failure, job
     *   should resume later" apart from "reached natural start of
     *   history". Because [onPage] is invoked (and, by the caller,
     *   persisted) after every successful page, resuming after such a
     *   failure picks up right where it left off - at most one page of
     *   progress is ever at risk.
     * - [WALK_FULL_HISTORY_PAGE_CEILING] is a sane outer safety valve
     *   against a runaway loop, not a functional limit the way
     *   `MAX_BACKFILL_PAGES_HARD_CEILING` is for `backfillCandles`.
     *
     * @param resumeFromCursor `startTime` to resume walking backward from
     *   (typically the oldest `startTime` seen by a prior, interrupted run),
     *   or `null` to start from "now".
     * @param onPage invoked once per successfully-fetched page, oldest page
     *   last; the caller is expected to persist it (and its own resume
     *   cursor) before returning, since nothing else retains this data.
     * @return the final cursor reached, or `null` if the walk terminated
     *   because it hit the natural start of history (`page.isEmpty()`).
     * @throws Exception if a page still fails after exhausting retries -
     *   the walk stops there rather than treating a transient/rate-limit
     *   failure as "reached the start of history".
     */
    suspend fun walkFullHistory(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = "1m",
        resumeFromCursor: Long? = null,
        onPage: suspend (List<Kline>) -> Unit,
    ): Long? {
        var cursor: Long? = resumeFromCursor
        var pages = 0

        if (cursor == null) {
            // No resume point yet: anchor the walk on the most recent
            // candle so the very first history-candles page requested is
            // "just before now", exactly like backfillCandles does via its
            // initial fetchRecentCandles call.
            val recent = fetchCandlePage(
                baseUrl = BASE_URL,
                instId = instId,
                productType = productType,
                granularity = granularity,
                limit = 1,
                endTime = null,
            )
            val newest = recent.firstOrNull() ?: return null
            onPage(recent)
            cursor = newest.startTime
        }

        while (cursor != null && pages < WALK_FULL_HISTORY_PAGE_CEILING) {
            pages++
            val page = fetchHistoryPageWithRetry(
                instId = instId,
                productType = productType,
                granularity = granularity,
                endTime = cursor - 1,
                pageNumber = pages,
            )
            if (page.isEmpty()) {
                Log.d(TAG, "walkFullHistory reached natural start of history after $pages page(s)")
                return null
            }

            onPage(page)

            val oldestInPage = page.first().startTime
            if (oldestInPage >= cursor) {
                // Cursor isn't moving backward any more - stop rather than
                // loop forever on the same page.
                return oldestInPage
            }
            cursor = oldestInPage

            delay(WALK_INTER_PAGE_DELAY_MS)
        }

        if (pages >= WALK_FULL_HISTORY_PAGE_CEILING) {
            Log.w(TAG, "walkFullHistory hit its $WALK_FULL_HISTORY_PAGE_CEILING-page safety ceiling; stopping")
        }
        return cursor
    }

    private suspend fun fetchHistoryPageWithRetry(
        instId: String,
        productType: String,
        granularity: String,
        endTime: Long,
        pageNumber: Int,
    ): List<Kline> {
        var attempt = 0
        while (true) {
            try {
                return fetchCandlePage(
                    baseUrl = HISTORY_URL,
                    instId = instId,
                    productType = productType,
                    granularity = granularity,
                    limit = HISTORY_PAGE_LIMIT,
                    endTime = endTime,
                )
            } catch (e: Exception) {
                attempt++
                if (attempt >= WALK_RETRY_MAX_ATTEMPTS) {
                    Log.w(
                        TAG,
                        "walkFullHistory page $pageNumber failed after $attempt attempt(s) at " +
                            "endTime=$endTime, giving up on this run: ${e.message}",
                    )
                    throw e
                }
                val delayMs = (WALK_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)))
                    .coerceAtMost(WALK_RETRY_MAX_DELAY_MS)
                Log.w(
                    TAG,
                    "walkFullHistory page $pageNumber failed (attempt $attempt/" +
                        "$WALK_RETRY_MAX_ATTEMPTS), retrying in ${delayMs}ms: ${e.message}",
                )
                delay(delayMs)
            }
        }
    }

    suspend fun fetchCandleRange(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = "1m",
        startTime: Long,
        endTime: Long,
        limit: Int = 200,
    ): List<Kline> {
        val url = "$BASE_URL?symbol=$instId&granularity=$granularity&limit=$limit" +
            "&productType=$productType&startTime=$startTime&endTime=$endTime"
        return executeCandleRequest(url)
    }

    private suspend fun fetchCandlePage(
        baseUrl: String,
        instId: String,
        productType: String,
        granularity: String,
        limit: Int,
        endTime: Long?,
    ): List<Kline> {
        val url = buildString {
            append(baseUrl)
            append("?symbol=$instId&granularity=$granularity&limit=$limit&productType=$productType")
            if (endTime != null) append("&endTime=$endTime")
        }
        return executeCandleRequest(url)
    }

    private suspend fun executeCandleRequest(url: String): List<Kline> {
        val request = Request.Builder().url(url).get().build()
        val body = executeAsync(request)

        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw IOException("Bitget REST error ${json.optString("code")}: ${json.optString("msg")}")
        }

        val rows = json.getJSONArray("data")
        val candles = buildList(rows.length()) {
            for (i in 0 until rows.length()) {
                add(Kline.fromRestJsonArray(rows.getJSONArray(i)))
            }
        }

        return candles.sortedBy { it.startTime }
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "REST snapshot request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful || text == null) {
                            continuation.resumeWithException(
                                IOException("HTTP ${it.code} fetching candle snapshot")
                            )
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
