package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bitget's dedicated historical-candle endpoint (`history-candles`), as
 * opposed to [BitgetKlineRestClient] which hits the "recent candles"
 * endpoint. Bitget splits these two on purpose - `candles` only serves a
 * shallow recent window, `history-candles` is what actually lets you page
 * back further. Used by [KlineBackfillManager] to walk the full history.
 */
class BitgetKlineHistoryClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetKlineHistory"
        const val BASE_URL = "https://api.bitget.com/api/v2/mix/market/history-candles"
    }

    /**
     * Fetches candles ending at (not including anything newer than)
     * [endTimeMs], oldest-first (same ordering convention as
     * [BitgetKlineRestClient.fetchRecentCandles]).
     *
     * Bitget doesn't consistently publish one hard maximum for [limit]
     * across product types, so [KlineBackfillManager]'s pagination loop
     * keys off how many rows actually come back rather than assuming the
     * requested limit was honored exactly - this stays correct either way.
     */
    suspend fun fetchHistoryCandles(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = "1m",
        endTimeMs: Long,
        startTimeMs: Long? = null,
        limit: Int = 200,
    ): List<Kline> {
        val url = buildString {
            append(BASE_URL)
            append("?symbol=").append(instId)
            append("&granularity=").append(granularity)
            append("&endTime=").append(endTimeMs)
            append("&limit=").append(limit)
            append("&productType=").append(productType)
            if (startTimeMs != null) {
                append("&startTime=").append(startTimeMs)
            }
        }

        val request = Request.Builder().url(url).get().build()
        val body = executeAsync(request)

        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw IOException("Bitget history-candles error ${json.optString("code")}: ${json.optString("msg")}")
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
                    Log.w(TAG, "history-candles request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful || text == null) {
                            continuation.resumeWithException(
                                IOException("HTTP ${it.code} fetching history candles")
                            )
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
