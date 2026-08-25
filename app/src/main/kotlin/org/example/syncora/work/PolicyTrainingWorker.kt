package org.example.syncora.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.syncora.SyncoraApplication
import org.example.syncora.agent.CombinatorialPurgedCrossValidator
import org.example.syncora.agent.ConfigPerformance
import org.example.syncora.agent.ConfigSplitScore
import org.example.syncora.agent.CpcvPboValidationGate
import org.example.syncora.agent.CpcvSplit
import org.example.syncora.agent.GateDecision
import org.example.syncora.agent.RolloutWindow
import org.example.syncora.agent.RolloutWindowBuilder
import org.example.syncora.ml.PpoHyperparameters
import org.example.syncora.ml.PpoTrainer
import java.io.File
import java.util.Locale

/**
 * The design doc §3.3/§3.6/§4 batch job, wired into `WorkManager` (§2.2
 * item 2: "`WorkManager`-scheduled periodic work for the batch training
 * step ... training doesn't need to run continuously - only inference and
 * risk-guardrail checks need to be near-real-time"). Scheduled by
 * [TrainingScheduler] to fire roughly daily; all the scheduling policy
 * (constraints, interval, idempotence) lives there, not here.
 *
 * **What one run does, precisely** (mirrors [RolloutWindowBuilder]'s own
 * "what the batch job does, precisely" kdoc):
 * 1. Pull every resolved row logged since the last successful promotion
 *    and assemble it into GAE-annotated [RolloutWindow]s.
 * 2. Not enough data yet -> skip this run cleanly. Design doc §3.6 step 5:
 *    unused experience simply "rolls forward into the next scheduled
 *    attempt" rather than being wasted or forcing a run on too little data.
 * 3. Partition the windows via [CombinatorialPurgedCrossValidator] and
 *    train + evaluate a small sweep of [PpoHyperparameters] configs across
 *    every split, building design doc §4's OOS performance matrix.
 * 4. Hand that matrix to [CpcvPboValidationGate]. **This worker never
 *    calls [org.example.syncora.ml.PolicyModelStore.promoteCandidateToLive]
 *    on a freshly trained model without a gate decision in between** - the
 *    task requirement this whole class exists to satisfy ("does not deploy
 *    it directly - instead hands it to the validation gate").
 * 5. On [GateDecision.Pass]: retrain the gate's winning configuration on
 *    the *full* window set (the CPCV sweep only ever trains on purged
 *    partial splits - the model that actually gets promoted should see
 *    every available resolved transition, not just one split's worth),
 *    export it as the candidate `.tflite`, promote, reload inference, and
 *    advance the promotion watermark. On [GateDecision.Reject]: discard
 *    the candidate, leave the watermark untouched, and leave the logged
 *    experience alone so it rolls into the next attempt.
 *
 * **Compute budget.** Training `H` configs across `J` CPCV splits, each
 * for several PPO epochs, is real sustained on-device compute - exactly
 * the tradeoff `WorkManager`'s constraints (see [TrainingScheduler]) exist
 * to manage. This class doesn't second-guess when `WorkManager` chooses to
 * run it; it only bounds *how much* work one run does via
 * [HYPERPARAMETER_SWEEP]'s fixed small size and
 * [CombinatorialPurgedCrossValidator]'s own `maxSplits`.
 */
class PolicyTrainingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val app = applicationContext as SyncoraApplication
        try {
            runTrainingAttempt(app)
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled PPO training run failed: ${e.message}", e)
            reportProgress(TrainingProgress.Stage.FAILED, 100, e.message ?: e.toString())
            Result.retry()
        }
    }

    private suspend fun runTrainingAttempt(app: SyncoraApplication): Result {
        reportProgress(TrainingProgress.Stage.CHECKING_MODEL, 0, "Checking for a live model to fine-tune from")
        if (!app.policyModelStore.hasLiveModel()) {
            Log.i(TAG, "No live model yet to fine-tune from; skipping this scheduled run")
            return Result.success()
        }
        val baseModelFile = app.policyModelStore.liveModelFile
        val trainer = PpoTrainer()

        val sinceMs = app.trainingRunStore.lastPromotionAtMs
        reportProgress(TrainingProgress.Stage.BUILDING_WINDOWS, 5, "Pulling resolved experience since last promotion")
        val windowBuilder = RolloutWindowBuilder(
            experienceLogStore = app.experienceLogStore,
            criticValueFn = { state -> trainer.estimateValue(baseModelFile, state) },
        )
        val windows = windowBuilder.build(sinceMs)
        val totalTransitions = windows.sumOf { it.steps.size }
        if (totalTransitions < MIN_TRANSITIONS_FOR_TRAINING) {
            Log.i(TAG, "Only $totalTransitions resolved transition(s) since last promotion (need $MIN_TRANSITIONS_FOR_TRAINING); skipping")
            reportProgress(
                TrainingProgress.Stage.SKIPPED_INSUFFICIENT_DATA,
                100,
                "$totalTransitions/$MIN_TRANSITIONS_FOR_TRAINING resolved transitions logged",
            )
            return Result.success()
        }

        reportProgress(TrainingProgress.Stage.SPLITTING, 10, "Partitioning ${windows.size} rollout window(s)")
        val splits = CombinatorialPurgedCrossValidator().splits(windows)
        if (splits.isEmpty()) {
            Log.i(TAG, "Only ${windows.size} rollout window(s) available - not enough yet for a CPCV split; skipping")
            reportProgress(TrainingProgress.Stage.SKIPPED_INSUFFICIENT_SPLITS, 100, "${windows.size} rollout window(s) available")
            return Result.success()
        }

        val cacheDir = File(app.cacheDir, "ppo_cpcv").apply { mkdirs() }
        val performances = try {
            HYPERPARAMETER_SWEEP.mapIndexed { index, hp ->
                reportProgress(
                    TrainingProgress.Stage.TRAINING_SWEEP,
                    10 + (index * 70 / HYPERPARAMETER_SWEEP.size),
                    "Config ${index + 1}/${HYPERPARAMETER_SWEEP.size} across ${splits.size} split(s)",
                )
                evaluateAcrossSplits(trainer, hp, baseModelFile, splits, cacheDir)
            }
        } finally {
            cacheDir.deleteRecursively()
        }

        reportProgress(TrainingProgress.Stage.GATING, 85, "${HYPERPARAMETER_SWEEP.size} config(s) evaluated")
        return when (val decision = CpcvPboValidationGate().decide(performances)) {
            is GateDecision.Reject -> {
                Log.i(TAG, "Candidate rejected by CPCV/PBO gate: ${decision.reason}")
                app.trainingRunStore.lastGateDecisionAtMs = System.currentTimeMillis()
                app.trainingRunStore.lastGateDecisionPassed = false
                app.trainingRunStore.lastGateDecisionSummary = decision.reason
                reportProgress(TrainingProgress.Stage.REJECTED, 100, decision.reason)
                Result.success()
            }
            is GateDecision.Pass -> {
                promote(app, trainer, baseModelFile, windows, decision)
                Result.success()
            }
        }
    }

    /** Trains and no-gradient-evaluates one hyperparameter config across every CPCV split, producing the row of design doc §4's OOS performance matrix that belongs to this config. */
    private fun evaluateAcrossSplits(
        trainer: PpoTrainer,
        hyperparameters: PpoHyperparameters,
        baseModelFile: File,
        splits: List<CpcvSplit>,
        cacheDir: File,
    ): ConfigPerformance {
        val scores = splits.mapIndexed { index, split ->
            val tmpModel = File(cacheDir, "cpcv_${hyperparameters.hashCode()}_$index.tflite")
            try {
                trainer.train(baseModelFile, split.trainWindows, tmpModel, hyperparameters)
                val inSample = trainer.evaluate(tmpModel, split.trainWindows, hyperparameters).score(hyperparameters)
                val outOfSample = trainer.evaluate(tmpModel, split.testWindows, hyperparameters).score(hyperparameters)
                ConfigSplitScore(index, inSample, outOfSample)
            } finally {
                tmpModel.delete()
            }
        }
        return ConfigPerformance(hyperparameters, scores)
    }

    /** Design doc §3.3 step 5's "Promote on pass" - retrains the gate's winning config on every available window, stages it, promotes it, and reloads inference, rolling back if the newly promoted file doesn't load. */
    private suspend fun promote(
        app: SyncoraApplication,
        trainer: PpoTrainer,
        baseModelFile: File,
        windows: List<RolloutWindow>,
        decision: GateDecision.Pass,
    ) {
        reportProgress(TrainingProgress.Stage.PROMOTING, 90, "Retraining gate winner on ${windows.size} window(s)")
        val trainedAt = System.currentTimeMillis()
        val trainResult = trainer.train(
            baseModelFile = baseModelFile,
            windows = windows,
            outputModelFile = app.policyModelStore.candidateModelFile,
            hyperparameters = decision.winningHyperparameters,
        )
        Log.i(
            TAG,
            "Trained full-data candidate ($trainResult) using gate winner ${decision.winningHyperparameters}; " +
                "PBO=${decision.pboProbability} over ${decision.splitsEvaluated} splits",
        )

        val gateSummary = String.format(
            Locale.US,
            "%s, PBO=%.3f over %d splits",
            decision.winningHyperparameters,
            decision.pboProbability,
            decision.splitsEvaluated,
        )

        reportProgress(TrainingProgress.Stage.RELOADING, 95, "Staging and reloading inference")
        if (!app.policyModelStore.promoteCandidateToLive()) {
            Log.e(TAG, "Gate passed but staging the candidate model failed; nothing was promoted")
            app.trainingRunStore.lastGateDecisionAtMs = trainedAt
            app.trainingRunStore.lastGateDecisionPassed = false
            app.trainingRunStore.lastGateDecisionSummary = "Gate passed ($gateSummary) but staging the candidate failed"
            reportProgress(TrainingProgress.Stage.FAILED, 100, "Staging the candidate model failed")
            return
        }

        if (!app.policyInferenceEngine.reload()) {
            Log.e(TAG, "Newly promoted candidate failed to load; rolling back to the previous live model")
            app.policyModelStore.rollbackToPreviousLive()
            app.policyInferenceEngine.reload()
            app.trainingRunStore.lastGateDecisionAtMs = trainedAt
            app.trainingRunStore.lastGateDecisionPassed = false
            app.trainingRunStore.lastGateDecisionSummary = "Gate passed ($gateSummary) but the promoted file failed to load; rolled back"
            reportProgress(TrainingProgress.Stage.FAILED, 100, "Promoted file failed to load; rolled back")
            return
        }

        app.trainingRunStore.lastPromotionAtMs = trainedAt
        app.trainingRunStore.lastGateDecisionAtMs = trainedAt
        app.trainingRunStore.lastGateDecisionPassed = true
        app.trainingRunStore.lastGateDecisionSummary = "Promoted — $gateSummary"
        // Everything this run pulled (and anything older that was somehow
        // still unpruned) is now baked into the promoted model and will
        // never be re-pulled, since the next run's sinceMs is trainedAt.
        val pruned = app.experienceLogStore.deleteResolvedBefore(trainedAt)
        Log.i(TAG, "Promoted new policy at $trainedAt; pruned $pruned resolved row(s) older than the new watermark")
        reportProgress(TrainingProgress.Stage.PROMOTED, 100, gateSummary)
    }

    /** Thin wrapper over [CoroutineWorker.setProgress] using the shared [TrainingProgress] key/stage schema - best-effort, since a progress update racing the worker's own completion is never worth failing the run over. */
    private suspend fun reportProgress(stage: TrainingProgress.Stage, percent: Int, detail: String) {
        try {
            setProgress(
                workDataOf(
                    TrainingProgress.KEY_STAGE to stage.wireValue,
                    TrainingProgress.KEY_PERCENT to percent,
                    TrainingProgress.KEY_DETAIL to detail,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish training progress (stage=${stage.wireValue}): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PolicyTrainingWorker"

        /** Below this many resolved transitions since the last promotion, a retrain isn't worth the compute - too little data to say anything about generalization, let alone train a stable policy update. */
        private const val MIN_TRANSITIONS_FOR_TRAINING = 500

        // A deliberately small sweep (design doc §4's "Sweep H hyperparameter
        // configurations") - three clip-epsilon values sharing everything
        // else. Kept small because each entry costs a full CPCV pass (train
        // + evaluate per split) on-device; see class kdoc's compute-budget
        // note.
        private val HYPERPARAMETER_SWEEP = listOf(
            PpoHyperparameters(clipEpsilon = 0.1f),
            PpoHyperparameters(clipEpsilon = 0.2f),
            PpoHyperparameters(clipEpsilon = 0.3f),
        )
    }
}
