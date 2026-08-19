package org.example.test.bitget

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Implements Bitget's REST request signing scheme:
 *
 *   sign = Base64( HMAC_SHA256( secretKey, timestamp + method + requestPath + body ) )
 *
 * - `timestamp` is milliseconds-since-epoch, as a string.
 * - `method` is uppercase ("GET" / "POST").
 * - `requestPath` includes the query string for GET requests (e.g.
 *   "/api/v2/mix/position/all-position?symbol=BTCUSDT&productType=USDT-FUTURES"),
 *   and is just the path for POST requests.
 * - `body` is the exact JSON string sent (empty string for GET / bodyless requests).
 *
 * See: https://www.bitget.com/api-doc/common/signature
 */
object BitgetRequestSigner {

    private const val ALGORITHM = "HmacSHA256"

    fun sign(secretKey: String, timestamp: String, method: String, requestPath: String, body: String): String {
        val message = timestamp + method.uppercase() + requestPath + body
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), ALGORITHM))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
