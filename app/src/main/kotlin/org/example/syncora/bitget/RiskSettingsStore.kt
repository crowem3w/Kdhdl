package org.example.syncora.bitget

import android.content.Context

/**
 * Persists the risk parameters for [StopLossGuard]. Nothing here is a
 * secret (unlike [BitgetLiveCredentialsStore]), so plain
 * [android.content.SharedPreferences] is fine.
 */
class RiskSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * How far below (long) or above (short) the entry price the resting
     * exchange-side stop-loss is placed, as a fraction of entry price -
     * e.g. 0.03 = 3%. This is deliberately independent of the RL policy's
     * own risk guardrails (design doc §5): it's the exchange-native floor
     * that still protects the position even if the app/service is dead and
     * the policy can't intervene at all.
     */
    var stopLossPercent: Double
        get() = prefs.getFloat(KEY_STOP_LOSS_PERCENT, DEFAULT_STOP_LOSS_PERCENT).toDouble()
        set(value) = prefs.edit().putFloat(KEY_STOP_LOSS_PERCENT, value.toFloat()).apply()

    /**
     * Master kill switch for [org.example.syncora.agent.DecisionLoopScheduler].
     * Defaults to `false`: the policy is loaded, run, and logged from the
     * moment market data starts, but a decision boundary never actually
     * places a live order until a user explicitly opts in from a settings
     * screen. This mirrors [stopLossPercent] in being a risk parameter
     * independent of the RL policy's own output - the policy can't turn
     * live dispatch on for itself.
     */
    var autoTradingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRADING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TRADING_ENABLED, value).apply()

    private companion object {
        const val PREFS_NAME = "risk_settings"
        const val KEY_STOP_LOSS_PERCENT = "stop_loss_percent"
        const val DEFAULT_STOP_LOSS_PERCENT = 0.03f // 3%
        const val KEY_AUTO_TRADING_ENABLED = "auto_trading_enabled"
    }
}
