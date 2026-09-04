package org.example.syncora.bitget

import android.content.Context






class RiskSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    







    var stopLossPercent: Double
        get() = prefs.getFloat(KEY_STOP_LOSS_PERCENT, DEFAULT_STOP_LOSS_PERCENT).toDouble()
        set(value) = prefs.edit().putFloat(KEY_STOP_LOSS_PERCENT, value.toFloat()).apply()

    private companion object {
        const val PREFS_NAME = "risk_settings"
        const val KEY_STOP_LOSS_PERCENT = "stop_loss_percent"
        const val DEFAULT_STOP_LOSS_PERCENT = 0.03f 
    }
}