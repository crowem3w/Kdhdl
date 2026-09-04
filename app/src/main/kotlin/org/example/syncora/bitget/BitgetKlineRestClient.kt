package org.example.syncora.bitget

import android.util.Log
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

        // Deeper, further-back history than /candles is willing to serve.
        // Paginated backwards via `endTime` to assemble more than one
        // page's worth of history (see backfillCandles).
        const val HISTORY_URL = "https://api.bitget.com/api/v2/mix/market/history-candles"

        // Conservative per-request page size for the history endpoint.
        // Bitget's documented examples cap this endpoint around 200; each
        // page is a separate REST round trip when paginating.
        const val HISTORY_PAGE_LIMIT = 200

        // Hard safety cap on how many pages a single backfill will walk,
        // so a misbehaving API (e.g. always returning a full page but never
        // advancing) can't turn into an unbounded request storm.
        const val MAX_BACKFILL_PAGES = 50
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

    /**
     * Fetches real historical depth for [granularity], beyond what a single
     * /candles call returns, by walking the /history-candles endpoint
     * backwards in time.
     *
     * Strategy:
     *  1. Grab the most recent page from /candles (cheap, always fresh).
     *  2. If more candles are still needed to reach [targetCount], keep
     *     requesting older pages from /history-candles - each page's
     *     `endTime` set just before the oldest candle seen so far - until
     *     either the target is met, the exchange returns an empty page
     *     (we've hit the start of available history), or [MAX_BACKFILL_PAGES]
     *     is reached.
     *
     * Returns candles sorted ascending by start time, trimmed to at most
     * the most recent [targetCount] bars.
     */
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
        while (collected.size < targetCount && cursor != null && pages < MAX_BACKFILL_PAGES) {
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
                // Defensive: cursor failed to move backwards, avoid looping forever.
                break
            }
            cursor = oldestInPage
        }

        val sorted = collected.values.sortedBy { it.startTime }
        return if (sorted.size > targetCount) sorted.takeLast(targetCount) else sorted
    }

    /**
     * Fetches candles strictly within [startTime, endTime] (both millis,
     * inclusive-ish per Bitget's semantics). Used to patch a specific gap -
     * e.g. bars missed while the websocket was reconnecting - rather than
     * re-pulling a whole snapshot.
     */
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
