package org.example.syncora.bitget

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec















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