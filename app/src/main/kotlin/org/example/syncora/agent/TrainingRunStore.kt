package org.example.syncora.agent

import android.content.Context

/**
 * Persists the one piece of cross-run state the §3.3 batch job needs: the
 * timestamp of the last successful promotion. That timestamp is both the
 * next run's [RolloutWindowBuilder.build] `sinceMs` argument (design doc
 * §3.3 step 1: "pull all resolved rows logged since the last successful
 * promotion") and [ExperienceLogStore.deleteResolvedBefore]'s prune
 * boundary after a promotion actually consumes a range.
 *
 * Same plain-[android.content.SharedPreferences] pattern as
 * [org.example.syncora.bitget.RiskSettingsStore] - nothing stored here is
 * a secret.
 */
class TrainingRunStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch millis of the last successful promotion, or `0L` if none has ever happened - "pull every resolved row ever logged" on the very first run. */
    var lastPromotionAtMs: Long
        get() = prefs.getLong(KEY_LAST_PROMOTION_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PROMOTION_AT_MS, value).apply()

    private companion object {
        const val PREFS_NAME = "training_run_state"
        const val KEY_LAST_PROMOTION_AT_MS = "last_promotion_at_ms"
    }
}
