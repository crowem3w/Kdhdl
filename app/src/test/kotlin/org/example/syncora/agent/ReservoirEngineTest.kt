package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ReservoirEngineTest {

    private val nInput = FeatureAssembler.FEATURE_WIDTH
    private val nHidden = 100

    // A deterministic "replayed feature history" fixture - same generator
    // shape as FeatureAssemblerTest's longKlineFixture, just directly in
    // u_t-space so this test doesn't need real Kline/DepthSnapshot fixtures.
    private fun featureSequence(steps: Int, seed: Long = 99L): List<FloatArray> {
        val rng = Random(seed)
        return List(steps) {
            FloatArray(nInput) { rng.nextFloat() * 2f - 1f }
        }
    }

    // ---- echo state property (Definition 2) -----------------------------

    @Test
    fun `two engines with identical weights but different initial states converge to the same trajectory`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 7L)

        val zeroStart = ReservoirEngine(weights, initialState = FloatArray(nHidden))
        val randomStart = ReservoirEngine(
            weights,
            initialState = FloatArray(nHidden) { Random(123L).nextFloat() * 2f - 1f },
        )

        // Sanity: the two engines really do start apart.
        assertTrue(
            "fixture initial states should differ, or this test proves nothing",
            maxAbsDiff(zeroStart.currentState(), randomStart.currentState()) > 0.01f,
        )

        val inputs = featureSequence(steps = 500)
        var lastDiff = Float.MAX_VALUE
        for (u in inputs) {
            zeroStart.step(u)
            randomStart.step(u)
            lastDiff = maxAbsDiff(zeroStart.currentState(), randomStart.currentState())
        }

        assertTrue(
            "echo state property violated: trajectories did not converge (final max abs diff = $lastDiff)",
            lastDiff < 1e-4f,
        )
    }

    @Test
    fun `divergence after a handful of steps is already far larger than after many more`() {
        // Heavy sparsification (default alpha = 0.75) plus random sign
        // flips typically drives the *achieved* spectral radius well below
        // the pre-sparsification target (a zero-mean random matrix's
        // dominant eigenvalue magnitude scales with sqrt(n), not the n-scale
        // a non-negative matrix's does - the classic circular-law-vs-
        // Perron-Frobenius contrast). That makes contraction fast enough
        // that comparing two *late* checkpoints against each other risks
        // comparing two values that have both already underflowed to
        // exactly 0.0f, which would make a strict "< " comparison
        // meaningless. Comparing one early (still clearly non-zero)
        // checkpoint against one late (allowed to be exactly zero) sidesteps
        // that: 0.0f is always < a genuinely positive early diff, so this
        // stays robust regardless of exactly how fast this particular
        // seed's weights happen to contract.
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 11L)
        val a = ReservoirEngine(weights, initialState = FloatArray(nHidden))
        val b = ReservoirEngine(weights, initialState = FloatArray(nHidden) { 0.9f })

        val inputs = featureSequence(steps = 60)

        var diffAfterTwoSteps = 0f
        var diffAfterSixtySteps = 0f
        for ((i, u) in inputs.withIndex()) {
            a.step(u)
            b.step(u)
            val step = i + 1
            if (step == 2) diffAfterTwoSteps = maxAbsDiff(a.currentState(), b.currentState())
            if (step == 60) diffAfterSixtySteps = maxAbsDiff(a.currentState(), b.currentState())
        }

        assertTrue(
            "expected measurable divergence after only 2 steps (got $diffAfterTwoSteps) - " +
                "otherwise this test can't demonstrate contraction",
            diffAfterTwoSteps > 1e-6f,
        )
        assertTrue(
            "expected divergence to shrink by many orders of magnitude from step 2 to step 60 " +
                "(step2=$diffAfterTwoSteps, step60=$diffAfterSixtySteps)",
            diffAfterSixtySteps < diffAfterTwoSteps * 1e-3f,
        )
    }

    // ---- spectral radius / weight construction ---------------------------

    @Test
    fun `randomWeights achieves a spectral radius strictly below the target`() {
        val weights = ReservoirWeights.randomWeights(
            nInput = nInput,
            nHidden = nHidden,
            spectralRadiusTarget = 0.9f,
            seed = 3L,
        )
        assertTrue(
            "spectral radius ${weights.spectralRadiusAchieved} should be < 1 (contractive)",
            weights.spectralRadiusAchieved < 1f,
        )
        // Sign flips + sparsification can only shrink rho relative to the
        // scaled non-negative matrix (Perron-Frobenius bound) - never grow
        // it back past the target.
        assertTrue(
            "achieved rho ${weights.spectralRadiusAchieved} should not exceed the target 0.9",
            weights.spectralRadiusAchieved <= 0.9f + 1e-3f,
        )
    }

    @Test
    fun `same seed produces bit-identical weights`() {
        val w1 = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 42L)
        val w2 = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 42L)
        assertEquals(w1.wInput.toList(), w2.wInput.toList())
        assertEquals(w1.wHidden.toList(), w2.wHidden.toList())
    }

    @Test
    fun `different seeds produce different weights`() {
        val w1 = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 1L)
        val w2 = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 2L)
        assertNotEquals(w1.wHidden.toList(), w2.wHidden.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nHidden below the allowed range is rejected`() {
        ReservoirWeights.randomWeights(nInput = nInput, nHidden = 10, seed = 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nHidden above the allowed range is rejected`() {
        ReservoirWeights.randomWeights(nInput = nInput, nHidden = 200, seed = 1L)
    }

    // ---- state update mechanics -------------------------------------------

    @Test
    fun `step output is always within tanh's range`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 5L)
        val engine = ReservoirEngine(weights)
        for (u in featureSequence(steps = 50)) {
            val state = engine.step(u)
            for (x in state) {
                assertTrue("state entry $x out of tanh range", x in -1f..1f)
            }
        }
    }

    @Test
    fun `step returns the engine's own state buffer, not a fresh allocation`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 5L)
        val engine = ReservoirEngine(weights)
        val u = featureSequence(1)[0]
        val out1 = engine.step(u)
        val out2 = engine.step(u)
        assertTrue("step() should return a stable reference to internal state", out1 === out2)
    }

    @Test
    fun `resetState zeroes the state and does not touch weights`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 5L)
        val engine = ReservoirEngine(weights)
        engine.step(featureSequence(1)[0])
        assertTrue(engine.currentState().any { it != 0f })

        engine.resetState()
        for (x in engine.currentState()) assertEquals(0f, x, 0f)

        // Weights are a completely separate array - untouched by state resets.
        assertEquals(weights.wHidden.toList(), engine.weights.wHidden.toList())
    }

    @Test
    fun `deterministic replay produces bit-identical state sequence`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 21L)
        val inputs = featureSequence(steps = 40)

        val run1 = ReservoirEngine(weights)
        val states1 = inputs.map { run1.step(it).copyOf() }

        val run2 = ReservoirEngine(weights)
        val states2 = inputs.map { run2.step(it).copyOf() }

        for (i in states1.indices) {
            assertEquals(states1[i].toList(), states2[i].toList())
        }
    }

    // ---- gap-closure #1: W_back own-output feedback ----------------------

    @Test
    fun `nBack = 0 (default) makes ownOutput a no-op`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 5L)
        val withoutFeedbackArg = ReservoirEngine(weights)
        val withIgnoredFeedbackArg = ReservoirEngine(weights)

        for (u in featureSequence(steps = 25)) {
            val a = withoutFeedbackArg.step(u).copyOf()
            val b = withIgnoredFeedbackArg.step(u, ownOutput = 0.73f).copyOf()
            assertEquals(a.toList(), b.toList())
        }
    }

    @Test
    fun `two engines with identical weights but different initial states still converge with feedback enabled`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = 5, seed = 7L)

        val zeroStart = ReservoirEngine(weights, initialState = FloatArray(nHidden))
        val randomStart = ReservoirEngine(
            weights,
            initialState = FloatArray(nHidden) { Random(123L).nextFloat() * 2f - 1f },
        )

        val inputs = featureSequence(steps = 500)
        val feedbackRng = Random(321L)
        var lastDiff = Float.MAX_VALUE
        for (u in inputs) {
            // Same feedback sequence into both copies of the *same* network -
            // Definition 2 only requires the input sequence (here, u_t and
            // the feedback channel together) to match, not the state.
            val ownOutput = feedbackRng.nextFloat() * 2f - 1f
            zeroStart.step(u, ownOutput)
            randomStart.step(u, ownOutput)
            lastDiff = maxAbsDiff(zeroStart.currentState(), randomStart.currentState())
        }

        assertTrue(
            "echo state property violated with feedback enabled: trajectories did not converge (final max abs diff = $lastDiff)",
            lastDiff < 1e-3f,
        )
    }

    @Test
    fun `feedback history never contains this step's own output (no look-ahead)`() {
        val nBack = 3
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 9L)
        val engine = ReservoirEngine(weights)

        val inputs = featureSequence(steps = 10)
        val fakeFuturePositions = floatArrayOf(0.1f, -0.4f, 0.9f, -0.9f, 0.2f, 0.0f, 0.5f, -0.1f, 0.3f, -0.6f)

        val expectedHistory = ArrayDeque<Float>()
        for ((i, u) in inputs.withIndex()) {
            engine.step(u, ownOutput = fakeFuturePositions[i])
            // The history fed into *this* step's x_t must be exactly
            // [f_{t-1}, ..., f_{t-nBack}] - i.e. built from ownOutput values
            // passed on *previous* calls, never including the value just
            // passed on this call (that would be f_t leaking into ŷ_t).
            expectedHistory.addFirst(fakeFuturePositions[i])
            if (expectedHistory.size > nBack) expectedHistory.removeLast()

            val actual = engine.currentFeedbackHistory()
            for (k in 0 until nBack) {
                val expected = expectedHistory.getOrNull(k) ?: 0f
                // expectedHistory reflects state *after* this call's insert,
                // but currentFeedbackHistory() also reflects the state used
                // to *compute* x_t this step (shift happens before the
                // forward pass) - both should agree post-shift.
                assertEquals(
                    "feedback slot $k after step $i mismatched (look-ahead bug)",
                    expected,
                    actual[k],
                    0f,
                )
            }
        }
    }

    @Test
    fun `augmentedState concatenates u_t, x_t and the feedback history in order`() {
        val nBack = 4
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = nBack, seed = 13L)
        val engine = ReservoirEngine(weights)

        val u = featureSequence(1)[0]
        engine.step(u, ownOutput = 0.42f)
        val z = engine.augmentedState(u)

        assertEquals(nInput + nHidden + nBack, z.size)
        for (i in 0 until nInput) assertEquals(u[i], z[i], 0f)
        for (h in 0 until nHidden) assertEquals(engine.currentState()[h], z[nInput + h], 0f)
        for (k in 0 until nBack) assertEquals(engine.currentFeedbackHistory()[k], z[nInput + nHidden + k], 0f)
    }

    @Test
    fun `resetState clears the feedback history along with the hidden state`() {
        val weights = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = 3, seed = 17L)
        val engine = ReservoirEngine(weights)
        engine.step(featureSequence(1)[0], ownOutput = 0.5f)
        assertTrue(engine.currentFeedbackHistory().any { it != 0f })

        engine.resetState()

        for (f in engine.currentFeedbackHistory()) assertEquals(0f, f, 0f)
    }

    @Test
    fun `same seed produces bit-identical wBack, and wInput-wHidden-rho are unaffected by nBack`() {
        val withoutFeedback = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, seed = 42L)
        val withFeedback = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = 6, seed = 42L)

        assertEquals(withoutFeedback.wInput.toList(), withFeedback.wInput.toList())
        assertEquals(withoutFeedback.wHidden.toList(), withFeedback.wHidden.toList())
        assertEquals(withoutFeedback.spectralRadiusAchieved, withFeedback.spectralRadiusAchieved, 0f)

        val w1 = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = 6, seed = 99L)
        val w2 = ReservoirWeights.randomWeights(nInput = nInput, nHidden = nHidden, nBack = 6, seed = 99L)
        assertEquals(w1.wBack.toList(), w2.wBack.toList())
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        var maxDiff = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > maxDiff) maxDiff = d
        }
        return maxDiff
    }
}
