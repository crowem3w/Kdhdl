package org.example.test.splash

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Times a minimal round trip to the exchange's public server-time endpoint so the splash
 * screen's loading-milestone animation can pace itself to how the network is actually
 * behaving right now, instead of running at one fixed speed regardless of connection
 * quality. Deliberately hits the lightest endpoint available — a few bytes of JSON, no
 * auth, no heavy query — so what we measure is close to pure round-trip latency rather
 * than server processing time.
 */
class NetworkLatencyProbe(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(),
) {

    /**
     * Round-trip time in milliseconds, or null if the probe failed outright (e.g. no
     * connection at all). Callers should treat null the same as a slow connection.
     */
    suspend fun measureRoundTripMs(): Long? {
        val request = Request.Builder().url(PING_URL).get().build()
        val startedAt = System.currentTimeMillis()
        return try {
            executeAsync(request)
            System.currentTimeMillis() - startedAt
        } catch (e: IOException) {
            Log.w(TAG, "Network latency probe failed: ${e.message}")
            null
        }
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(IOException("HTTP ${it.code} pinging $PING_URL"))
                        } else {
                            continuation.resume(it.body?.string().orEmpty())
                        }
                    }
                }
            })
        }

    private companion object {
        const val TAG = "NetworkLatencyProbe"
        const val PING_URL = "https://api.bitget.com/api/v2/public/time"

        // Hard ceiling on how long we wait for the probe itself before giving up and
        // treating the connection as slow.
        const val PROBE_TIMEOUT_MS = 1_500L
    }
}
