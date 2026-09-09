package org.example.syncora.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Wraps the `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` flow (design doc
 * §2.2): OEM background killers (Samsung, Xiaomi, etc.) can and do stop even
 * a correctly-declared foreground service unless the user explicitly
 * whitelists the app, so this has to be a real onboarding step rather than
 * something assumed to just work.
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true // no such restriction pre-Marshmallow
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Builds the system intent that prompts the user directly (no custom UI
     * Android will accept for this specific permission - it must be the
     * system's own dialog). Returns null pre-Marshmallow, where there's
     * nothing to request.
     */
    fun buildExemptionRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
