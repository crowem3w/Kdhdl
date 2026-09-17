package org.example.test

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate










object ThemeManager {
    private const val PREFS = "theme_prefs"
    private const val KEY_DARK = "dark_mode"

    
    private const val DEFAULT_DARK = true

    fun isDarkMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK, DEFAULT_DARK)

    fun setDarkMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK, enabled).apply()
        applyNightMode(enabled)
    }

    

    fun applySavedMode(context: Context) {
        applyNightMode(isDarkMode(context))
    }

    private fun applyNightMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}