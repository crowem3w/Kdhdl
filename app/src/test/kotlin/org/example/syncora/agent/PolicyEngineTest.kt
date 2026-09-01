package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [PolicyEngine] - Phase 5 (`ESN_RRL_Agent_Task_Prompts.md`
 * Prompt 6), updated for gap-closure #1 (no own feedback path - [PolicyEngine.step]
 * now takes the reservoir's already-assembled augmented state `z_t`
 * directly, `(nInput + nHidden + nBack)`-shaped) and gap-closure #2 (no
 * readout-forecast regressor slot either). Since gap-closure #1 removed
 * this class's own recurrence (the own-output feedback loop now lives on
 * [ReservoirEngine]'s fixed, untrained `W_back`), `d(f_t)/d(w_i)` is just
 * the direct single-layer `tanh` gradient - the central test here is
 * [`the trace matches a finite-difference gradient`], checking that direct
 * term is correct, rather than a multi-step recurrent-rollout check (there
 * is no recurrence left inside this class to get wrong).
 */
class PolicyEngineTest {

    private val nInput = 4
    private val nHidden = 8

    private fun randomZ(rng: Random, nBack: Int): FloatArray =
        FloatArray(nInput + nHidden + nBack) { rng.nextFloat() * 2f - 1f }

    // ---- boundedness ----

