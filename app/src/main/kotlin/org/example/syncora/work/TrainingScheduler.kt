package org.example.syncora.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Schedules/cancels [PolicyTrainingWorker] as daily `WorkManager` periodic
 * work (design doc §2.2 item 2 / §3.3: the batch training step "triggered
 * from a scheduled foreground window, not per-tick"). All the actual
 * training/gating logic lives in [PolicyTrainingWorker]; this object only
 * owns the schedule.
 *
 * **Constraints.** No network is required - training reads/writes are
 * entirely local (SQLite experience log in, `.tflite` file out) - but the
 * job is still gated on the battery not being critically low, since a PPO
 * sweep across several hyperparameter configs and CPCV splits (see
 * [PolicyTrainingWorker]'s compute-budget note) is real, sustained CPU
 * work that shouldn't run the last few percent of someone's battery down.
 * [Constraints.setRequiresDeviceIdle] is deliberately *not* set: most
 * phones rarely satisfy Doze's strict idle definition, and this job
 * doesn't need it the way, say, a large download batch would - starving it
 * waiting for true idle would mean it rarely runs at all.
 *
 * **Idempotent scheduling.** [schedule] uses
 * [ExistingPeriodicWorkPolicy.KEEP], the same idempotence discipline
 * [org.example.syncora.SyncoraApplication.ensureMarketDataStarted] already
 * applies to its own start calls - calling this on every app/service start
 * doesn't reset an already-scheduled job's next-run window.
 */
object TrainingScheduler {
    const val UNIQUE_WORK_NAME = "policy_ppo_training"

    private val TRAINING_INTERVAL = 1L to TimeUnit.DAYS

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<PolicyTrainingWorker>(TRAINING_INTERVAL.first, TRAINING_INTERVAL.second)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Not currently called anywhere in the app - here for symmetry/tests and in case a future settings screen wants a "pause training" toggle. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
