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

data class FeeRates(
    val makerRate: Double,
    val takerRate: Double,
    val isAccountSpecific: Boolean,
    val fetchedAt: Long,
) {
    companion object {
        val DEFAULT = FeeRates(makerRate = 0.0002, takerRate = 0.0006, isAccountSpecific = false, fetchedAt = 0L)
    }
}

class BitgetFeeRateClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetFeeRateClient"
        const val BASE_URL = "https://api.bitget.com"
    }

    suspend fun fetchStandardFeeRates(
        symbol: String = "BTCUSDT",
        productType: String = "usdt-futures",
    ): FeeRates {
        val query = "symbol=$symbol&productType=$productType"
        val json = getPublic("/api/v2/mix/market/contracts", query)
        val rows = json.optJSONArray("data") ?: throw IOException("Missing contract config data for $symbol")
        if (rows.length() == 0) throw IOException("No contract config returned for $symbol")
        val row = rows.getJSONObject(0)
        val maker = row.optString("makerFeeRate").toDoubleOrNull()
        val taker = row.optString("takerFeeRate").toDoubleOrNull()
        if (maker == null || taker == null) throw IOException("Contract config missing fee rate fields")
        return FeeRates(makerRate = maker, takerRate = taker, isAccountSpecific = false, fetchedAt = System.currentTimeMillis())
    }

    suspend fun fetchAccountFeeRates(
        credentials: BitgetCredentials,
        symbol: String = "BTCUSDT",
        businessType: String = "contract",
    ): FeeRates {
        val path = "/api/v2/common/trade-rate"
        val query = "symbol=$symbol&businessType=$businessType"
        val timestamp = System.currentTimeMillis().toString()
        val requestPath = "$path?$query"
        val sign = BitgetRequestSigner.sign(credentials.secretKey, timestamp, "GET", requestPath, "")
        val request = Request.Builder()
            .url("$BASE_URL$requestPath")
            .get()
            .header("ACCESS-KEY", credentials.apiKey)
            .header("ACCESS-SIGN", sign)
            .header("ACCESS-TIMESTAMP", timestamp)
            .header("ACCESS-PASSPHRASE", credentials.passphrase)
            .header("Content-Type", "application/json")
            .header("locale", "en-US")
            .build()
        val json = parseResponse(executeAsync(request))
        val data = json.optJSONObject("data") ?: throw IOException("Missing trade-rate data")
        val maker = data.optString("makerFeeRate").toDoubleOrNull()
        val taker = data.optString("takerFeeRate").toDoubleOrNull()
        if (maker == null || taker == null) throw IOException("trade-rate response missing fee rate fields")
        return FeeRates(makerRate = maker, takerRate = taker, isAccountSpecific = true, fetchedAt = System.currentTimeMillis())
    }

    private suspend fun getPublic(path: String, query: String): JSONObject {
        val request = Request.Builder().url("$BASE_URL$path?$query").get().build()
        return parseResponse(executeAsync(request))
    }

    private fun parseResponse(body: String): JSONObject {
        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw IOException("Bitget fee-rate error ${json.optString("code")}: ${json.optString("msg")}")
        }
        return json
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Fee-rate request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful || text == null) {
                            continuation.resumeWithException(IOException("HTTP ${it.code} fetching fee rate"))
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
