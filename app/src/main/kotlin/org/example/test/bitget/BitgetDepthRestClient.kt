package org.example.test.bitget

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

data class DepthRestSnapshot(
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
)

class BitgetDepthRestClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetDepthRest"
        const val BASE_URL = "https://api.bitget.com/api/v2/mix/market/merge-depth"
    }

    suspend fun fetchAggregatedDepth(
        instId: String = "BTCUSDT",
        productType: String = "usdt-futures",
        precision: String = "scale0",
        limit: Int = 500,
    ): DepthRestSnapshot {
        val url = "$BASE_URL?symbol=$instId&productType=$productType&precision=$precision&limit=$limit"
        val request = Request.Builder().url(url).get().build()
        val body = executeAsync(request)

        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw IOException("Bitget REST error ${json.optString("code")}: ${json.optString("msg")}")
        }

        val data = json.getJSONObject("data")
        val bidsArr = data.getJSONArray("bids")
        val asksArr = data.getJSONArray("asks")
        return DepthRestSnapshot(
            bids = buildList(bidsArr.length()) {
                for (i in 0 until bidsArr.length()) add(OrderBookLevel.fromWsJsonArray(bidsArr.getJSONArray(i)))
            },
            asks = buildList(asksArr.length()) {
                for (i in 0 until asksArr.length()) add(OrderBookLevel.fromWsJsonArray(asksArr.getJSONArray(i)))
            },
        )
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "REST aggregated depth request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful || text == null) {
                            continuation.resumeWithException(
                                IOException("HTTP ${it.code} fetching aggregated depth")
                            )
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
