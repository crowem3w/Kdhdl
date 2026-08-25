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

    /** Epoch millis of the last successful *manual* [org.example.syncora.agent.ModelRollbackController] call, or `0L` if a manual rollback has never happened. Purely an audit trail - nothing reads this to make a decision - so an incident review can tell when a human last intervened to revert the live model. */
    var lastManualRollbackAtMs: Long
        get() = prefs.getLong(KEY_LAST_MANUAL_ROLLBACK_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_MANUAL_ROLLBACK_AT_MS, value).apply()

    /**
     * Epoch millis of the most recent [CpcvPboValidationGate.decide] call, pass **or**
     * reject - deliberately distinct from [lastPromotionAtMs], which only ever advances on a
     * *pass*. Without this, a UI showing "last promotion" would look silently stale through an
     * arbitrarily long run of rejected candidates, even though the batch job is running and
     * checking on schedule; this is what lets the Agent tab show "gate last ran 4h ago,
     * rejected" instead of just going quiet.
     */
    var lastGateDecisionAtMs: Long
        get() = prefs.getLong(KEY_LAST_GATE_DECISION_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_GATE_DECISION_AT_MS, value).apply()

    /** `true` if the decision at [lastGateDecisionAtMs] was a [GateDecision.Pass]. */
    var lastGateDecisionPassed: Boolean
        get() = prefs.getBoolean(KEY_LAST_GATE_DECISION_PASSED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_GATE_DECISION_PASSED, value).apply()

    /**
     * Human-readable one-liner for [lastGateDecisionAtMs]'s outcome - either
     * [GateDecision.Reject.reason] verbatim, or a short summary of the winning hyperparameters
     * and PBO probability on a pass - so a UI can display *something* without reconstructing a
     * sentence from raw numbers itself.
     */
    var lastGateDecisionSummary: String
        get() = prefs.getString(KEY_LAST_GATE_DECISION_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_GATE_DECISION_SUMMARY, value).apply()

    /**
     * Structured counterpart to [lastGateDecisionSummary], populated only on a
     * [GateDecision.Pass][org.example.syncora.agent.GateDecision.Pass] - the swept
     * [org.example.syncora.ml.PpoHyperparameters.clipEpsilon]/[org.example.syncora.ml.PpoHyperparameters.learningRate]
     * of the winning config, so the Agent tab can render a proper hyperparameter readout
     * instead of parsing them back out of the free-text summary string. `NaN`/`0` before any
     * pass has ever happened - callers should gate on [lastGateDecisionPassed] rather than on
     * these being non-default.
     */
    var lastWinningClipEpsilon: Float
        get() = prefs.getFloat(KEY_LAST_WINNING_CLIP_EPSILON, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_LAST_WINNING_CLIP_EPSILON, value).apply()

    var lastWinningLearningRate: Float
        get() = prefs.getFloat(KEY_LAST_WINNING_LEARNING_RATE, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_LAST_WINNING_LEARNING_RATE, value).apply()

    /** [org.example.syncora.agent.GateDecision.Pass.pboProbability] of the winning config on its promotion run - lower is better, gate threshold is 0.10 (design doc §4). */
    var lastPboProbability: Float
        get() = prefs.getFloat(KEY_LAST_PBO_PROBABILITY, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_LAST_PBO_PROBABILITY, value).apply()

    /** [org.example.syncora.agent.GateDecision.Pass.splitsEvaluated] the winning config was ranked across. */
    var lastSplitsEvaluated: Int
        get() = prefs.getInt(KEY_LAST_SPLITS_EVALUATED, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SPLITS_EVALUATED, value).apply()

    private companion object {
        const val PREFS_NAME = "training_run_state"
        const val KEY_LAST_PROMOTION_AT_MS = "last_promotion_at_ms"
        const val KEY_LAST_MANUAL_ROLLBACK_AT_MS = "last_manual_rollback_at_ms"
        const val KEY_LAST_GATE_DECISION_AT_MS = "last_gate_decision_at_ms"
        const val KEY_LAST_GATE_DECISION_PASSED = "last_gate_decision_passed"
        const val KEY_LAST_GATE_DECISION_SUMMARY = "last_gate_decision_summary"
        const val KEY_LAST_WINNING_CLIP_EPSILON = "last_winning_clip_epsilon"
        const val KEY_LAST_WINNING_LEARNING_RATE = "last_winning_learning_rate"
        const val KEY_LAST_PBO_PROBABILITY = "last_pbo_probability"
        const val KEY_LAST_SPLITS_EVALUATED = "last_splits_evaluated"
    }
}
