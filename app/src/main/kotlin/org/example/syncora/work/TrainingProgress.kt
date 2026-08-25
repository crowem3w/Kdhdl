package org.example.syncora.work

/**
 * Schema for the `WorkManager` progress [androidx.work.Data] that
 * [PolicyTrainingWorker] publishes via `setProgress` while a run is in
 * flight, and that the Agent tab (see `MainActivity.observeAgentTab` /
 * `QuickTradePanel.renderTrainingProgress`) reads back via
 * `WorkInfo.progress`.
 *
 * `WorkInfo.progress` is only ever populated while a run is actually
 * `RUNNING` - `WorkManager` clears it once the worker finishes, which is
 * why the UI side also needs [TrainingRunStore][org.example.syncora.agent.TrainingRunStore]'s
 * durable fields (`lastGateDecisionSummary` etc.) to describe the most
 * recent *completed* run. This file only covers the in-flight case.
 */
object TrainingProgress {
    /** One of [Stage]'s [Stage.wireValue]s. */
    const val KEY_STAGE = "stage"

    /** `0..100`, coarse - this is a sequence of discrete phases, not something with a precise fractional cost model. */
    const val KEY_PERCENT = "percent"

    /** Short human-readable detail for the current stage, e.g. "Training config 2/3". */
    const val KEY_DETAIL = "detail"

    /**
     * Mirrors [PolicyTrainingWorker]'s own kdoc'd run sequence 1:1, plus the
     * early-exit outcomes (skip/reject) so the UI can distinguish "still
     * working" from "done, nothing to do this time" without needing to also
     * inspect [androidx.work.WorkInfo.State] itself.
     */
    enum class Stage(val wireValue: String, val label: String) {
        CHECKING_MODEL("checking_model", "Checking for a live model"),
        BUILDING_WINDOWS("building_windows", "Pulling logged experience"),
        SKIPPED_INSUFFICIENT_DATA("skipped_insufficient_data", "Skipped — not enough new data yet"),
        SPLITTING("splitting", "Building CPCV splits"),
        SKIPPED_INSUFFICIENT_SPLITS("skipped_insufficient_splits", "Skipped — not enough data for a CPCV split"),
        TRAINING_SWEEP("training_sweep", "Training hyperparameter sweep"),
        GATING("gating", "Running CPCV/PBO validation gate"),
        REJECTED("rejected", "Candidate rejected by validation gate"),
        PROMOTING("promoting", "Training full-data candidate"),
        RELOADING("reloading", "Promoting and reloading inference"),
        PROMOTED("promoted", "Promoted new policy"),
        FAILED("failed", "Run failed");

        companion object {
            fun fromWireValue(value: String?): Stage? = entries.firstOrNull { it.wireValue == value }
        }
    }
}
