package org.example.syncora.agent

import android.util.Log
import org.example.syncora.ml.LoadedModelVersion
import org.example.syncora.ml.PolicyInferenceEngine
import org.example.syncora.ml.PolicyModelStore

/**
 * Outcome of a [ModelRollbackController.rollbackToPreviousLive] call - deliberately
 * distinct cases rather than a plain `Boolean`, since a caller-facing UI (or a support/ops
 * flow triggered outside the app) needs to say *why* a rollback did or didn't happen, not
 * just whether it did.
 */
sealed class RollbackOutcome {
    /** The previous live model is now serving inference; [restoredVersion] is what [PolicyInferenceEngine.currentVersion] reports post-rollback. */
    data class RolledBack(val restoredVersion: LoadedModelVersion?) : RollbackOutcome()

    /** There's no [PolicyModelStore.hasPreviousLiveModel] to revert to - e.g. nothing has ever been promoted, or a rollback already consumed the only prior model. */
    object NothingToRollBackTo : RollbackOutcome()

    /** The file swap succeeded but the restored file failed to load/validate; the store's own rollback already re-swaps automatically in that case (see [PolicyModelStore.rollbackToPreviousLive]/[PolicyInferenceEngine.reload]), so this reports the underlying inference engine stayed on whatever it had loaded before the attempt. */
    data class ReloadFailed(val reason: String) : RollbackOutcome()
}

/**
 * The manual counterpart to the *automatic* rollback [org.example.syncora.work.PolicyTrainingWorker]
 * already performs when a freshly promoted candidate fails to load. That automatic path only
 * catches a *load-time* failure - it can't know a model is bad because it's underperforming
 * live (drawing down, taking degenerate positions, etc.), since that's only observable after
 * the model has been trading for a while. This class is the deliberate, human-triggered
 * escape hatch for that case: "revert to a known-good prior model if a promoted model
 * underperforms live."
 *
 * Deliberately thin - it composes two things that already exist and already do the right
 * thing individually ([PolicyModelStore.rollbackToPreviousLive] for the file swap,
 * [PolicyInferenceEngine.reload] to actually start serving the restored file) rather than
 * reimplementing either. "Known-good prior model" here means [PolicyModelStore]'s single
 * most-recent previous-live slot - the same one [org.example.syncora.work.PolicyTrainingWorker]
 * already uses for its own automatic rollback - not an arbitrary point further back in
 * history, since the store only ever retains one generation of rollback target (see that
 * class's kdoc).
 *
 * Safe to call from a UI action, a settings screen, or any other manually-triggered path
 * (e.g. a debug/support entry point) - it never runs on a schedule and never fires on its
 * own. Every call, successful or not, is logged at a level that shows up in a bug report,
 * since a manual rollback of the live trading policy is exactly the kind of event an
 * incident review would need a timestamped record of.
 */
class ModelRollbackController(
    private val policyModelStore: PolicyModelStore,
    private val policyInferenceEngine: PolicyInferenceEngine,
    private val trainingRunStore: TrainingRunStore,
) {

    /** `true` if [rollbackToPreviousLive] has something to actually roll back to right now - callers can use this to enable/disable a "Rollback" affordance without triggering a real rollback attempt. */
    fun canRollBack(): Boolean = policyModelStore.hasPreviousLiveModel()

    /**
     * Reverts the live model to whatever was live immediately before the most recent
     * promotion, then reloads [PolicyInferenceEngine] so the change takes effect on the very
     * next decision boundary. Does not touch [TrainingRunStore.lastPromotionAtMs]: the
     * scheduled batch job always fine-tunes from whatever file is currently live (see
     * [org.example.syncora.work.PolicyTrainingWorker]), so once this call swaps the file, the
     * next scheduled run picks up the restored model as its base automatically - no separate
     * bookkeeping needed to "undo" the promotion watermark, and no already-pruned experience
     * needs to be (or can be) recovered.
     */
    fun rollbackToPreviousLive(): RollbackOutcome {
        if (!policyModelStore.hasPreviousLiveModel()) {
            Log.i(TAG, "Manual rollback requested but there's no previous live model to revert to")
            return RollbackOutcome.NothingToRollBackTo
        }

        val swapped = policyModelStore.rollbackToPreviousLive()
        if (!swapped) {
            // Only reachable if hasPreviousLiveModel() and the store's own rollback
            // disagree due to a concurrent file-system change between the two calls;
            // treat it the same as "nothing to roll back to" rather than crashing.
            Log.w(TAG, "Manual rollback: previous-live model disappeared between check and swap")
            return RollbackOutcome.NothingToRollBackTo
        }

        val reloaded = policyInferenceEngine.reload()
        if (!reloaded) {
            val reason = "Restored model file failed to load/validate after rollback"
            Log.e(TAG, "Manual rollback: $reason; inference engine kept serving whatever it had loaded before this call")
            return RollbackOutcome.ReloadFailed(reason)
        }

        trainingRunStore.lastManualRollbackAtMs = System.currentTimeMillis()
        val restored = policyInferenceEngine.currentVersion
        Log.i(TAG, "Manual rollback to previous live model succeeded: $restored")
        return RollbackOutcome.RolledBack(restored)
    }

    private companion object {
        const val TAG = "ModelRollbackController"
    }
}
