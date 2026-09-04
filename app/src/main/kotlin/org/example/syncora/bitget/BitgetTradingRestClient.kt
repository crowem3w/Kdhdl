package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


















enum class BitgetEnvironment { LIVE, DEMO }

class BitgetNotAuthenticatedException(environment: BitgetEnvironment) : IOException(
    if (environment == BitgetEnvironment.DEMO) "No Bitget Demo API Key configured" else "No Bitget API Key configured"
)

class BitgetApiException(val code: String, message: String) : IOException(message)
























class BitgetTradingRestClient(
    private val environment: () -> BitgetEnvironment,
    private val credentialsProvider: () -> BitgetCredentials?,
    private val productType: String = "USDT-FUTURES",
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        const val TAG = "BitgetTradingRest"
        const val BASE_URL = "https://api.bitget.com"
        const val JSON_MEDIA_TYPE = "application/json"
    }

    suspend fun fetchPosition(symbol: String): PaperPosition? {
        val path = "/api/v2/mix/position/single-position"
        val query = "symbol=$symbol&productType=$productType&marginCoin=USDT"
        val json = get(path, query)
        val rows = json.optJSONArray("data") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val position = parsePosition(rows.getJSONObject(i))
            if (position != null) return position
        }
        return null
    }

    suspend fun fetchAllPositions(): List<PaperPosition> {
        val path = "/api/v2/mix/position/all-position"
        val query = "productType=$productType&marginCoin=USDT"
        val json = get(path, query)
        val rows = json.optJSONArray("data") ?: JSONArray()
        return buildList(rows.length()) {
            for (i in 0 until rows.length()) {
                parsePosition(rows.getJSONObject(i))?.let { add(it) }
            }
        }
    }

    







    suspend fun fetchUserId(): String? {
        val json = get("/api/v2/spot/account/info", "")
        val data = json.optJSONObject("data") ?: return null
        return data.optString("userId").takeIf { it.isNotBlank() }
    }

    suspend fun fetchAccountBalance(): PaperAccountBalance? {
        val path = "/api/v2/mix/account/accounts"
        val query = "productType=$productType"
        val json = get(path, query)
        val rows = json.optJSONArray("data") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("marginCoin").equals("USDT", ignoreCase = true)) {
                return PaperAccountBalance(
                    marginCoin = row.optString("marginCoin", "USDT"),
                    available = row.optString("available", "0").toDoubleOrNull() ?: 0.0,
                    equity = row.optString("usdtEquity", "0").toDoubleOrNull() ?: 0.0,
                    unrealizedPnl = row.optString("unrealizedPL", "0").toDoubleOrNull() ?: 0.0,
                )
            }
        }
        return null
    }

    suspend fun setLeverage(symbol: String, leverage: Int, marginMode: String = "crossed") {
        val path = "/api/v2/mix/account/set-leverage"
        val body = JSONObject().apply {
            put("symbol", symbol)
            put("productType", productType)
            put("marginCoin", "USDT")
            put("leverage", leverage.toString())
        }
        
        runCatching { post(path, body) }
    }

    
    suspend fun openPosition(ticket: OrderTicket): PlacedOrder {
        val clientOrderId = "${orderIdPrefix()}-${System.currentTimeMillis()}"
        val body = JSONObject().apply {
            put("symbol", ticket.symbol)
            put("productType", productType)
            put("marginMode", ticket.marginMode)
            put("marginCoin", "USDT")
            put("size", ticket.sizeInBaseCoin)
            put("side", ticket.side.openOrderSide)
            put("tradeSide", "open") 
            put("orderType", "market")
            put("clientOid", clientOrderId)
        }
        val json = post("/api/v2/mix/order/place-order", body)
        val data = json.getJSONObject("data")
        return PlacedOrder(
            orderId = data.optString("orderId"),
            clientOrderId = data.optString("clientOid", clientOrderId),
        )
    }

    
    suspend fun closePosition(symbol: String, side: PositionSide, sizeInBaseCoin: String, marginMode: String = "crossed"): PlacedOrder {
        val clientOrderId = "${orderIdPrefix()}-close-${System.currentTimeMillis()}"
        val body = JSONObject().apply {
            put("symbol", symbol)
            put("productType", productType)
            put("marginMode", marginMode)
            put("marginCoin", "USDT")
            put("size", sizeInBaseCoin)
            put("side", side.closeOrderSide)
            put("tradeSide", "close")
            put("orderType", "market")
            put("reduceOnly", "YES")
            put("clientOid", clientOrderId)
        }
        val json = post("/api/v2/mix/order/place-order", body)
        val data = json.getJSONObject("data")
        return PlacedOrder(
            orderId = data.optString("orderId"),
            clientOrderId = data.optString("clientOid", clientOrderId),
        )
    }

    
















    suspend fun placeStopLoss(
        symbol: String,
        holdSide: PositionSide,
        triggerPrice: String,
        sizeInBaseCoin: String,
    ): String {
        val body = JSONObject().apply {
            put("marginCoin", "USDT")
            put("productType", productType)
            put("symbol", symbol)
            put("planType", "loss_plan")
            put("triggerPrice", triggerPrice)
            put("triggerType", "mark_price")
            put("executePrice", "0") 
            put("holdSide", holdSide.bitgetHoldSide)
            put("size", sizeInBaseCoin)
        }
        val json = post("/api/v2/mix/order/place-pos-tpsl", body)
        val data = json.getJSONObject("data")
        return data.optString("orderId")
    }

    






    suspend fun fetchPendingStopLossOrders(symbol: String): List<StopLossOrder> {
        val path = "/api/v2/mix/order/orders-plan-pending"
        val query = "symbol=$symbol&productType=$productType&planType=loss_plan"
        val json = get(path, query)
        val data = json.optJSONObject("data")
        val rows = data?.optJSONArray("entrustedList") ?: JSONArray()
        return buildList(rows.length()) {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val orderId = row.optString("orderId")
                val triggerPrice = row.optString("triggerPrice", "0").toDoubleOrNull()
                val size = row.optString("size", "0").toDoubleOrNull()
                if (orderId.isBlank() || triggerPrice == null || size == null) continue
                add(
                    StopLossOrder(
                        orderId = orderId,
                        symbol = row.optString("symbol", symbol),
                        holdSide = PositionSide.fromHoldSide(row.optString("holdSide")),
                        triggerPrice = triggerPrice,
                        sizeInBaseCoin = size,
                    ),
                )
            }
        }
    }

    
    suspend fun cancelStopLoss(symbol: String, orderId: String) {
        val body = JSONObject().apply {
            put("marginCoin", "USDT")
            put("productType", productType)
            put("symbol", symbol)
            put("orderId", orderId)
            put("planType", "loss_plan")
        }
        
        
        
        runCatching { post("/api/v2/mix/order/cancel-plan-order", body) }
    }

    private fun orderIdPrefix(): String = if (environment() == BitgetEnvironment.DEMO) "paper" else "live"

    private fun parsePosition(row: JSONObject): PaperPosition? {
        val total = row.optString("total", "0").toDoubleOrNull() ?: 0.0
        if (total <= 0.0) return null 
        return PaperPosition(
            symbol = row.optString("symbol"),
            side = PositionSide.fromHoldSide(row.optString("holdSide")),
            total = total,
            available = row.optString("available", "0").toDoubleOrNull() ?: 0.0,
            entryPrice = row.optString("openPriceAvg", "0").toDoubleOrNull() ?: 0.0,
            markPrice = row.optString("markPrice", "0").toDoubleOrNull() ?: 0.0,
            leverage = row.optString("leverage", "1").toDoubleOrNull()?.toInt() ?: 1,
            marginSize = row.optString("marginSize", "0").toDoubleOrNull() ?: 0.0,
            unrealizedPnl = row.optString("unrealizedPL", "0").toDoubleOrNull() ?: 0.0,
        )
    }

    private suspend fun get(path: String, query: String): JSONObject {
        val credentials = credentialsProvider() ?: throw BitgetNotAuthenticatedException(environment())
        val timestamp = System.currentTimeMillis().toString()
        val requestPath = if (query.isBlank()) path else "$path?$query"
        val sign = BitgetRequestSigner.sign(credentials.secretKey, timestamp, "GET", requestPath, "")

        val request = Request.Builder()
            .url("$BASE_URL$requestPath")
            .get()
            .applyAuthHeaders(credentials, timestamp, sign)
            .build()

        return parseResponse(executeAsync(request))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        val credentials = credentialsProvider() ?: throw BitgetNotAuthenticatedException(environment())
        val timestamp = System.currentTimeMillis().toString()
        val bodyString = body.toString()
        val sign = BitgetRequestSigner.sign(credentials.secretKey, timestamp, "POST", path, bodyString)

        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(bodyString.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .applyAuthHeaders(credentials, timestamp, sign)
            .build()

        return parseResponse(executeAsync(request))
    }

    








    private fun Request.Builder.applyAuthHeaders(credentials: BitgetCredentials, timestamp: String, sign: String): Request.Builder {
        val builder = header("ACCESS-KEY", credentials.apiKey)
            .header("ACCESS-SIGN", sign)
            .header("ACCESS-TIMESTAMP", timestamp)
            .header("ACCESS-PASSPHRASE", credentials.passphrase)
            .header("Content-Type", JSON_MEDIA_TYPE)
            .header("locale", "en-US")
        return if (environment() == BitgetEnvironment.DEMO) {
            builder.header("paptrading", "1") 
        } else {
            builder
        }
    }

    private fun parseResponse(body: String): JSONObject {
        val json = JSONObject(body)
        val code = json.optString("code")
        if (code != "00000") {
            throw BitgetApiException(code, json.optString("msg", "Unknown Bitget error"))
        }
        return json
    }

    private suspend fun executeAsync(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Trading request failed: ${e.message}")
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string()
                        if (text == null) {
                            continuation.resumeWithException(IOException("Empty response body (HTTP ${it.code})"))
                        } else {
                            
                            
                            continuation.resume(text)
                        }
                    }
                }
            })
        }
}