package org.example.syncora.bitget

import android.content.Context

/**
 * The one value [StopLossGuard] actually needs out of [RiskSettingsStore],
 * pulled out as its own interface so the guard can be tested (Prompt 8b's
 * exchange-stop-independence test) against a plain in-memory fake instead
 * of a real [Context]-backed `SharedPreferences` store - the same reason
 * [StopLossOrderClient] exists alongside [BitgetTradingRestClient].
 */
interface StopLossPercentSource {
    /** See [RiskSettingsStore.stopLossPercent]. */
    val stopLossPercent: Double
}

/**
 * Persists the risk parameters for [StopLossGuard]. Nothing here is a
 * secret (unlike [BitgetLiveCredentialsStore]), so plain
 * [android.content.SharedPreferences] is fine.
 */
class RiskSettingsStore(context: Context) : StopLossPercentSource {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * How far below (long) or above (short) the entry price the resting
     * exchange-side stop-loss is placed, as a fraction of entry price -
     * e.g. 0.03 = 3%. This is deliberately independent of the RL policy's
     * own risk guardrails (design doc §5): it's the exchange-native floor
     * that still protects the position even if the app/service is dead and
     * the policy can't intervene at all.
     */
    override var stopLossPercent: Double
        get() = prefs.getFloat(KEY_STOP_LOSS_PERCENT, DEFAULT_STOP_LOSS_PERCENT).toDouble()
        set(value) = prefs.edit().putFloat(KEY_STOP_LOSS_PERCENT, value.toFloat()).apply()

    private companion object {
        const val PREFS_NAME = "risk_settings"
        const val KEY_STOP_LOSS_PERCENT = "stop_loss_percent"
        const val DEFAULT_STOP_LOSS_PERCENT = 0.03f // 3%
    }
}
