package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [PolicyEngine] - Phase 5 (`ESN_RRL_Agent_Task_Prompts.md`
 * Prompt 6), updated for gap-closure #2 (no readout-forecast regressor
 * slot - [PolicyEngine.step] now takes only the reservoir state). The
 * central test here is [`the RTRL trace matches a finite-difference gradient over a multi-step recurrent rollout`]:
 * everything else about [PolicyEngine] (bounded output, determinism,
 * stability) is a fairly ordinary property test, but the RTRL trace being
 * the *actual* gradient of `f_T` with respect to each weight - through the
 * `f_{t-1}...f_{t-nBack}` recurrence, not just the immediate term - is the
 * one property that would be very easy to get subtly wrong and have
 * everything else still look fine, so it gets an independent numerical
 * check rather than only being trusted from the derivation in the class
 * doc.
 */
class PolicyEngineTest {

    private val nHidden = 8

    private fun randomState(rng: Random): FloatArray = FloatArray(nHidden) { rng.nextFloat() * 2f - 1f }

    // ---- boundedness ----

    @Test
    fun `output is always within the bounded position range regardless of extreme inputs`() {
        val engine = PolicyEngine(nHidden = nHidden, nBack = 3, seed = 1L)
        val extremeState = FloatArray(nHidden) { 1000f }
        repeat(20) {
            val f = engine.step(extremeState)
            assertTrue("f_t=$f must be in [-1, 1]", f in -1f..1f)
            engine.update(dUtilityDReward = 1.0, dRewardDPosition = 1.0)
        }
    }

    // ---- determinism ----

    @Test
    fun `two engines with identical seed and identical input sequence produce identical trajectories`() {
        val a = PolicyEngine(nHidden = nHidden, nBack = 4, seed = 42L)
        val b = PolicyEngine(nHidden = nHidden, nBack = 4, seed = 42L)

        val rng = Random(7L)
        repeat(50) {
            val state = randomState(rng)
            val fa = a.step(state)
            val fb = b.step(state)
            assertEquals(fa, fb, 0f)
            a.update(dUtilityDReward = 0.3, dRewardDPosition = -0.2)
            b.update(dUtilityDReward = 0.3, dRewardDPosition = -0.2)
        }
        assertEquals(a.weightsSnapshot().toList(), b.weightsSnapshot().toList())
    }

    // ---- readout independence (gap-closure #2) ----

    @Test
    fun `nRegressors has no readout-forecast slot`() {
        val nBack = 3
        val engine = PolicyEngine(nHidden = nHidden, nBack = nBack, seed = 1L)
        // reservoir state (nHidden) + own-output feedback (nBack) + bias (1) - no +1 for a forecast.
        assertEquals(nHidden + nBack + 1, engine.nRegressors)
    }

