package org.example.test.bitget

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
