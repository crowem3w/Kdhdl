package org.example.test.bitget

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

/**
 * A single funding-rate reading for a perpetual.
 *
 * [fundingTimeMs] is the settlement timestamp the rate actually applies to
 * - set for every entry from [BitgetFundingRateClient.fetchFundingRateHistory]
 * (a rate that has already settled), and null from
 * [BitgetFundingRateClient.fetchCurrentFundingRate] (the live rate that
 * *will* settle at [nextSettlementMs], which hasn't happened yet).
 */
data class FundingRateInfo(
    val symbol: String,
    val fundingRate: Double,
    val fundingTimeMs: Long?,
    val nextSettlementMs: Long? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * Pulls real funding-rate data for `BTCUSDTP`-style USDT-margined
 * perpetuals from Bitget's public market-data API - design doc §7
 * ("Funding Accrual"). Both endpoints here are unauthenticated public
 * market data, exactly like [BitgetFeeRateClient]'s standard-tier fetch -
 * no trading permissions required, same as the rest of this local paper
 * trading engine.
 */
class BitgetFundingRateClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetFundingRateClient"
        const val BASE_URL = "https://api.bitget.com"
    }

    /**
     * The live, not-yet-settled funding rate that will apply at the next
     * settlement ([FundingRateInfo.nextSettlementMs]) - the best estimate
     * of what an open position is about to be charged/paid, per doc §7
     * step 1. This is what a settlement happening *right now* should use,
     * since Bitget's history endpoint won't have that settlement's actual
     * rate recorded until after it lands.
     */
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

    /**
     * The actual settled rate for one specific past funding timestamp -
     * used to catch up accurately (rather than approximating with
     * whatever the live rate happens to be now) on settlements that
     * occurred while the app wasn't running. Returns null instead of
     * throwing if no history entry matches [atMs] exactly (e.g. it falls
     * outside the window Bitget's history endpoint returns), so the
     * caller can fall back to [fetchCurrentFundingRate].
     */
    suspend fun fetchSettledFundingRate(
        atMs: Long,
        symbol: String = "BTCUSDT",
        productType: String = "usdt-futures",
        pageSize: Int = 100,
    ): FundingRateInfo? = fetchFundingRateHistory(symbol, productType, pageSize).firstOrNull { it.fundingTimeMs == atMs }

    /** Most-recent-first list of actually-settled historical funding rates. */
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