    @Test
    fun `identical reservoir state always produces identical output, regardless of any external readout signal`() {
        // There is no readoutForecast parameter on step() at all anymore, so
        // this is really a compile-time guarantee - this test just pins the
        // observable behavior: the same state, decided independently by two
        // freshly-seeded engines, never diverges, which would only be
        // possible if some hidden channel let outside information leak in.
        val state = randomState(Random(99L))
        val a = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 5L)
        val b = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 5L)
        assertEquals(a.step(state), b.step(state), 0f)
    }

    // ---- RTRL gradient correctness: single step, nBack = 0 (isolates the direct term) ----

    @Test
    fun `single-step trace matches a finite-difference gradient with no recurrent feedback`() {
        val nBack = 0
        val rng = Random(11L)
        val baseWeights = FloatArray(nHidden + nBack + 1) { rng.nextFloat() * 0.4f - 0.2f }
        val state = randomState(Random(99L))

        val engine = PolicyEngine(nHidden = nHidden, nBack = nBack, initialWeights = baseWeights.copyOf())
        val f = engine.step(state)

        val epsilon = 1e-3f
        for (i in baseWeights.indices) {
            val perturbed = baseWeights.copyOf()
            perturbed[i] += epsilon
            val perturbedEngine = PolicyEngine(nHidden = nHidden, nBack = nBack, initialWeights = perturbed)
            val fPerturbed = perturbedEngine.step(state)

            val finiteDiffGradient = (fPerturbed - f) / epsilon
            val analyticGradient = analyticTrace(i, f, state, nBack)
            assertEquals(
                "d(f_t)/d(w_$i) mismatch",
                finiteDiffGradient.toDouble(),
                analyticGradient.toDouble(),
                5e-3,
            )
        }
    }

    /** Recomputes what [PolicyEngine.step] stores internally as `lastTrace[i]`, for the no-recurrence (`nBack = 0`) case, so the test above can compare it without needing a package-visibility backdoor into the engine. */
    private fun analyticTrace(
        i: Int,
        f: Float,
        state: FloatArray,
        nBack: Int
    ): Float {
        val regressor = FloatArray(state.size + nBack + 1)
        System.arraycopy(state, 0, regressor, 0, state.size)
        regressor[regressor.size - 1] = 1f
        val dtanh = 1f - f * f
        return dtanh * regressor[i]
    }

    // ---- RTRL gradient correctness: multi-step recurrent rollout ----

    @Test
    fun `the RTRL trace matches a finite-difference gradient over a multi-step recurrent rollout`() {
        val nBack = 3
        val steps = 6
        val nRegressors = nHidden + nBack + 1
        val rng = Random(2024L)
        val baseWeights = FloatArray(nRegressors) { rng.nextFloat() * 0.3f - 0.15f }

        val stateRng = Random(55L)
        val states = List(steps) { randomState(stateRng) }

        // Feedback weight indices happen to matter most for this test (they're
        // what makes the recurrence non-trivial), plus a couple of reservoir
        // indices and the bias, as a representative sample - checking all
        // ~12 weights keeps the test fast while still covering every regressor
        // category (reservoir, feedback, bias).
        val feedbackBase = nHidden
        val indicesToCheck = listOf(0, 1, nHidden - 1, feedbackBase, feedbackBase + 1, feedbackBase + 2, nRegressors - 1)

        val baseEngine = PolicyEngine(nHidden = nHidden, nBack = nBack, initialWeights = baseWeights.copyOf())
        var baseFinal = 0f
        for (t in 0 until steps) baseFinal = baseEngine.step(states[t])

        val epsilon = 1e-3f
        for (i in indicesToCheck) {
            val perturbed = baseWeights.copyOf()
            perturbed[i] += epsilon
            val perturbedEngine = PolicyEngine(nHidden = nHidden, nBack = nBack, initialWeights = perturbed)
            var perturbedFinal = 0f
            for (t in 0 until steps) perturbedFinal = perturbedEngine.step(states[t])

            val finiteDiffGradient = (perturbedFinal - baseFinal) / epsilon

            // Re-run the base rollout, capturing the engine's own trace via
            // a paired update()/weight-delta probe: since update()'s applied
            // delta is learningRate * coefficient * trace[i], a tiny
            // coefficient recovers trace[i] as delta / coefficient while
            // staying safely clear of update()'s defense-in-depth clamps
            // (DEFAULT_MAX_WEIGHT_DELTA / weightClip), which would otherwise
            // corrupt this probe the same way a naive coefficient=1 probe
            // did (that clamp is doing its job - it's just not what this
            // gradient-correctness test is trying to measure).
            val probeCoefficient = 1e-4
            val probeEngine = PolicyEngine(
                nHidden = nHidden,
                nBack = nBack,
                initialWeights = baseWeights.copyOf(),
                learningRate = 1f
            )
            for (t in 0 until steps) probeEngine.step(states[t])
            val before = probeEngine.weightsSnapshot()[i]
            probeEngine.update(dUtilityDReward = probeCoefficient, dRewardDPosition = 1.0)
            val after = probeEngine.weightsSnapshot()[i]
            val analyticGradient = (after - before) / probeCoefficient

            assertEquals(
                "d(f_T)/d(w_$i) mismatch after $steps recurrent steps",
                finiteDiffGradient.toDouble(),
                analyticGradient.toDouble(),
                1e-2,
            )
        }
    }

    // ---- stability over a long online-learning replay ----

    @Test
    fun `stays stable across a long replay with continuous updates`() {
        val engine = PolicyEngine(nHidden = nHidden, nBack = 5, seed = 3L)
        val rng = Random(123L)
        repeat(5000) {
            val state = randomState(rng)
            engine.step(state)
            val dUtility = (rng.nextDouble() - 0.5) * 2.0
            val dReward = (rng.nextDouble() - 0.5) * 2.0
            engine.update(dUtility, dReward)
            assertTrue("engine became unstable", engine.isStable())
        }
    }

    // ---- resetState ----

    @Test
    fun `resetState clears recurrent history but preserves trained weights`() {
        val engine = PolicyEngine(nHidden = nHidden, nBack = 3, seed = 5L)
        val rng = Random(8L)
        repeat(10) {
            engine.step(randomState(rng))
            engine.update(0.2, 0.1)
        }
        val weightsBefore = engine.weightsSnapshot()
        engine.resetState()
        assertEquals(0f, engine.currentPosition(), 0f)
        assertEquals(weightsBefore.toList(), engine.weightsSnapshot().toList())
    }
}
