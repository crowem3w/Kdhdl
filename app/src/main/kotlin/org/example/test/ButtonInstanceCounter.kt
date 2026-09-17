package org.example.test

import android.content.Context

/**
 * Tracks how many times a Button component panel has been opened, so each opening gets its own
 * default label ("Button 1", "Button 2", ...). There's currently no per-project/per-file scoping
 * anywhere else in the app (see HapticSettings.kt for the same pattern), so this counts globally
 * for the app, persisted via SharedPreferences the same way, and survives process death/app
 * restarts.
 */
object ButtonInstanceCounter {
    private const val PREFS = "button_instance_prefs"
    private const val KEY_COUNT = "button_instance_count"

    /** Increments the stored count and returns the new value, e.g. 1 on first-ever call. */
    fun nextInstanceNumber(context: Context): Int {
        val next = prefs(context).getInt(KEY_COUNT, 0) + 1
        prefs(context).edit().putInt(KEY_COUNT, next).apply()
        return next
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
