package org.example.syncora.agent

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.RequiresDevice
import org.example.syncora.bitget.Timeframe
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * On-device timing for [ReservoirEngine.step] at `n_hidden = 150` (the top
 * of Phase 2's allowed 50-150 range, so this is the pessimistic case).
 *
 * ### This must be run on the physical MT6765G target device
 * `./gradlew connectedAndroidTest` against the target hardware (2x Cortex-A75
 * + 6x Cortex-A55, aarch64, no ML accelerator) - **not** an emulator, and
 * not a host JVM `test` (which is why this lives under `androidTest`, not
 * `test`). Emulator x86_64 timing and desktop JIT timing are both
 * meaningless proxies for the actual A55 cores this runs on in production;
 * neither substitutes for this. [logDeviceFingerprint] prints [Build.MODEL]
 * / [Build.HARDWARE] at the start of the run specifically so a CI log or a
 * screenshot of test output can be checked against "was this actually the
 * MT6765G" before trusting the result.
 *
 * ### Budget
 * The bar-close cadence is at minimum [Timeframe.ONE_MINUTE]'s 60s (the
 * app's shortest selectable timeframe - see `docs/agent-design-
 * contract.md` / the implementation plan's "bar-close cadence, not tick
 * cadence" note). A single reservoir step is one small piece of the full
 * Phase 1->5 chain that must fit inside that window, so the exit criterion
 * here is deliberately generous headroom, not "just barely fits": one step
 * must average under [BUDGET_FRACTION_OF_BAR] of the bar interval.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@RequiresDevice // enforced at the test-runner level: refuses to run on an emulator
class ReservoirEngineBenchmarkTest {

    private companion object {
        const val TAG = "ReservoirBenchmark"
        const val N_HIDDEN = 150
        const val N_INPUT = 6 // FeatureAssembler.FEATURE_WIDTH
        const val N_BACK = 5 // PolicyEngine.DEFAULT_N_BACK - the realistic feedback width
        const val WARMUP_STEPS = 2_000
        const val MEASURED_STEPS = 20_000

        // One step must comfortably fit inside the bar interval - budget it
        // at well under 1% of the shortest bar (60s), leaving effectively
        // all of the interval for the rest of the Phase 1->5 chain plus
        // rendering/UI work sharing the same device.
        const val BUDGET_FRACTION_OF_BAR = 0.01
    }

    private fun logDeviceFingerprint() {
        Log.i(TAG, "Running on MODEL=${Build.MODEL} HARDWARE=${Build.HARDWARE} SOC_MODEL=${socModelOrUnknown()}")
    }

    private fun socModelOrUnknown(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown (API < 31)"

    @Test
    fun step_at_n150_fits_comfortably_within_the_bar_close_budget() {
        logDeviceFingerprint()

        val weights = ReservoirWeights.randomWeights(nInput = N_INPUT, nHidden = N_HIDDEN, seed = 1234L)
        val engine = ReservoirEngine(weights)
        val rng = Random(5678L)

        // Warm up the JIT / tier-up compiler before timing, so the measured
        // loop reflects steady-state throughput, not interpreter startup.
        repeat(WARMUP_STEPS) {
            engine.step(FloatArray(N_INPUT) { rng.nextFloat() * 2f - 1f })
        }

        val inputs = Array(MEASURED_STEPS) { FloatArray(N_INPUT) { rng.nextFloat() * 2f - 1f } }

        val startNanos = System.nanoTime()
        for (u in inputs) {
            engine.step(u)
        }
        val elapsedNanos = System.nanoTime() - startNanos

        val avgStepNanos = elapsedNanos.toDouble() / MEASURED_STEPS
        val barIntervalNanos = Timeframe.ONE_MINUTE.durationMillis * 1_000_000.0
        val budgetNanos = barIntervalNanos * BUDGET_FRACTION_OF_BAR

        Log.i(
            TAG,
            "n_hidden=$N_HIDDEN avg step = %.1f us over %d steps (budget = %.1f us, bar interval = %.0f ms)"
                .format(avgStepNanos / 1_000.0, MEASURED_STEPS, budgetNanos / 1_000.0, barIntervalNanos / 1_000_000.0),
        )

        assertTrue(
            "reservoir step averaged %.1f us, which exceeds the %.1f us headroom budget (%.2f%% of the %dms bar interval)"
                .format(
                    avgStepNanos / 1_000.0,
                    budgetNanos / 1_000.0,
                    BUDGET_FRACTION_OF_BAR * 100,
                    Timeframe.ONE_MINUTE.durationMillis,
                ),
            avgStepNanos < budgetNanos,
        )
    }

    /**
     * Same shape as [step_at_n150_fits_comfortably_within_the_bar_close_budget],
     * but with gap-closure #1's `W_back` feedback path enabled at
     * [N_BACK] = 5 (matching [PolicyEngine.DEFAULT_N_BACK]) - the added
     * `O(nHidden * nBack)` cost the gap-closure plan calls out as "small
     * relative to the existing `O(nHidden^2)` term". This should land at
     * essentially the same average step time as the feedback-free case
     * above, well within the same headroom budget.
     */
    @Test
    fun step_at_n150_with_wBack_feedback_still_fits_comfortably_within_the_bar_close_budget() {
        logDeviceFingerprint()

        val weights = ReservoirWeights.randomWeights(nInput = N_INPUT, nHidden = N_HIDDEN, nBack = N_BACK, seed = 1234L)
        val engine = ReservoirEngine(weights)
        val rng = Random(5678L)

        repeat(WARMUP_STEPS) {
            engine.step(FloatArray(N_INPUT) { rng.nextFloat() * 2f - 1f }, ownOutput = rng.nextFloat() * 2f - 1f)
        }

        val inputs = Array(MEASURED_STEPS) { FloatArray(N_INPUT) { rng.nextFloat() * 2f - 1f } }
        val ownOutputs = FloatArray(MEASURED_STEPS) { rng.nextFloat() * 2f - 1f }

        val startNanos = System.nanoTime()
        for (i in inputs.indices) {
            engine.step(inputs[i], ownOutputs[i])
        }
        val elapsedNanos = System.nanoTime() - startNanos

        val avgStepNanos = elapsedNanos.toDouble() / MEASURED_STEPS
        val barIntervalNanos = Timeframe.ONE_MINUTE.durationMillis * 1_000_000.0
        val budgetNanos = barIntervalNanos * BUDGET_FRACTION_OF_BAR

        Log.i(
            TAG,
            "n_hidden=$N_HIDDEN n_back=$N_BACK avg step = %.1f us over %d steps (budget = %.1f us, bar interval = %.0f ms)"
                .format(avgStepNanos / 1_000.0, MEASURED_STEPS, budgetNanos / 1_000.0, barIntervalNanos / 1_000_000.0),
        )

        assertTrue(
            "reservoir step with W_back averaged %.1f us, which exceeds the %.1f us headroom budget (%.2f%% of the %dms bar interval)"
                .format(
                    avgStepNanos / 1_000.0,
                    budgetNanos / 1_000.0,
                    BUDGET_FRACTION_OF_BAR * 100,
                    Timeframe.ONE_MINUTE.durationMillis,
                ),
            avgStepNanos < budgetNanos,
        )
    }
}
