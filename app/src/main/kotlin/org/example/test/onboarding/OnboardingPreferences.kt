package org.example.test.onboarding

import android.content.Context

/**
 * Tracks whether the user has ever made it past the onboarding screen. Backed by
 * [android.content.SharedPreferences], which persists across app restarts but is cleared
 * on uninstall — so onboarding naturally reappears on a fresh install, but never again on
 * subsequent app opens.
 */
class OnboardingPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_HAS_COMPLETED_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_COMPLETED_ONBOARDING, value).apply()

    private companion object {
        const val PREFS_NAME = "onboarding_prefs"
        const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"
    }
}
