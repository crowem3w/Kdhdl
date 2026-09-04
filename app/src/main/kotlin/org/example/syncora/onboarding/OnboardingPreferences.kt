package org.example.syncora.onboarding

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

    /**
     * Whether the battery-optimization-exemption prompt (see
     * [org.example.syncora.onboarding.OnboardingActivity]) has already been
     * shown once. Onboarding itself is one-time already, but this is kept as
     * its own flag rather than reusing [hasCompletedOnboarding] in case a
     * future flow needs to re-prompt independently of full onboarding.
     */
    var hasRequestedBatteryOptimizationExemption: Boolean
        get() = prefs.getBoolean(KEY_HAS_REQUESTED_BATTERY_EXEMPTION, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_REQUESTED_BATTERY_EXEMPTION, value).apply()

    private companion object {
        const val PREFS_NAME = "onboarding_prefs"
        const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"
        const val KEY_HAS_REQUESTED_BATTERY_EXEMPTION = "has_requested_battery_optimization_exemption"
    }
}
