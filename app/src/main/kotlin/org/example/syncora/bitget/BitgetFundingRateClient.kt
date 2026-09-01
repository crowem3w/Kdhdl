package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FundingRateInfo(
    val symbol: String,
    val fundingRate: Double,
    val fundingTimeMs: Long?,
    val nextSettlementMs: Long? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

class BitgetFundingRateClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetFundingRateClient"
        const val BASE_URL = "https://api.bitget.com"
    }

    suspend fun fetchCurrentFundingRate(
        symbol: String = "BTCUSDT",
        productType: String = "usdt-futures",
    ): FundingRateInfo {
        val query = "symbol=$symbol&productType=$productType"
        val json = getPublic("/api/v2/mix/market/current-fund-rate", query)
        val rows = json.optJSONArray("data") ?: throw IOException("Missing funding-rate data for $symbol")
        if (rows.length() == 0) throw IOException("No funding rate returned for $symbol")
        val row = rows.getJSONObject(0)
        val rate = row.optString("fundingRate").toDoubleOrNull()
            ?: throw IOException("current-fund-rate response missing fundingRate")
        val nextUpdate = row.optString("nextUpdate").toLongOrNull()
        return FundingRateInfo(symbol = symbol, fundingRate = rate, fundingTimeMs = null, nextSettlementMs = nextUpdate)
    }

    suspend fun fetchSettledFundingRate(
        atMs: Long,
        symbol: String = "BTCUSDT",
        productType: String = "usdt-futures",
        pageSize: Int = 100,
    ): FundingRateInfo? = fetchFundingRateHistory(symbol, productType, pageSize).firstOrNull { it.fundingTimeMs == atMs }

    suspend fun fetchFundingRateHistory(
        symbol: String = "BTCUSDT",
        productType: String = "usdt-futures",
        pageSize: Int = 100,
    ): List<FundingRateInfo> {
        val query = "symbol=$symbol&productType=$productType&pageSize=$pageSize"
        val json = getPublic("/api/v2/mix/market/history-fund-rate", query)
        val rows = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val rate = row.optString("fundingRate").toDoubleOrNull() ?: continue
                val time = row.optString("fundingTime").toLongOrNull() ?: continue
                add(FundingRateInfo(symbol = symbol, fundingRate = rate, fundingTimeMs = time))
            }
        }
    }

    private suspend fun getPublic(path: String, query: String): JSONObject {
        val request = Request.Builder().url("$BASE_URL$path?$query").get().build()
        return parseResponse(executeAsync(request))
    }

    private fun parseResponse(body: String): JSONObject {
        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw IOException("Bitget funding-rate error ${json.optString("code")}: ${json.optString("msg")}")
        }
        return json
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Funding-rate request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful || text == null) {
                            continuation.resumeWithException(IOException("HTTP ${it.code} fetching funding rate"))
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
