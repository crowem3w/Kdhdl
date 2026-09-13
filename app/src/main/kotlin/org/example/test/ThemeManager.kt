package org.example.test

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Persists the user's Light/Dark mode preference (Settings tab) and keeps
 * AppCompatDelegate's night mode in sync with it, so DayNight-aware system
 * widgets (buttons, switches, status bar) follow along automatically.
 *
 * Screens built with hand-rolled colors don't pick up DayNight resources on
 * their own, so they instead read [isDarkMode] via [AppTheme.of] to choose
 * their palette explicitly.
 */
object ThemeManager {
    private const val PREFS = "theme_prefs"
    private const val KEY_DARK = "dark_mode"

    /** App shipped as dark-only before this feature existed, so that's the default. */
    private const val DEFAULT_DARK = true

    fun isDarkMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK, DEFAULT_DARK)

    fun setDarkMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK, enabled).apply()
        applyNightMode(enabled)
    }

    /** Call once at activity startup, before setContentView, so the DayNight
     * theme resources resolve to the saved preference immediately. */
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
