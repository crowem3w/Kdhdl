package org.example.syncora.bitget

import okhttp3.Response
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

object NetworkErrorClassifier {

    fun friendlyMessage(e: Throwable): String =
        if (isTrustAnchorFailure(e)) {
            "Can't verify a secure connection. Check that your device's date & " +
                "time are correct, and that Settings → Security → Trusted " +
                "credentials hasn't been altered (e.g. by a VPN/ad-block app)."
        } else {
            e.message ?: e::class.java.simpleName
        }

    /**
     * Same idea as [friendlyMessage] but for WebSocket handshake failures, which hand
     * back the raw HTTP [response] (if the connection reached a server at all) instead
     * of just a Throwable. A 403/451 here — as opposed to a plain timeout/DNS failure —
     * points at an IP/region-based block sitting in front of Bitget rather than a purely
     * local network problem, which is the detail worth surfacing when diagnosing "works
     * on one network, not on another".
     */
    fun diagnosticMessage(e: Throwable, response: Response?): String {
        val base = friendlyMessage(e)
        val code = response?.code ?: return base
        val hint = when (code) {
            403, 451 -> " (HTTP $code — possible IP/region restriction on this network)"
            429 -> " (HTTP $code — rate limited)"
            else -> " (HTTP $code)"
        }
        return "$base$hint"
    }

    private fun isTrustAnchorFailure(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 6) {
            if (cause is CertPathValidatorException || cause is SSLHandshakeException) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
