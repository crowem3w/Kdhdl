package org.example.syncora.bitget

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey














class BitgetLiveCredentialsStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): BitgetCredentials? {
        val apiKey = prefs.getString(KEY_API_KEY, null) ?: return null
        val secretKey = prefs.getString(KEY_SECRET_KEY, null) ?: return null
        val passphrase = prefs.getString(KEY_PASSPHRASE, null).orEmpty()
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

    companion object {
        
        
        const val PREFS_NAME = "bitget_live_credentials_encrypted"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SECRET_KEY = "secret_key"
        private const val KEY_PASSPHRASE = "passphrase"
    }
}