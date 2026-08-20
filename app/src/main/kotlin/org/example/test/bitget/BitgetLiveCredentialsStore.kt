package org.example.test.bitget

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the user's **live** Bitget API Key - the one Bitget's real
 * matching engine will accept and that can move real funds.
 *
 * Unlike [BitgetCredentialsStore] (which is fine with plain prefs because
 * Demo API Keys are worthless outside the paper-trading sandbox), this store
 * uses [EncryptedSharedPreferences], backed by an AES256-GCM key that lives
 * in the Android Keystore and never leaves the device. The prefs file this
 * writes is also excluded from Auto Backup / device-to-device transfer (see
 * res/xml/data_extraction_rules.xml and res/xml/backup_rules.xml), so a
 * backup can't leak it, and a restore onto another device can't leave behind
 * an undecryptable blob.
 */
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
        // Referenced by name (not just used internally) from the backup/data-extraction
        // XML rules, since those match on the SharedPreferences file name.
        const val PREFS_NAME = "bitget_live_credentials_encrypted"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SECRET_KEY = "secret_key"
        private const val KEY_PASSPHRASE = "passphrase"
    }
}
