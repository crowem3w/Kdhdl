package org.example.syncora.bitget

import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DirectRLReadout]'s reward/utility calculation - Eq. 6-12 and
 * Algorithm 1 of `recurrent-reinforcement-learning-crypto-agent.md`.
 *
 * The golden values in `matches independent oracle over a five-tick run`
 * come from an independently written Python transcription of the same
 * equations (not a copy of this Kotlin code), run once offline. Cross-checking against a second,
 * independently-written implementation of the formulas - rather than
 * against values re-derived from the Kotlin under test - is what makes
 * this a meaningful regression check on the math itself, not just a
 * change-detector for whatever the code currently does.
 *
 * Regenerated for the Fix 4 correction (train on gated P&L, not raw f_t
 * P&L): the oracle now evaluates the §3.3 trading gate before computing
 * r_t, uses the *gated* position (not raw f_t) for Delta f_t and the
 * funding term, and zeroes dr_t/df_t whenever the gate is closed (since a
 * gate-flattened tick's realized position no longer depends on this
 * tick's own raw output). Ticks 0-1 are unchanged from the pre-Fix-4
 * golden values (the gate is open throughout both, so gated == f there);
 * ticks 2-4 differ, since the gate is closed starting tick 2.
 */
class DirectRLReadoutTest {

    private fun sample(
        z: DoubleArray,
        deltaP: Double,
        execLong: Double?,
        execShort: Double?,
        kappaT: Double?,
        timestampMs: Long = 0L,
    ): AugmentedFeatureSample {
        val source = RLFeatureSample(
            timestampMs = timestampMs,
            uT = emptyList(),
            deltaP = deltaP,
            executionCostLong = execLong,
            executionCostShort = execShort,
            kappaT = kappaT,
            yHat = emptyList(),
        )
        return AugmentedFeatureSample(
            timestampMs = timestampMs,
            z = z,
            x = DoubleArray(0),
            source = source,
        )
    }

    private fun assertClose(expected: Double, actual: Double, label: String, relTol: Double = 1e-6, absTol: Double = 1e-9) {
        val tol = max(absTol, relTol * abs(expected))
        assertTrue(
            "$label: expected $expected but was $actual (tol $tol)",
            abs(expected - actual) <= tol,
        )
    }

    // ---- Null-return guards: Eq. 8 must never silently substitute a zero cost/rate ----

    @Test
    fun `step returns null when funding rate is missing`() {
        val readout = DirectRLReadout(augmentedSize = 4)
        val s = sample(
            z = doubleArrayOf(0.1, 0.2, 0.3, 0.4),
            deltaP = 1.0,
            execLong = 1.0,
            execShort = 1.0,
            kappaT = null,
        )
        assertNull(readout.step(s))
    }

    @Test
    fun `step returns null when the tick's direction needs the missing execution-cost side`() {
        // These two ticks are the golden run's first two (see the oracle
        // cross-check test below): after tick 0 (w_out_0 = 0, so f_0 = 0),
        // tick 1's z drives w_out negative enough that f_1 < f_0 = 0, i.e.
        // deltaF < 0 - a short move, which Eq. 8 needs executionCostShort
        // (not executionCostLong) for.
        val readout = DirectRLReadout(augmentedSize = 4)
        readout.step(sample(doubleArrayOf(0.1, -0.2, 0.05, 0.3), 2.5, 1.0, 1.0, 0.0002))!!

        val shortMoveMissingShortCost = sample(doubleArrayOf(0.2, 0.1, -0.1, 0.4), -1.0, execLong = 1.2, execShort = null, kappaT = 0.0001)
        assertNull(readout.step(shortMoveMissingShortCost))

        // The same tick succeeds once executionCostShort is supplied -
        // confirming the null above really was about the missing cost,
        // not some other input.
        val shortMoveWithCost = sample(doubleArrayOf(0.2, 0.1, -0.1, 0.4), -1.0, execLong = 1.2, execShort = 1.1, kappaT = 0.0001)
        assertTrue(readout.step(shortMoveWithCost) != null)
    }

    @Test
    fun `reset clears state so a repeated run reproduces the first run`() {
        val readout = DirectRLReadout(augmentedSize = 4)
        val ticks = listOf(
            sample(doubleArrayOf(0.1, -0.2, 0.05, 0.3), 2.5, 1.0, 1.0, 0.0002),
            sample(doubleArrayOf(0.2, 0.1, -0.1, 0.4), -1.0, 1.2, 1.1, 0.0001),
        )
        val firstRun = ticks.map { readout.step(it)!!.utility }
        readout.reset()
        val secondRun = ticks.map { readout.step(it)!!.utility }
        assertEquals(firstRun, secondRun)
    }

    // ---- Golden cross-check against the independent oracle (Eq. 6-12, Algorithm 1) ----

    @Test
    fun `matches independent oracle over a five-tick run`() {
        val readout = DirectRLReadout(augmentedSize = 4, lambda = 0.00001, beta = 1.0, tau = 0.999)
        val ticks = listOf(
            sample(doubleArrayOf(0.1, -0.2, 0.05, 0.3), 2.5, 1.0, 1.0, 0.0002),
            sample(doubleArrayOf(0.2, 0.1, -0.1, 0.4), -1.0, 1.2, 1.1, 0.0001),
            sample(doubleArrayOf(-0.3, 0.05, 0.2, -0.1), 3.0, 0.9, 0.95, -0.0003),
            sample(doubleArrayOf(0.0, 0.0, 0.0, 0.0), 0.5, 1.0, 1.0, 0.0),
            sample(doubleArrayOf(0.15, -0.05, 0.25, -0.2), -2.0, 1.05, 1.0, 0.0005),
        )

        // f, shouldTrade, gated, r, mu, sigma2, utility, ir - per tick, from
        // the Python oracle (see class doc).
        data class Golden(
            val f: Double, val shouldTrade: Boolean, val gated: Double, val r: Double,
            val mu: Double, val sigma2: Double, val utility: Double, val ir: Double,
        )
        val golden = listOf(
            Golden(0.0, true, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            Golden(-2.3023023023022913e-08, true, -2.3023023023022913e-08, -2.5323023023022903e-08, -2.5323023023022924e-11, 6.399736252899945e-19, -2.5323023023026123e-11, 0.0),
            Golden(-4.769728315680269e-07, false, 0.0, -8.978978978978936e-08, -1.1508748978978935e-10, 8.680885884258262e-18, -1.1508748978983275e-10, -0.6200780589964049),
            Golden(0.0, false, 0.0, 0.0, -1.1497240229999956e-10, 8.672218217027293e-18, -1.1497240230004292e-10, -0.6197674700766653),
            Golden(-0.000341820699557628, false, 0.0, 0.0, -1.1485742989769956e-10, 8.663559191039469e-18, -1.1485742989774288e-10, -0.619457037199642),
        )

        ticks.zip(golden).forEachIndexed { i, (tick, g) ->
            val d = readout.step(tick)!!
            assertClose(g.f, d.targetPosition, "tick $i targetPosition")
            assertEquals("tick $i shouldTrade", g.shouldTrade, d.shouldTrade)
            assertClose(g.gated, d.gatedPosition, "tick $i gatedPosition")
            assertClose(g.r, d.netReturn, "tick $i netReturn")
            assertClose(g.mu, d.mu, "tick $i mu")
            assertClose(g.sigma2, d.variance, "tick $i variance")
            assertClose(g.utility, d.utility, "tick $i utility")
            assertClose(g.ir, d.informationRatio, "tick $i informationRatio", absTol = 1e-6)
        }
    }

    // ---- Formula-level sanity checks, independent of the golden run ----

    @Test
    fun `utility always equals mu minus half lambda times variance`() {
        val lambda = 0.00001
        val readout = DirectRLReadout(augmentedSize = 3, lambda = lambda)
        val ticks = listOf(
            sample(doubleArrayOf(0.4, -0.1, 0.2), 5.0, 2.0, 2.0, 0.001),
            sample(doubleArrayOf(-0.1, 0.3, -0.2), -4.0, 1.5, 1.5, -0.001),
            sample(doubleArrayOf(0.2, 0.2, 0.2), 1.0, 1.0, 1.0, 0.0002),
        )
        for (tick in ticks) {
            val d = readout.step(tick)!!
            assertClose(d.mu - (lambda / 2.0) * d.variance, d.utility, "utility = mu - (lambda/2) sigma^2")
        }
    }

    @Test
    fun `net return on the very first tick is zero regardless of inputs`() {
        // f_0 = tanh(w_out_0 . z) = tanh(0) = 0 = prevF_0, so Eq. 8's
        // execution-cost term is skipped (deltaF == 0), and the price and
        // funding terms both multiply a position of zero.
        val readout = DirectRLReadout(augmentedSize = 3)
        val d = readout.step(
            sample(doubleArrayOf(9.0, -9.0, 4.0), deltaP = 123.4, execLong = 1.0, execShort = 1.0, kappaT = 0.01),
        )!!
        assertEquals(0.0, d.targetPosition, 1e-12)
        assertEquals(0.0, d.netReturn, 1e-12)
        assertEquals(0.0, d.utility, 1e-12)
    }

    @Test
    fun `trading gate uses mu entering the tick, not mu after folding in this tick's return`() {
        // Per Appendix A / §3.3 ("trade freely if mu_t >= 0; else flatten
        // and wait") and the class doc's own note, the gate must be
        // evaluated against mu_{t-1} (the value entering the tick), since
        // mu_t itself isn't known until r_t is realized. Single-weight
        // readout so every intermediate value is easy to hand-verify:
        //
        //  t=0: kappaT != 0 nudges w_out away from 0 (f_0 = tanh(0) = 0,
        //       so r_0 = 0 regardless - prevF is still 0 here).
        //  t=1: w_out is now nonzero, so f_1 != 0 becomes the *next* tick's
        //       nonzero prevF; deltaP/kappaT are 0 here so r_1 = 0 too,
        //       leaving mu at 0 entering t=2.
        //  t=2: a large deltaP against the nonzero prevF from t=1 makes
        //       r_2 (and so mu after this tick) strongly negative - but
        //       *this* tick's own shouldTrade must still be true, since it
        //       was evaluated against mu_1 = 0.
        //  t=3: shouldTrade must now be false, since mu entering this tick
        //       is the strongly negative mu produced by t=2.
        val readout = DirectRLReadout(augmentedSize = 1, tau = 0.5)

        readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.1))!!
        val t1 = readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        assertClose(0.0, t1.mu, "mu entering t=2", absTol = 1e-9)
        assertTrue("expected w_out to have moved off zero by t=1", t1.targetPosition != 0.0)

        val t2 = readout.step(sample(doubleArrayOf(1.0), deltaP = 500.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        assertTrue("expected this tick's realized r (and so mu) to go strongly negative", t2.mu < -1.0)
        assertTrue("t=2's own gate should still reflect mu_1 = 0", t2.shouldTrade)

        val t3 = readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        assertFalse("t=3's gate must reflect the strongly negative mu produced by t=2", t3.shouldTrade)
    }

    // ---- Fix 4 acceptance criteria: train on gated P&L, not raw f_t P&L ----

    @Test
    fun `funding term uses gated position, not raw f_t, once the gate is closed`() {
        // Same driving sequence as "trading gate uses mu entering the
        // tick...": t=0's nonzero kappaT nudges w_out off zero, t=1 leaves
        // mu at 0 entering t=2, and t=2's huge deltaP against the nonzero
        // f_1 sends mu strongly negative, closing the gate for t=3.
        val readout = DirectRLReadout(augmentedSize = 1, tau = 0.5)
        readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.1))!!
        readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        readout.step(sample(doubleArrayOf(1.0), deltaP = 500.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!

        // t=3: gate is closed (see the referenced test), so gated_3 = 0.
        // deltaP/cost are held at 0 here so only the funding term (kappaT *
        // position) can contribute to r_3 - a nonzero kappaT with the bug
        // (funding on raw f_3, which is not 0 here) would make r_3 != 0.
        val t3 = readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.5))!!
        assertFalse("t=3's gate must be closed for this case to be meaningful", t3.shouldTrade)
        assertTrue("expected raw f_3 to be nonzero for this case to be meaningful", t3.targetPosition != 0.0)
        assertEquals(0.0, t3.gatedPosition, 1e-12)
        assertEquals(
            "funding term must use gated (0), not raw f_3 (nonzero) - r_3 should be exactly 0 with deltaP/cost held at 0",
            0.0,
            t3.netReturn,
            1e-12,
        )
    }

    @Test
    fun `next tick's price term uses the previous gated position, not raw f_t, once the gate has closed`() {
        val readout = DirectRLReadout(augmentedSize = 1, tau = 0.5)
        readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.1))!!
        readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        readout.step(sample(doubleArrayOf(1.0), deltaP = 500.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        val t3 = readout.step(sample(doubleArrayOf(1.0), deltaP = 0.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        assertFalse("t=3's gate must be closed for this case to be meaningful", t3.shouldTrade)
        assertTrue("expected raw f_3 to be nonzero for this case to be meaningful", t3.targetPosition != 0.0)

        // t=4: cost/funding held at 0 so only the price term
        // (deltaP * f_{t-1}) can contribute to r_4. With the bug (prevF
        // rolled forward as raw f_3, which is nonzero), a large deltaP
        // here would make r_4 strongly nonzero; with the fix (prevF rolled
        // forward as gated_3 = 0), r_4 must be exactly 0.
        val t4 = readout.step(sample(doubleArrayOf(1.0), deltaP = 777.0, execLong = 0.0, execShort = 0.0, kappaT = 0.0))!!
        assertEquals(
            "price term must use the previous *gated* position (0), not raw f_3 (nonzero)",
            0.0,
            t4.netReturn,
            1e-12,
        )
    }

    @Test
    fun `execution cost is only charged when position actually changes`() {
        val readout = DirectRLReadout(augmentedSize = 3)
        // First tick: f stays at 0 (w_out_0 = 0), so no cost regardless of
        // how large execLong/execShort are.
        val d = readout.step(
            sample(doubleArrayOf(0.0, 0.0, 0.0), deltaP = 10.0, execLong = 999.0, execShort = 999.0, kappaT = 0.0),
        )!!
        assertEquals(0.0, d.netReturn, 1e-12)
    }
}
