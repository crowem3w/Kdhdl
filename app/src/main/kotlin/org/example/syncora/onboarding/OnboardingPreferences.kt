package org.example.syncora.onboarding

import android.content.Context

class OnboardingPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_HAS_COMPLETED_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_COMPLETED_ONBOARDING, value).apply()

    var hasRequestedBatteryOptimizationExemption: Boolean
        get() = prefs.getBoolean(KEY_HAS_REQUESTED_BATTERY_EXEMPTION, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_REQUESTED_BATTERY_EXEMPTION, value).apply()

    private companion object {
        const val PREFS_NAME = "onboarding_prefs"
        const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"
        const val KEY_HAS_REQUESTED_BATTERY_EXEMPTION = "has_requested_battery_optimization_exemption"
    }
}