    @Test
    fun `output is always within the bounded position range regardless of extreme inputs`() {
        val nBack = 3
        val engine = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 1L)
        val extremeZ = FloatArray(nInput + nHidden + nBack) { 1000f }
        repeat(20) {
            val f = engine.step(extremeZ)
            assertTrue("f_t=$f must be in [-1, 1]", f in -1f..1f)
            engine.update(dUtilityDReward = 1.0, dRewardDPosition = 1.0)
        }
    }

    // ---- determinism ----

    @Test
    fun `two engines with identical seed and identical input sequence produce identical trajectories`() {
        val nBack = 4
        val a = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 42L)
        val b = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 42L)

        val rng = Random(7L)
        repeat(50) {
            val z = randomZ(rng, nBack)
            val fa = a.step(z)
            val fb = b.step(z)
            assertEquals(fa, fb, 0f)
            a.update(dUtilityDReward = 0.3, dRewardDPosition = -0.2)
            b.update(dUtilityDReward = 0.3, dRewardDPosition = -0.2)
        }
        assertEquals(a.weightsSnapshot().toList(), b.weightsSnapshot().toList())
    }

    // ---- regressor shape (gap-closure #1 / #2) ----

    @Test
    fun `nRegressors is exactly z_t's width plus a bias slot, no feedback or readout slot of its own`() {
        val nBack = 3
        val engine = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 1L)
        // z_t (nInput + nHidden + nBack, assembled entirely by ReservoirEngine) + bias (1).
        assertEquals(nInput + nHidden + nBack + 1, engine.nRegressors)
    }

    @Test
    fun `identical z always produces identical output, regardless of any external readout signal`() {
        // There is no readoutForecast parameter on step() at all, so this
        // is really a compile-time guarantee - this test just pins the
        // observable behavior: the same z_t, decided independently by two
        // freshly-seeded engines, never diverges, which would only be
        // possible if some hidden channel let outside information leak in.
        val nBack = 2
        val z = randomZ(Random(99L), nBack)
        val a = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 5L)
        val b = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 5L)
        assertEquals(a.step(z), b.step(z), 0f)
    }

    // ---- gradient correctness: direct term only (gap-closure #1 removed the recurrence) ----

    @Test
    fun `the trace matches a finite-difference gradient, with no recurrence left to get wrong`() {
        val nBack = 3
        val rng = Random(11L)
        val nRegressors = nInput + nHidden + nBack + 1
        val baseWeights = FloatArray(nRegressors) { rng.nextFloat() * 0.4f - 0.2f }
        val z = randomZ(Random(99L), nBack)

        val engine = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, initialWeights = baseWeights.copyOf())
        val f = engine.step(z)

        val epsilon = 1e-3f
        for (i in baseWeights.indices) {
            val perturbed = baseWeights.copyOf()
            perturbed[i] += epsilon
            val perturbedEngine = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, initialWeights = perturbed)
            val fPerturbed = perturbedEngine.step(z)

            val finiteDiffGradient = (fPerturbed - f) / epsilon
            val analyticGradient = analyticTrace(i, f, z)
            assertEquals(
                "d(f_t)/d(w_$i) mismatch",
                finiteDiffGradient.toDouble(),
                analyticGradient.toDouble(),
                5e-3,
            )
        }
    }

    /** Recomputes what [PolicyEngine.step] stores internally as `lastTrace[i]` - `dtanh * [z_t, 1]_i`, the direct term, so the test above can compare it without needing a package-visibility backdoor into the engine. */
    private fun analyticTrace(i: Int, f: Float, z: FloatArray): Float {
        val regressor = FloatArray(z.size + 1)
        System.arraycopy(z, 0, regressor, 0, z.size)
        regressor[regressor.size - 1] = 1f
        val dtanh = 1f - f * f
        return dtanh * regressor[i]
    }

    /** Confirms [PolicyEngine]'s applied [PolicyEngine.update] delta agrees with the same direct-term trace via the same weight-delta probe technique the previous (pre-gap-closure-#1) multi-step test used, now over a run of independent steps rather than a recurrent rollout - there is no rollout left to be recurrent over. */
    @Test
    fun `update's applied delta agrees with the direct-term trace across a run of independent steps`() {
        val nBack = 3
        val steps = 6
        val nRegressors = nInput + nHidden + nBack + 1
        val rng = Random(2024L)
        val baseWeights = FloatArray(nRegressors) { rng.nextFloat() * 0.3f - 0.15f }

        val zRng = Random(55L)
        val zs = List(steps) { randomZ(zRng, nBack) }

        val indicesToCheck = listOf(0, 1, nInput, nInput + nHidden - 1, nInput + nHidden, nRegressors - 1)

        for (i in indicesToCheck) {
            val probeCoefficient = 1e-4
            val probeEngine = PolicyEngine(
                nInput = nInput,
                nHidden = nHidden,
                nBack = nBack,
                initialWeights = baseWeights.copyOf(),
                learningRate = 1f,
            )
            var lastF = 0f
            for (t in 0 until steps) lastF = probeEngine.step(zs[t])
            val before = probeEngine.weightsSnapshot()[i]
            probeEngine.update(dUtilityDReward = probeCoefficient, dRewardDPosition = 1.0)
            val after = probeEngine.weightsSnapshot()[i]
            val appliedTrace = (after - before) / probeCoefficient

            val analyticGradient = analyticTrace(i, lastF, zs[steps - 1])
            assertEquals(
                "applied update delta at w_$i should match the direct-term trace of the final step",
                analyticGradient.toDouble(),
                appliedTrace.toDouble(),
                1e-2,
            )
        }
    }

    // ---- stability over a long online-learning replay ----

    @Test
    fun `stays stable across a long replay with continuous updates`() {
        val nBack = 5
        val engine = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 3L)
        val rng = Random(123L)
        repeat(5000) {
            val z = randomZ(rng, nBack)
            engine.step(z)
            val dUtility = (rng.nextDouble() - 0.5) * 2.0
            val dReward = (rng.nextDouble() - 0.5) * 2.0
            engine.update(dUtility, dReward)
            assertTrue("engine became unstable", engine.isStable())
        }
    }

    // ---- resetState ----

    @Test
    fun `resetState clears recurrent history but preserves trained weights`() {
        val nBack = 3
        val engine = PolicyEngine(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 5L)
        val rng = Random(8L)
        repeat(10) {
            engine.step(randomZ(rng, nBack))
            engine.update(0.2, 0.1)
        }
        val weightsBefore = engine.weightsSnapshot()
        engine.resetState()
        assertEquals(0f, engine.currentPosition(), 0f)
        assertEquals(weightsBefore.toList(), engine.weightsSnapshot().toList())
    }
}
