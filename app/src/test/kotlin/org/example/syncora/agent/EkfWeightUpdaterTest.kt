package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Unit tests for [EkfWeightUpdater] - gap-closure #3
 * (`agent-architecture-gap-closure.md`). Structurally mirrors
 * [ReadoutTrainerTest]'s convergence / stability / numerical-guard
 * patterns, since the two recursions ([EkfWeightUpdater]'s `P` update and
 * [ReadoutTrainer]'s RLS covariance downdate) are the same shape - see
 * [EkfWeightUpdater]'s own class doc.
 */
class EkfWeightUpdaterTest {

    // ---- convergence ----------------------------------------------------

    @Test
    fun `repeated updates against a fixed target gradient converge the weights toward that target`() {
        // A minimal stand-in for what PolicyEngine actually does: treat
        // "gradUtility_t = target - w_{t-1}" as the utility gradient of
        // maximising -||w - target||^2 / 2 (i.e. driving w toward target),
        // and confirm the EKF-driven w += delta sequence actually gets
        // there - the property gap-closure #3 exists to give PolicyEngine.
        val nWeights = 12
        val target = FloatArray(nWeights) { i -> (i % 5 - 2) * 0.3f }
        val updater = EkfWeightUpdater(nWeights = nWeights, beta = 0.01f, tau = 0.995f)
        val w = FloatArray(nWeights)

        var earlyError = 0f
        var lateError = 0f
        repeat(300) { step ->
            val grad = FloatArray(nWeights) { i -> target[i] - w[i] }
            val delta = updater.computeDelta(grad)
            for (i in 0 until nWeights) w[i] += delta[i]

            val error = (0 until nWeights).sumOf { abs(target[it] - w[it]).toDouble() }.toFloat()
            if (step == 5) earlyError = error
            if (step == 299) lateError = error
        }

        assertTrue("expected late-stage error ($lateError) to be much smaller than early-stage error ($earlyError)", lateError < earlyError * 0.05f)
        assertTrue("expected the weights to have converged closely to the target (error=$lateError)", lateError < 0.05f)
    }

    @Test
    fun `converges faster in early steps than a small fixed-rate update would from the same start`() {
        // The paper's whole justification for EKF over plain fixed-rate
        // gradient ascent (what PolicyEngine.update used before
        // gap-closure #3) is adaptive, faster early convergence - pin that
        // comparison directly on the same synthetic target-tracking setup.
        val nWeights = 6
        val target = FloatArray(nWeights) { 0.5f }

        val ekf = EkfWeightUpdater(nWeights = nWeights, beta = 0.01f, tau = 0.995f)
        val wEkf = FloatArray(nWeights)
        repeat(10) {
            val grad = FloatArray(nWeights) { i -> target[i] - wEkf[i] }
            val delta = ekf.computeDelta(grad)
            for (i in 0 until nWeights) wEkf[i] += delta[i]
        }
        val ekfError = (0 until nWeights).sumOf { abs(target[it] - wEkf[it]).toDouble() }.toFloat()

        val fixedRate = 0.01f // a conservative, stable fixed learning rate, same order PolicyEngine used pre-gap-closure-#3
        val wFixed = FloatArray(nWeights)
        repeat(10) {
            for (i in 0 until nWeights) wFixed[i] += fixedRate * (target[i] - wFixed[i])
        }
        val fixedError = (0 until nWeights).sumOf { abs(target[it] - wFixed[it]).toDouble() }.toFloat()

        assertTrue(
            "expected the EKF-driven update (error=$ekfError) to converge faster than a conservative fixed-rate update (error=$fixedError) over the same 10 steps",
            ekfError < fixedError,
        )
    }

    // ---- covariance shape / init -----------------------------------------

    @Test
    fun `covariance is initialized to I over beta`() {
        val nWeights = 5
        val beta = 4f
        val updater = EkfWeightUpdater(nWeights = nWeights, beta = beta)
        val p = updater.covarianceSnapshot()
        for (i in 0 until nWeights) {
            for (j in 0 until nWeights) {
                val expected = if (i == j) 1f / beta else 0f
                assertEquals("P[$i,$j] mismatch", expected, p[i * nWeights + j], 1e-6f)
            }
        }
        assertEquals(1f / beta, updater.covarianceMagnitude(), 1e-6f)
    }

    // ---- stability / non-divergence --------------------------------------

    @Test
    fun `remains stable over a long noisy replay`() {
        val nWeights = 40
        val updater = EkfWeightUpdater(nWeights = nWeights, beta = 0.05f, tau = 0.99f)
        val rng = Random(11L)
        repeat(5000) {
            val grad = FloatArray(nWeights) { (rng.nextFloat() * 2f - 1f) * 3f }
            updater.computeDelta(grad)
            assertTrue("EKF diverged (non-finite P entry found)", updater.isStable())
        }
    }

    @Test
    fun `covarianceMagnitude is a finite early-warning signal that tracks isStable`() {
        val nWeights = 10
        val updater = EkfWeightUpdater(nWeights = nWeights, beta = 0.1f, tau = 0.995f)
        val rng = Random(22L)
        repeat(1000) {
            val grad = FloatArray(nWeights) { (rng.nextFloat() * 2f - 1f) }
            updater.computeDelta(grad)
            assertTrue(updater.isStable())
            assertTrue(updater.covarianceMagnitude().isFinite())
        }
    }

    // ---- construction guards ----------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `nWeights of 0 is rejected`() {
        EkfWeightUpdater(nWeights = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `beta of 0 is rejected`() {
        EkfWeightUpdater(nWeights = 5, beta = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative beta is rejected`() {
        EkfWeightUpdater(nWeights = 5, beta = -1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tau of 0 is rejected`() {
        EkfWeightUpdater(nWeights = 5, tau = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative tau is rejected`() {
        EkfWeightUpdater(nWeights = 5, tau = -0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gradUtility of the wrong width is rejected`() {
        val updater = EkfWeightUpdater(nWeights = 8)
        updater.computeDelta(FloatArray(3))
    }
}
