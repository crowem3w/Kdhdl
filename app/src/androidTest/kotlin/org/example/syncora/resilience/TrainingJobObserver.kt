package org.example.syncora.resilience

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Deterministically fires [org.example.syncora.work.PolicyTrainingWorker]'s
 * constraints via `WorkManager`'s `TestDriver`, rather than waiting on real
 * device battery/idle/network state - see
 * [org.example.syncora.work.TrainingScheduler] for the constraints this
 * would otherwise wait on for real. Requires
 * [WorkManagerTestInitHelper.initializeTestWorkManager] to have already run
 * (see [PipelineResilienceTest.setUp]) so `getTestDriver` returns non-null.
 */
class TrainingJobObserver(context: Context) {
    private val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
        ?: error("WorkManagerTestInitHelper.initializeTestWorkManager() must run before TrainingJobObserver is constructed")

    fun forceConstraintsMet(workRequestId: UUID) {
        testDriver.setAllConstraintsMet(workRequestId)
    }

    fun awaitCompletion(workManager: WorkManager, workId: UUID, timeoutMs: Long): WorkInfo.State =
        runBlocking {
            withTimeout(timeoutMs) {
                workManager.getWorkInfoByIdFlow(workId)
                    .first { it.state.isFinished }
                    .state
            }
        }
}
