package org.example.test

import android.content.Context




object HapticSettings {
    private const val PREFS = "haptic_prefs"
    private const val KEY_ENABLED = "haptics_enabled"

    private const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
