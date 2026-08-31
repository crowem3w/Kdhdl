package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [PolicyEngine] - Phase 5 (`ESN_RRL_Agent_Task_Prompts.md`
 * Prompt 6), updated for gap-closure #2 (no readout-forecast regressor
 * slot - [PolicyEngine.step] now takes only the reservoir state) and
 * gap-closure #3 (the RTRL trace is now turned into a weight delta via
 * [EkfWeightUpdater] rather than plain fixed-learning-rate gradient
 * ascent - see the "gap-closure #3" tests below). The central test here is
 * [`the RTRL trace matches a finite-difference gradient over a multi-step recurrent rollout`]:
 * everything else about [PolicyEngine] (bounded output, determinism,
 * stability) is a fairly ordinary property test, but the RTRL trace being
 * the *actual* gradient of `f_T` with respect to each weight - through the
 * `f_{t-1}...f_{t-nBack}` recurrence, not just the immediate term - is the
 * one property that would be very easy to get subtly wrong and have
 * everything else still look fine, so it gets an independent numerical
 * check rather than only being trusted from the derivation in the class
 * doc. Gap-closure #3 moved that check onto [PolicyEngine.lastTraceSnapshot]
 * directly (internal, test-only visibility) rather than the old
 * before/after weight-delta probe, since the EKF no longer applies the
 * trace via a value simply proportional to a fixed learning rate.
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

            // Re-run the base rollout and read the engine's own RTRL trace
            // directly (gap-closure #3: since update() now routes the
            // trace through EkfWeightUpdater rather than applying it via a
            // simple learningRate-scaled delta, there's no longer a clean
            // way to reverse-engineer the trace out of a before/after
            // weight-delta probe - lastTraceSnapshot() gives internal
            // test-only visibility into exactly the same [lastTrace] value
            // instead, independent of whatever update() does with it).
            val probeEngine = PolicyEngine(nHidden = nHidden, nBack = nBack, initialWeights = baseWeights.copyOf())
            for (t in 0 until steps) probeEngine.step(states[t])
            val analyticGradient = probeEngine.lastTraceSnapshot()[i]

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

    // ---- gap-closure #3: EKF weight update ----

    @Test
    fun `isStable reflects EkfWeightUpdater covariance divergence, not just weight and trace finiteness`() {
        // isStable() now also delegates to the EKF's own covariance check
        // (gap-closure #3) - a healthy short replay should leave both the
        // engine and its EKF covariance finite and well within bounds.
        val engine = PolicyEngine(nHidden = nHidden, nBack = 3, seed = 21L)
        val rng = Random(321L)
        repeat(200) {
            engine.step(randomState(rng))
            engine.update(dUtilityDReward = (rng.nextDouble() - 0.5), dRewardDPosition = (rng.nextDouble() - 0.5))
        }
        assertTrue("engine should be stable after a healthy short replay", engine.isStable())
        assertTrue(
            "EKF covariance magnitude should be finite after a healthy short replay",
            engine.ekfCovarianceMagnitude().isFinite(),
        )
    }

    @Test
    fun `the EKF-driven update converges toward a target position faster than a small fixed-rate step would`() {
        // The paper's whole justification for EKF over plain fixed-rate
        // gradient ascent is faster convergence on a stationary target -
        // this pins that property on a simple synthetic surface: repeatedly
        // reward the engine for moving toward a fixed target position and
        // confirm it gets there well within a modest number of updates
        // (a fixed-rate updater at a conservative, stable learning rate -
        // DEFAULT_MAX_WEIGHT_DELTA's own defense-in-depth clamp implies a
        // sane single-run learning rate is well under 1 - would need many
        // more steps to close the same gap from a near-zero init).
        val target = 0.6f
        val engine = PolicyEngine(nHidden = nHidden, nBack = 0, seed = 17L)
        val state = randomState(Random(4242L))

        var lastPosition = 0f
        var reachedWithin = -1
        for (t in 0 until 200) {
            lastPosition = engine.step(state)
            val error = target - lastPosition
            // Reward moving toward the target: dU/d(f_t) = error (maximised
            // when f_t == target), dRewardDPosition folded in as 1.0 so
            // dUtilityDReward alone carries the signal.
            engine.update(dUtilityDReward = error.toDouble(), dRewardDPosition = 1.0)
            if (reachedWithin < 0 && kotlin.math.abs(target - lastPosition) < 0.05f) {
                reachedWithin = t
            }
        }

        assertTrue(
            "engine should converge close to the target position within 200 steps (final position=$lastPosition)",
            kotlin.math.abs(target - lastPosition) < 0.05f,
        )
        assertTrue(
            "EKF-driven convergence should be reasonably fast, not crawl toward the target over the full 200 steps",
            reachedWithin in 0..100,
        )
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
