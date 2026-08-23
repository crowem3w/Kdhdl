package org.example.syncora.risk

import android.content.Context

/**
 * Persists the hard limits enforced by [PreTradeSafetyGate] and
 * [VolatilityCircuitBreaker] - kept as a separate store from
 * [org.example.syncora.bitget.RiskSettingsStore] (which owns the
 * dead-man's-switch stop-loss percent and the auto-trading kill switch)
 * because these values are a *ceiling* the policy can never move past,
 * regardless of what [org.example.syncora.bitget.RiskSettingsStore.autoTradingEnabled]
 * or the policy network's own output say. Nothing in this store is a
 * secret, so plain [android.content.SharedPreferences] is fine, same as
 * [org.example.syncora.bitget.RiskSettingsStore].
 */
class RiskLimitsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Hard notional cap, expressed as a multiple of live account equity
     * (e.g. `3.0` = position notional can never exceed 3x equity), enforced
     * by [PreTradeSafetyGate] against a *freshly fetched* Bitget balance on
     * every entry order - independent of, and always at least as strict as,
     * [org.example.syncora.ml.PolicyInferenceEngine]'s own `actionLeverageCap`
     * and [org.example.syncora.agent.DecisionLoopScheduler]'s
     * `positionLeverage`. Those two exist to shape the policy's *intended*
     * behavior; this one exists so a bug, a bad model promotion, or a
     * compromised/misbehaving policy output can't ever place a request past
     * this number, because it's not derived from the policy's output at all.
     */
    var maxLeverage: Double
        get() = prefs.getFloat(KEY_MAX_LEVERAGE, DEFAULT_MAX_LEVERAGE).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MAX_LEVERAGE, value.toFloat()).apply()

    /**
     * DVOL level (Deribit's BTC volatility index - see [VolatilityIndexClient])
     * at/above which [VolatilityCircuitBreaker] trips: halts new entries and
     * signals that open exposure should be flattened. See
     * [VolatilityCircuitBreaker]'s kdoc for why this default is a
     * conservative starting point pending a proper calibration pass against
     * DVOL's actual historical range, not a value to trust blindly.
     */
    var volatilityHaltThreshold: Double
        get() = prefs.getFloat(KEY_VOL_THRESHOLD, DEFAULT_VOL_THRESHOLD).toDouble()
        set(value) = prefs.edit().putFloat(KEY_VOL_THRESHOLD, value.toFloat()).apply()

    private companion object {
        const val PREFS_NAME = "risk_limits"
        const val KEY_MAX_LEVERAGE = "max_leverage"
        const val DEFAULT_MAX_LEVERAGE = 3.0f
        const val KEY_VOL_THRESHOLD = "volatility_halt_threshold"
        const val DEFAULT_VOL_THRESHOLD = 90.0f // DVOL units; see kdoc above - requires calibration.
    }
}
