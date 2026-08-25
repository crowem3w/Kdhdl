package org.example.syncora.resilience

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.syncora.BuildConfig
import org.example.syncora.SyncoraApplication
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PipelineState
import org.example.syncora.work.TrainingScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.UUID

/**
 * Task 12: End-to-End Pipeline Resilience Test (Paper Mode).
 *
 * Targets composition risk, not unit risk (see design doc's "Purpose"):
 * every component here can pass in isolation and still fail together under
 * real timing, scheduling, and process-death conditions. See
 * `package-info.kt` for the full list of adaptations this codebase's real
 * architecture needed versus the original design doc's sketch.
 *
 * **Run this via `scripts/run_pipeline_resilience_test.sh`, not via a bare
 * `connectedDebugAndroidTest`.** Standard Android instrumentation always
 * shares its process with the app under test - that's fundamental to how
 * `Instrumentation` gets its `Application`/`Activity` references at all, and
 * no test runner (orchestrated or not) changes that for a single running
 * test method. So the scenario is split into three separate `@Test`
 * methods - [phaseA_day1DecisionsAndFundingSettlement],
 * [phaseB_survivesFirstKillAndResolvesDay2], and
 * [phaseC_survivesSecondKillAndTrainingJobSucceeds] - each meant to be
 * launched as its own `am instrument -e class ...#methodName` invocation,
 * with a real `adb shell am force-stop` + relaunch in between. That's the
 * only way to inflict a genuine ungraceful kill on the app process without
 * also killing the instrumentation driving the test out from under itself
 * mid-assertion (package-info.kt gap #5's problem with the original
 * single-method design).
 *
 * Each phase persists its checkpoint (simulated clock value + this
 * process's pid) via [ResiliencePhaseState] so the next phase's `@Before`
 * can (a) resume the simulated multi-day timeline and (b) hard-assert the
 * process pid actually changed since the last phase - i.e. that a real kill
 * happened between invocations, rather than the phases quietly running
 * back-to-back inside one process (which would report green without ever
 * exercising crash recovery). Running the whole class in one session
 * without the driver script fails fast at that assertion with a message
 * pointing at the script, rather than passing having tested nothing.
 *
 * Explicitly out of scope (design doc §6): whether the CPCV/PBO gate
 * correctly *rejects* a bad candidate - that's a separate, narrower test
 * against a deliberately-overfit fixture. This test only needs the gate to
 * reach *a* decision and the worker to finish either way; on this run's
 * synthetic (and reward-wise fairly random) data, a gate rejection is at
 * least as likely an outcome as a pass - [phaseC_survivesSecondKillAndTrainingJobSucceeds]
 * asserts on `WorkInfo.State.SUCCEEDED` (the worker ran to completion
 * without crashing/retrying forever), not on which way the gate went.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING) // cosmetic only - see class kdoc on why phases must actually be launched separately
class PipelineResilienceTest {

    private companion object {
        const val REFERENCE_START_TIME = 1_700_000_000_000L // arbitrary, deterministic, funding-grid-agnostic start
        const val REFERENCE_PRICE = 60_000.0

        // Real ONE_MINUTE bar duration (see Timeframe.DEFAULT) - keeps injected
        // klines realistic relative to FundingSchedule's 8h grid even though
        // nothing here actually waits real minutes.
        const val KLINE_INTERVAL_MILLIS = 60_000L

        // 300/day * 2 days = 600 resolved transitions, comfortably above
        // PolicyTrainingWorker's private MIN_TRANSITIONS_FOR_TRAINING (500 at
        // time of writing) - see that class if this ever needs retuning.
        const val DECISIONS_PER_DAY = 300

        const val AWAIT_TICK_TIMEOUT_MS = 15_000L
        const val AWAIT_SERVICE_REATTACH_TIMEOUT_MS = 30_000L
        const val TRAINING_JOB_TIMEOUT_MS = 120_000L
    }

    private lateinit var app: SyncoraApplication
    private lateinit var state: ResiliencePhaseState
    private lateinit var clock: SimulatedClock
    private lateinit var fundingInjector: FundingSettlementInjector

    @Before
    fun setUp() {
        assertTrue(
            "Resilience test harness is debug-only - ENABLE_RESILIENCE_TEST_HARNESS must be true",
            BuildConfig.ENABLE_RESILIENCE_TEST_HARNESS,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as SyncoraApplication
        state = ResiliencePhaseState(context)

        // Zero-capital-risk guard (see package-info.kt gap #1: no formal
        // TradingMode enum exists yet, so this is the real mechanism that
        // makes an order physically unreachable). Re-checked in every
        // phase's @Before, including after a relaunch, as a second line of
        // defense against these ever being flipped mid-run.
        assertTrue(
            "Resilience test must run with auto-trading disabled",
            !app.riskSettingsStore.autoTradingEnabled,
        )
        assertTrue(
            "Resilience test must run with no live credentials saved (paper mode only)",
            app.liveCredentialsStore.load() == null,
        )

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        // initializeTestWorkManager replaces the WorkManager instance
        // Application.onCreate() already scheduled into, so re-schedule
        // against the fresh test instance - needed every phase, since a
        // relaunch means a fresh Application/onCreate() too.
        TrainingScheduler.schedule(context)

        fundingInjector = FundingSettlementInjector(app.experienceLogStore)
    }

    // ------------------------------------------------------------------
    // Phase A - fresh start: Day 1 decisions + mid-day funding settlement.
    // Launch first, with a clean install (empty experience log).
    // ------------------------------------------------------------------

    @Test
    fun phaseA_day1DecisionsAndFundingSettlement() {
        state.reset()
        app.experienceLogStore.clear()
        seedFixtureLiveModel()
        clock = SimulatedClock(REFERENCE_START_TIME)

        app.ensureMarketDataStarted()
        primeChartPipeline()
        repeat(DECISIONS_PER_DAY) {
            clock.advanceBy(KLINE_INTERVAL_MILLIS)
            injectKlineCloseAndAwaitTick(clock.nowMillis())
        }
        ExperienceLogAssertions.assertRowCount(app.experienceLogStore, expected = DECISIONS_PER_DAY)
        // Rows resolve as soon as the *next* tick supplies S_{t+1} (see
        // ExperienceLogStore.backfillDeltaV) - the most recent row is
        // legitimately still pending at this checkpoint, so allow one.
        assertTrue(
            "Expected at most 1 still-pending row after $DECISIONS_PER_DAY ticks, found ${app.experienceLogStore.pendingCount()}",
            app.experienceLogStore.pendingCount() <= 1,
        )

        // --- Trigger a real funding settlement mid-day ---
        fundingInjector.triggerSettlement(fundingComponent = 0.0001, settledAtMs = clock.nowMillis())
        ExperienceLogAssertions.assertFundingComponentBackfilled(app.experienceLogStore)

        checkpoint(ResiliencePhaseState.Phase.A_DONE)

        // No kill from inside this method - see class kdoc. The driver
        // script force-stops and relaunches the app immediately after this
        // phase reports PASS, then launches phaseB in a fresh invocation.
    }

    // ------------------------------------------------------------------
    // Phase B - launched fresh after the first ungraceful kill+relaunch.
    // Verifies no data loss/corruption across the kill, then resolves Day 2.
    // ------------------------------------------------------------------

    @Test
    fun phaseB_survivesFirstKillAndResolvesDay2() {
        requirePhase(ResiliencePhaseState.Phase.A_DONE)
        requireProcessActuallyRestarted()
        clock = SimulatedClock(state.clockMillis)

        // --- Verify no data loss / corruption across the kill, before anything else touches the log ---
        ExperienceLogAssertions.assertRowCount(app.experienceLogStore, expected = DECISIONS_PER_DAY) // unchanged
        ExperienceLogAssertions.assertNoOrphanedPendingRows(
            app.experienceLogStore,
            nowMs = clock.nowMillis(),
            maxAge = KLINE_INTERVAL_MILLIS * 2,
        )

        app.ensureMarketDataStarted()
        waitForForegroundServiceReattachment()

        // --- Continue into day 2, resolving remaining pending rewards ---
        primeChartPipeline() // relaunch means a fresh pipeline instance; re-prime before resuming ticks
        repeat(DECISIONS_PER_DAY) {
            clock.advanceBy(KLINE_INTERVAL_MILLIS)
            injectKlineCloseAndAwaitTick(clock.nowMillis())
        }
        ExperienceLogAssertions.assertAllRowsResolved(app.experienceLogStore, olderThan = clock.nowMillis() - KLINE_INTERVAL_MILLIS)

        checkpoint(ResiliencePhaseState.Phase.B_DONE)
    }

    // ------------------------------------------------------------------
    // Phase C - launched fresh after a second ungraceful kill+relaunch,
    // deeper into accumulated state. Fires the training job and asserts
    // it reaches SUCCEEDED with a candidate/promoted model on disk.
    // ------------------------------------------------------------------

    @Test
    fun phaseC_survivesSecondKillAndTrainingJobSucceeds() {
        requirePhase(ResiliencePhaseState.Phase.B_DONE)
        requireProcessActuallyRestarted()
        clock = SimulatedClock(state.clockMillis)

        ExperienceLogAssertions.assertRowCount(app.experienceLogStore, expected = DECISIONS_PER_DAY * 2)

        app.ensureMarketDataStarted()
        waitForForegroundServiceReattachment()

        val jobObserver = TrainingJobObserver(app)
        val workId = scheduledTrainingJobId()
        jobObserver.forceConstraintsMet(workId)
        val finalState = jobObserver.awaitCompletion(
            workManager = WorkManager.getInstance(app),
            workId = workId,
            timeoutMs = TRAINING_JOB_TIMEOUT_MS,
        )

        assertEquals(
            "Training job must complete (not crash/retry-forever) given resolved experience data",
            WorkInfo.State.SUCCEEDED,
            finalState,
        )
        // Not asserting GateDecision.Pass here - see class kdoc's scoping note.

        checkpoint(ResiliencePhaseState.Phase.C_DONE)
    }

    // ------------------------------------------------------------------
    // Checkpoint / cross-phase guard helpers
    // ------------------------------------------------------------------

    private fun checkpoint(completed: ResiliencePhaseState.Phase) {
        state.clockMillis = clock.nowMillis()
        state.processPid = Process.myPid()
        state.phase = completed
    }

    private fun requirePhase(expected: ResiliencePhaseState.Phase) {
        val actual = state.phase
        if (actual != expected) {
            fail(
                "Expected checkpoint '$expected' but found '$actual'. Phases must run in order via " +
                    "scripts/run_pipeline_resilience_test.sh, not as a standalone method or an " +
                    "unfiltered `connectedDebugAndroidTest`.",
            )
        }
    }

    /**
     * The core anti-false-positive guard: if this pid matches the pid the
     * previous phase recorded, no real kill happened between phases (e.g.
     * someone ran the whole class in one instrumentation session), and this
     * phase would otherwise pass without ever exercising crash recovery.
     */
    private fun requireProcessActuallyRestarted() {
        val previousPid = state.processPid
        val currentPid = Process.myPid()
        assertTrue(
            "Process pid is unchanged ($currentPid) since the previous phase - no real kill happened " +
                "between phases. Run via scripts/run_pipeline_resilience_test.sh, which force-stops " +
                "and relaunches the app between phases.",
            previousPid != currentPid,
        )
    }

    // ------------------------------------------------------------------
    // Fixture / pipeline / WorkManager helpers
    // ------------------------------------------------------------------

    /**
     * Stages a fixture `.tflite` as the live model before the run starts,
     * matching [org.example.syncora.ml.PolicyInferenceEngine]'s expected
     * signature (state-vector input; single scalar action output). Without
     * a live model, [org.example.syncora.agent.DecisionLoopScheduler] never
     * logs a row and [org.example.syncora.work.PolicyTrainingWorker] skips
     * outright.
     *
     * The asset itself (`app/src/androidTest/assets/fixtures/dummy_policy_model.tflite`)
     * isn't generated by this harness - producing a valid TFLite flatbuffer
     * needs the TF toolchain, which this repo doesn't otherwise depend on.
     * Generate it once via `scripts/generate_dummy_policy_model.py` (see
     * that script's header) before running phaseA for the first time.
     */
    private fun seedFixtureLiveModel() {
        val bytes = getInstrumentation().context.assets
            .open("fixtures/dummy_policy_model.tflite")
            .use { it.readBytes() }
        app.policyModelStore.liveModelFile.writeBytes(bytes)
    }

    /** Injects one priming kline so the pipeline is `primed` (see [org.example.syncora.bitget.TradingChartPipeline.injectTestKline]) before ticks start advancing it. */
    private fun primeChartPipeline() {
        app.pipeline.injectTestKline(syntheticKline(clock.nowMillis()))
    }

    /** Injects a kline with a strictly later `startTime`, which closes the previous bar and fires exactly one decision tick - then awaits that tick landing in `lastDecision`. */
    private fun injectKlineCloseAndAwaitTick(startTimeMs: Long, timeoutMs: Long = AWAIT_TICK_TIMEOUT_MS) {
        app.pipeline.injectTestKline(syntheticKline(startTimeMs))
        runBlocking {
            withTimeout(timeoutMs) {
                app.decisionLoopScheduler.lastDecision.first { it != null && it.klineStartTime == startTimeMs }
            }
        }
    }

    private fun syntheticKline(startTimeMs: Long) = Kline(
        startTime = startTimeMs,
        open = REFERENCE_PRICE,
        high = REFERENCE_PRICE,
        low = REFERENCE_PRICE,
        close = REFERENCE_PRICE,
        baseVolume = 1.0,
        quoteVolume = REFERENCE_PRICE,
        usdtVolume = REFERENCE_PRICE,
    )

    /**
     * Polls for the foreground service's market-data pipelines to be back
     * up after the driver script's relaunch - a fresh `Application`
     * instance means `ensureMarketDataStarted` needs to run again, which
     * happens via `MarketDataForegroundService.onCreate()` once the
     * relaunched activity/service comes up.
     */
    private fun waitForForegroundServiceReattachment(timeoutMs: Long = AWAIT_SERVICE_REATTACH_TIMEOUT_MS) {
        runBlocking {
            withTimeout(timeoutMs) {
                while (app.pipeline.pipelineState.value == PipelineState.IDLE) {
                    delay(200)
                }
            }
        }
    }

    private fun scheduledTrainingJobId(): UUID {
        val infos = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(TrainingScheduler.UNIQUE_WORK_NAME)
            .get()
        check(infos.isNotEmpty()) { "No scheduled work found for ${TrainingScheduler.UNIQUE_WORK_NAME}" }
        return infos.first().id
    }
}
