package org.example.test.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class BitgetDepthSocket(
    private val instId: String = "BTCUSDT",
    private val instType: String = "USDT-FUTURES",
    private val channel: String = "books",
) {
    private companion object {
        const val TAG = "BitgetDepthSocket"
        const val WS_URL = "wss://ws.bitget.com/v2/ws/public"
        const val PING_INTERVAL_MS = 25_000L
        const val BASE_RECONNECT_DELAY_MS = 2_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _rawUpdates = MutableSharedFlow<DepthUpdate>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    val rawUpdates: SharedFlow<DepthUpdate> = _rawUpdates.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var intentionallyStopped = false

    fun connect() {
        intentionallyStopped = false
        reconnectAttempt = 0
        openSocket()
    }

    fun disconnect() {
        intentionallyStopped = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        socket?.close(1000, "client stopped")
        socket = null
        _state.value = SocketState.STOPPED
    }

    fun resubscribe() {
        val ws = socket
        if (ws != null && _state.value == SocketState.CONNECTED) {
            unsubscribe(ws)
            subscribe(ws)
        }
    }

    private fun openSocket() {
        _state.value = if (reconnectAttempt == 0) SocketState.CONNECTING else SocketState.RECONNECTING
        val request = Request.Builder().url(WS_URL).build()
        socket = httpClient.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            reconnectAttempt = 0
            _lastError.value = null
            _state.value = SocketState.CONNECTED
            subscribe(ws)
            startHeartbeat(ws)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (text == "pong") return
            handleMessage(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(code, reason)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            heartbeatJob?.cancel()
            if (!intentionallyStopped) scheduleReconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            _lastError.value = NetworkErrorClassifier.friendlyMessage(t)
            heartbeatJob?.cancel()
            _state.value = SocketState.FAILED
            if (!intentionallyStopped) scheduleReconnect()
        }
    }

    private fun subscribe(ws: WebSocket) {
        ws.send(subscriptionPayload("subscribe"))
    }

    private fun unsubscribe(ws: WebSocket) {
        ws.send(subscriptionPayload("unsubscribe"))
    }

    private fun subscriptionPayload(op: String): String =
        JSONObject().apply {
            put("op", op)
            put(
                "args",
                JSONArray().put(
                    JSONObject().apply {
                        put("instType", instType)
                        put("channel", channel)
                        put("instId", instId)
                    }
                )
            )
        }.toString()

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                ws.send("ping")
            }
        }
    }

    private fun scheduleReconnect() {
        socket = null
        reconnectJob?.cancel()
        val delayMs = min(
            BASE_RECONNECT_DELAY_MS * (1 shl min(reconnectAttempt, 4)),
            MAX_RECONNECT_DELAY_MS,
        )
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!intentionallyStopped) openSocket()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("event")) {
                if (json.optString("event") == "error") {
                    Log.w(TAG, "Subscribe error: $text")
                }
                return
            }
            if (!json.has("data") || !json.has("action")) return

            val action = json.getString("action")
            val rows = json.getJSONArray("data")

            for (i in 0 until rows.length()) {
                val update = DepthUpdate.fromJson(rows.getJSONObject(i), action)
                _rawUpdates.tryEmit(update)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: $text", e)
        }
    }
}
