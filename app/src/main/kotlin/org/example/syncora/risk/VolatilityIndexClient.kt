package org.example.syncora.risk

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
 * One reading of the chosen volatility index (see [VolatilityIndexClient]
 * kdoc for which provider and why), independent of anything the trading
 * policy computes.
 */
data class VolatilityReading(
    val value: Double,
    /** The timestamp the exchange attaches to this reading (candle close time), not when we fetched it. */
    val asOfMs: Long,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)

class VolatilityIndexUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sources BTC volatility from **Deribit's DVOL index** - the specific,
 * named provider this app uses for the design doc's §5 volatility circuit
 * breaker. The doc explicitly rejected reusing the original paper's
 * unsourced "CVIX" figure and required picking one real provider (CVI/COTI,
 * CVX, and CME's BVX were all named as *not* generic placeholders, but none
 * of them are actually a BTC volatility index). DVOL is chosen instead
 * because it is:
 *
 * - The de-facto standard implied-volatility index for BTC specifically
 *   (an options-IV index, the same methodology family as VIX, just built
 *   off Deribit's own BTC options order book rather than equity index
 *   options) - a direct match for this app's single-instrument BTCUSDT scope.
 * - Published via a public, unauthenticated REST endpoint with historical
 *   data available for threshold calibration (see [VolatilityCircuitBreaker]).
 *
 * This client is independent of Bitget entirely (a different venue, on
 * purpose) and independent of the RL policy - nothing about its output can
 * be influenced by policy weights, decision-loop state, or anything else
 * downstream of it. It only ever reads Deribit's public market data.
 *
 * **Endpoint note:** uses `/api/v2/public/get_volatility_index_data`
 * (Deribit's public historical-DVOL-candle endpoint) rather than a
 * "current value" endpoint, and treats the most recent candle's close as
 * the current reading. If Deribit's API surface for this has moved since
 * this was written, [fetchLatest] fails loudly (see
 * [VolatilityIndexUnavailableException]) rather than silently returning a
 * stale or fabricated number - callers (see [VolatilityCircuitBreaker])
 * are required to treat a failure here as "unknown," which fails safe into
 * *blocking* new entries, not permitting them.
 */
class VolatilityIndexClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val currency: String = "BTC",
) {
    private companion object {
        const val TAG = "VolatilityIndexClient"
        const val BASE_URL = "https://www.deribit.com"
        // 1-minute candles; look back a small window so a single dropped
        // candle doesn't produce a hard failure, and take the most recent
        // one as "current."
        const val LOOKBACK_MS = 5 * 60_000L
        const val RESOLUTION_SECONDS = "60"
    }

    /**
     * Fetches the latest available DVOL reading for [currency]. Throws
     * [VolatilityIndexUnavailableException] (or an [IOException] from the
     * underlying HTTP call) rather than returning a best-guess value - see
     * class kdoc on why callers must treat that as "unknown," not "safe."
     */
    suspend fun fetchLatest(): VolatilityReading {
        val end = System.currentTimeMillis()
        val start = end - LOOKBACK_MS
        val query = "currency=$currency&start_timestamp=$start&end_timestamp=$end&resolution=$RESOLUTION_SECONDS"
        val json = get("/api/v2/public/get_volatility_index_data", query)

        val result = json.optJSONObject("result")
            ?: throw VolatilityIndexUnavailableException("Deribit DVOL response missing 'result'")
        val data = result.optJSONArray("data")
        if (data == null || data.length() == 0) {
            throw VolatilityIndexUnavailableException("Deribit DVOL response had no candles in the lookback window")
        }

        // Each row is [timestamp, open, high, low, close]; take the most recent (last) row.
        val lastRow = data.optJSONArray(data.length() - 1)
            ?: throw VolatilityIndexUnavailableException("Malformed DVOL candle row")
        val timestamp = lastRow.optLong(0, -1L)
        val close = lastRow.optDouble(4, Double.NaN)
        if (timestamp <= 0L || close.isNaN() || close < 0.0) {
            throw VolatilityIndexUnavailableException("Malformed DVOL candle values: timestamp=$timestamp close=$close")
        }
        return VolatilityReading(value = close, asOfMs = timestamp)
    }

    private suspend fun get(path: String, query: String): JSONObject {
        val request = Request.Builder().url("$BASE_URL$path?$query").get().build()
        return parseResponse(executeAsync(request))
    }

    private fun parseResponse(body: String): JSONObject {
        val json = JSONObject(body)
        if (json.has("error") && !json.isNull("error")) {
            val err = json.optJSONObject("error")
            val message = err?.optString("message")?.takeIf { it.isNotBlank() } ?: json.optString("error")
            throw VolatilityIndexUnavailableException("Deribit error: $message")
        }
        return json
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "DVOL request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string()
                        if (!it.isSuccessful || text == null) {
                            continuation.resumeWithException(IOException("HTTP ${it.code} fetching DVOL"))
                        } else {
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}
