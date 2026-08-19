package org.example.test.bitget

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
    }

    suspend fun fetchRecentCandles(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        granularity: String = "1m",
        limit: Int = 100,
    ): List<Kline> {
        val url = "$BASE_URL?symbol=$instId&granularity=$granularity&limit=$limit&productType=$productType"
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
