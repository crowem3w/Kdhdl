package org.example.test.bitget

import android.content.Context

/**
 * Persists the user's Bitget Demo API Key on-device so it survives app
 * restarts. This uses plain [android.content.SharedPreferences] scoped to
 * this app's private storage (MODE_PRIVATE, not world-readable).
 *
 * These are Demo API keys - they cannot move real funds - so plain prefs are
 * an acceptable tradeoff for a paper-trading feature. If this client is ever
 * extended to hold *live* trading keys, swap this for
 * androidx.security.crypto.EncryptedSharedPreferences instead.
 */
class BitgetCredentialsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): BitgetCredentials? {
        val apiKey = prefs.getString(KEY_API_KEY, null) ?: return null
        val secretKey = prefs.getString(KEY_SECRET_KEY, null) ?: return null
        val passphrase = prefs.getString(KEY_PASSPHRASE, null) ?: return null
        val credentials = BitgetCredentials(apiKey, secretKey, passphrase)
        return if (credentials.isComplete) credentials else null
    }

    fun save(credentials: BitgetCredentials) {
        prefs.edit()
            .putString(KEY_API_KEY, credentials.apiKey)
            .putString(KEY_SECRET_KEY, credentials.secretKey)
            .putString(KEY_PASSPHRASE, credentials.passphrase)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "bitget_demo_credentials"
        const val KEY_API_KEY = "api_key"
        const val KEY_SECRET_KEY = "secret_key"
        const val KEY_PASSPHRASE = "passphrase"
    }
}
