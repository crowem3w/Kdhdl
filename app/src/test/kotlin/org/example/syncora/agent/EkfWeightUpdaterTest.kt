package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [EkfWeightUpdater] - gap-closure #3
 * (`agent-architecture-gap-closure.md`). Structurally mirrors
 * [ReadoutTrainerTest] since both classes are the same shape of
 * recursive, `O(n^2)`-per-step, flat-`FloatArray` covariance update
 * (RLS there, EKF here) - same numerical-stability hazards, so the same
 * test patterns apply: PSD-ness/finiteness under repeated updates,
 * denominator-guard behavior, and convergence toward a known target.
 */
class EkfWeightUpdaterTest {

    private fun gradSequence(nWeights: Int, steps: Int, seed: Long): List<FloatArray> {
        val rng = Random(seed)
        return List(steps) { FloatArray(nWeights) { rng.nextFloat() * 2f - 1f } }
    }

    // ---- construction validation ----

    @Test(expected = IllegalArgumentException::class)
    fun `nWeights of 0 is rejected`() {
        EkfWeightUpdater(nWeights = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `beta of 0 is rejected`() {
        EkfWeightUpdater(nWeights = 5, beta = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative tau is rejected`() {
        EkfWeightUpdater(nWeights = 5, tau = -0.1f)
    }

    // ---- initial covariance shape ----

    @Test
    fun `covariance starts diagonal at 1 over beta`() {
        val nWeights = 6
        val beta = 0.05f
        val ekf = EkfWeightUpdater(nWeights = nWeights, beta = beta)
        val p = ekf.covarianceSnapshot()
        assertEquals(nWeights * nWeights, p.size)
        for (i in 0 until nWeights) {
            for (j in 0 until nWeights) {
                val expected = if (i == j) 1f / beta else 0f
                assertEquals("P[$i,$j]", expected, p[i * nWeights + j], 1e-6f)
            }
        }
    }

    // ---- computeDelta shape / determinism ----

    @Test
    fun `computeDelta returns a delta of the expected width and is deterministic for identical inputs`() {
        val nWeights = 8
        val trace = gradSequence(nWeights, steps = 1, seed = 1L)[0]

        val a = EkfWeightUpdater(nWeights = nWeights)
        val b = EkfWeightUpdater(nWeights = nWeights)
        val deltaA = a.computeDelta(trace, residual = 0.7f).copyOf()
        val deltaB = b.computeDelta(trace, residual = 0.7f).copyOf()
        assertEquals(nWeights, deltaA.size)
        assertEquals(deltaA.toList(), deltaB.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `computeDelta rejects a mismatched trace width`() {
        val ekf = EkfWeightUpdater(nWeights = 4)
        ekf.computeDelta(FloatArray(3), residual = 1f)
    }

    @Test
    fun `a non-finite residual produces an all-zero delta and leaves covariance untouched`() {
        val nWeights = 6
        val ekf = EkfWeightUpdater(nWeights = nWeights, beta = 0.2f)
        val trace = gradSequence(nWeights, steps = 1, seed = 2L)[0]
        val pBefore = ekf.covarianceSnapshot()
        val delta = ekf.computeDelta(trace, residual = Float.NaN)
        for (d in delta) assertEquals(0f, d, 0f)
        assertEquals(pBefore.toList(), ekf.covarianceSnapshot().toList())
    }

    // ---- convergence toward a known linear target ----

    @Test
    fun `EKF-updated weights converge toward a known linear target`() {
        val nWeights = 15
        val trueWeights = FloatArray(nWeights) { i -> (i % 5 - 2) * 0.2f }

        val ekf = EkfWeightUpdater(nWeights = nWeights)
        val weights = FloatArray(nWeights)
        val regressors = gradSequence(nWeights, steps = 800, seed = 11L)

        fun trueTarget(x: FloatArray): Float {
            var acc = 0f
            for (i in x.indices) acc += trueWeights[i] * x[i]
            return acc
        }
        fun dot(w: FloatArray, x: FloatArray): Float {
            var acc = 0f
            for (i in x.indices) acc += w[i] * x[i]
            return acc
        }

        var earlyAbsError = 0f
        var lateAbsError = 0f
        for ((step, x) in regressors.withIndex()) {
            val target = trueTarget(x)
            val predicted = dot(weights, x)
            val error = target - predicted
            val absError = kotlin.math.abs(error)
            if (step == 20) earlyAbsError = absError
            if (step == regressors.lastIndex) lateAbsError = absError

            // trace_i = x_i (this synthetic task's "Jacobian" is just the
            // regressor itself, i.e. treating weights as directly linear in
            // x - the same H_t role PolicyEngine's RTRL trace plays), and
            // the scalar residual is the prediction error - exactly the
            // trace/residual split PolicyEngine.update() now passes into
            // computeDelta separately (see EkfWeightUpdater's class doc).
            val delta = ekf.computeDelta(x, residual = error)
            for (i in 0 until nWeights) weights[i] += delta[i]
        }

        assertTrue("expected some learning by step 20 (error=$earlyAbsError)", earlyAbsError < 10f)
        assertTrue(
            "expected the late-stage error ($lateAbsError) to be much smaller than the early one ($earlyAbsError)",
            lateAbsError < earlyAbsError * 0.1f,
        )
        assertTrue("expected convergence close to the true linear target (error=$lateAbsError)", lateAbsError < 0.25f)
    }

    // ---- stability / non-divergence ----

    @Test
    fun `EKF covariance remains stable over a long, noisy replay`() {
        val nWeights = 20
        val ekf = EkfWeightUpdater(nWeights = nWeights, beta = 0.1f, tau = 0.995f)
        val rng = Random(4L)
        val grads = gradSequence(nWeights, steps = 5000, seed = 5L)

        for (base in grads) {
            val noisy = FloatArray(nWeights) { i -> base[i] * (1f + (rng.nextFloat() - 0.5f)) }
            ekf.computeDelta(noisy, residual = rng.nextFloat() * 2f - 1f)
            assertTrue("EKF diverged (non-finite covariance entry found)", ekf.isStable())
        }
    }

    @Test
    fun `tau of exactly 1 (no forgetting, no stabilization rescale) also stays stable`() {
        val nWeights = 10
        val ekf = EkfWeightUpdater(nWeights = nWeights, tau = 1.0f)
        val grads = gradSequence(nWeights, steps = 3000, seed = 6L)
        for (grad in grads) {
            ekf.computeDelta(grad, residual = 1f)
            assertTrue(ekf.isStable())
        }
    }

    @Test
    fun `covarianceMagnitude reports infinity if the covariance ever diverges, finite otherwise`() {
        val nWeights = 5
        val ekf = EkfWeightUpdater(nWeights = nWeights)
        assertTrue(ekf.covarianceMagnitude().isFinite())
        val grads = gradSequence(nWeights, steps = 500, seed = 9L)
        for (grad in grads) ekf.computeDelta(grad, residual = 1f)
        assertTrue("covariance magnitude should remain finite over a healthy replay", ekf.covarianceMagnitude().isFinite())
    }

    // ---- degenerate-gradient guard ----

    @Test
    fun `an all-zero trace produces an all-zero delta and leaves covariance untouched`() {
        val nWeights = 6
        val ekf = EkfWeightUpdater(nWeights = nWeights, beta = 0.2f)
        val pBefore = ekf.covarianceSnapshot()
        val delta = ekf.computeDelta(FloatArray(nWeights), residual = 1f)
        for (d in delta) assertEquals(0f, d, 0f)
        assertEquals(pBefore.toList(), ekf.covarianceSnapshot().toList())
    }
}
