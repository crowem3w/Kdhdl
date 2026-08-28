package org.example.syncora.agent

import org.example.syncora.bitget.FundingSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Fixture tests for [RewardEngine] - Phase 4's exit criterion is that its
 * output matches hand-calculated reference values exactly (see
 * `docs/agent-design-contract.md` §1/§3) on every case here, including a
 * funding-crossing case and a fee-only case that isolates the transaction
 * cost term (`ESN_RRL_Agent_Task_Prompts.md`, Prompt 5).
 *
 * Every expected value below is computed independently of
 * [RewardEngine]'s implementation - either by plain arithmetic in the
 * test itself, or (for the differential Sharpe cases) via [handRolledDsr],
 * a second, deliberately separate implementation of the same formula - so
 * a bug shared between the production code and the test can't hide.
 */
class RewardEngineTest {

    private val eps = 1e-9

    // ---- reward: pure price P&L (no trade, no spread, no fee, no funding) ----

    @Test
    fun `reward reduces to mark-to-market PnL when nothing else is in play`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_100.0,
            prevPosition = 0.4,
            currPosition = 0.4,
            bid = 50_100.0,
            ask = 50_100.0,
        )
        // r_t = Δp_t . f_{t-1} = 100 * 0.4 = 40
        assertEquals(40.0, result.markToMarketPnl, eps)
        assertEquals(0.0, result.transactionCost, eps)
        assertEquals(0.0, result.fundingCost, eps)
        assertEquals(40.0, result.reward, eps)
    }

    // ---- reward: fee-only case, isolating the transaction cost term ----

    @Test
    fun `fee-only case isolates the transaction cost term`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_000.0, // Δp = 0: no price P&L to muddy the isolation
            prevPosition = 0.2,
            currPosition = 0.5, // |Δf_t| = 0.3
            bid = 49_990.0,
            ask = 50_010.0, // δ_t = 0.5*(50010-49990) = 10
            feeRate = 0.0006, // Bitget's standard taker rate
            fundingRate = 0.0, // no funding involved, as the fixture name promises
            barSpanMs = 0L,
        )
        // cost_t = (δ_t + feeRate*p_t) * |Δf_t| = (10 + 0.0006*50000) * 0.3 = (10+30)*0.3 = 12
        assertEquals(0.0, result.markToMarketPnl, eps)
        assertEquals(12.0, result.transactionCost, eps)
        assertEquals(0.0, result.fundingCost, eps)
        assertEquals(-12.0, result.reward, eps)
    }

    @Test
    fun `zero position change means zero transaction cost even with a wide spread and nonzero fee rate`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_000.0,
            prevPosition = 0.7,
            currPosition = 0.7, // Δf_t = 0
            bid = 49_900.0,
            ask = 50_100.0,
            feeRate = 0.0006,
        )
        assertEquals(0.0, result.transactionCost, eps)
        assertEquals(0.0, result.reward, eps)
    }

    // ---- reward: funding crossing a full settlement interval ----

    @Test
    fun `a bar spanning a full funding interval charges exactly notional times rate, matching settleFunding`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_000.0, // isolate funding: no price move, no position change
            prevPosition = 0.5,
            currPosition = 0.5,
            bid = 50_000.0,
            ask = 50_000.0,
            fundingRate = 0.0001, // 1 bp, a typical positive (long-pays) funding rate
            barSpanMs = FundingSchedule.INTERVAL_MS, // this bar crosses (covers) one full settlement
        )
        // funding_amount_t = N_t . fundingRate_t . sign(f_t), N_t = |f_t|*p_t = 0.5*50000 = 25000
        // = 25000 * 0.0001 * sign(0.5) = 2.5 -> a cost to the long position
        assertEquals(2.5, result.fundingCost, eps)
        assertEquals(-2.5, result.reward, eps)
    }

    @Test
    fun `funding sign convention flips for a short position at the same positive funding rate`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_000.0,
            prevPosition = -0.5,
            currPosition = -0.5,
            bid = 50_000.0,
            ask = 50_000.0,
            fundingRate = 0.0001,
            barSpanMs = FundingSchedule.INTERVAL_MS,
        )
        // Same magnitude as the long case, opposite sign: the short *receives*.
        assertEquals(-2.5, result.fundingCost, eps)
        assertEquals(2.5, result.reward, eps)
    }

    @Test
    fun `a bar spanning half a funding interval accrues exactly half the settlement amount`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_000.0,
            prevPosition = 0.5,
            currPosition = 0.5,
            bid = 50_000.0,
            ask = 50_000.0,
            fundingRate = 0.0001,
            barSpanMs = FundingSchedule.INTERVAL_MS / 2,
        )
        assertEquals(1.25, result.fundingCost, eps)
    }

    @Test
    fun `a bar with no elapsed span accrues no funding regardless of rate`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_000.0,
            prevPosition = 0.9,
            currPosition = 0.9,
            bid = 50_000.0,
            ask = 50_000.0,
            fundingRate = 0.01,
            barSpanMs = 0L,
        )
        assertEquals(0.0, result.fundingCost, eps)
    }

    // ---- reward: everything at once, hand-summed ----

    @Test
    fun `all three terms combine additively in a single bar`() {
        val engine = RewardEngine()
        val result = engine.step(
            prevMidPrice = 50_000.0,
            currMidPrice = 50_200.0, // Δp = 200
            prevPosition = 0.5,
            currPosition = 0.8, // Δf = 0.3
            bid = 50_190.0,
            ask = 50_210.0, // δ_t = 10
            feeRate = 0.0006,
            fundingRate = 0.0001,
            barSpanMs = FundingSchedule.INTERVAL_MS,
        )
        val expectedMtm = 200.0 * 0.5 // 100
        val expectedCost = (10.0 + 0.0006 * 50_200.0) * 0.3 // (10 + 30.12) * 0.3 = 12.036
        val expectedFunding = 0.8 * 50_200.0 * 0.0001 // 4.016
        val expectedReward = expectedMtm - expectedCost - expectedFunding

        assertEquals(expectedMtm, result.markToMarketPnl, eps)
        assertEquals(expectedCost, result.transactionCost, eps)
        assertEquals(expectedFunding, result.fundingCost, eps)
        assertEquals(expectedReward, result.reward, eps)
    }

    // ---- differential Sharpe ratio ----

    /**
     * A second, independent implementation of the same a_t/b_t/dsr_t
     * recursion `RewardEngine` uses internally - see the class doc for why
     * this uses `b_{t-1} - a_{t-1}^2` rather than the source paper's
     * eq. 5 literal `a_{t-1} - a_{t-1}^2` (a transcription slip that would
     * make the denominator negative, and thus `dsr_t` `NaN`, for the very
     * common case of a negative average reward).
     */
    private fun handRolledDsr(rewards: List<Double>, tau: Double): List<Double> {
        var a = 0.0
        var b = 0.0
        val out = ArrayList<Double>(rewards.size)
        for (r in rewards) {
            val variance = b - a * a
            val dsr = if (variance <= 1e-12) {
                0.0
            } else {
                val deltaA = r - a
                val deltaB = r * r - b
                (b * deltaA - 0.5 * a * deltaB) / variance.pow(1.5)
            }
            out.add(dsr)
            a += tau * (r - a)
            b += tau * (r * r - b)
        }
        return out
    }

    @Test
    fun `the very first bar has no prior variance estimate, so dsr is conventionally zero`() {
        val engine = RewardEngine(sharpeAdaptationRate = 0.5)
        val result = engine.step(
            prevMidPrice = 100.0,
            currMidPrice = 101.0,
            prevPosition = 1.0,
            currPosition = 1.0,
            bid = 101.0,
            ask = 101.0,
        )
        assertEquals(1.0, result.reward, eps)
        assertEquals(0.0, result.differentialSharpe, eps)
        assertEquals(0.5, engine.firstMoment(), eps) // a_1 = 0 + 0.5*(1.0-0)
        assertEquals(0.5, engine.secondMoment(), eps) // b_1 = 0 + 0.5*(1.0^2-0)
    }

    @Test
    fun `dsr over a short reward sequence matches an independently hand-rolled recursion`() {
        val tau = 0.5
        val engine = RewardEngine(sharpeAdaptationRate = tau)

        // Three bars engineered (zero spread/fee/funding) so r_t is driven
        // purely by Δp_t * f_{t-1}, and thus fully known ahead of time.
        val bars = listOf(
            // prevPrice, currPrice, prevPos, currPos -> r_t
            Quad(100.0, 101.0, 1.0, 1.0), // r1 = 1.0
            Quad(101.0, 100.5, 1.0, 1.0), // r2 = -0.5
            Quad(100.5, 102.5, 1.0, 1.0), // r3 = 2.0
        )
        val rewards = bars.map { (prev, curr, prevPos, _) -> (curr - prev) * prevPos }
        val expectedDsr = handRolledDsr(rewards, tau)

        for ((i, bar) in bars.withIndex()) {
            val result = engine.step(
                prevMidPrice = bar.prevPrice,
                currMidPrice = bar.currPrice,
                prevPosition = bar.prevPos,
                currPosition = bar.currPos,
                bid = bar.currPrice,
                ask = bar.currPrice,
            )
            assertEquals("reward at bar $i", rewards[i], result.reward, eps)
            assertEquals("dsr at bar $i", expectedDsr[i], result.differentialSharpe, eps)
        }
    }

    private data class Quad(val prevPrice: Double, val currPrice: Double, val prevPos: Double, val currPos: Double)

    @Test
    fun `reset returns the engine to a fresh no-history state`() {
        val engine = RewardEngine(sharpeAdaptationRate = 0.5)
        engine.step(prevMidPrice = 100.0, currMidPrice = 105.0, prevPosition = 1.0, currPosition = 1.0, bid = 105.0, ask = 105.0)
        assertTrue(engine.firstMoment() != 0.0)

        engine.reset()
        assertEquals(0.0, engine.firstMoment(), eps)
        assertEquals(0.0, engine.secondMoment(), eps)

        val afterReset = engine.step(prevMidPrice = 100.0, currMidPrice = 101.0, prevPosition = 1.0, currPosition = 1.0, bid = 101.0, ask = 101.0)
        assertEquals(0.0, afterReset.differentialSharpe, eps) // same "no prior variance" convention as a brand-new engine
    }

    // ---- validation ----

    @Test(expected = IllegalArgumentException::class)
    fun `an ask below the bid is rejected`() {
        RewardEngine().step(prevMidPrice = 100.0, currMidPrice = 100.0, prevPosition = 0.0, currPosition = 0.0, bid = 100.0, ask = 99.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative fee rate is rejected`() {
        RewardEngine().step(prevMidPrice = 100.0, currMidPrice = 100.0, prevPosition = 0.0, currPosition = 0.1, bid = 100.0, ask = 100.0, feeRate = -0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative bar span is rejected`() {
        RewardEngine().step(prevMidPrice = 100.0, currMidPrice = 100.0, prevPosition = 0.0, currPosition = 0.0, bid = 100.0, ask = 100.0, barSpanMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a sharpe adaptation rate of 0 is rejected`() {
        RewardEngine(sharpeAdaptationRate = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a sharpe adaptation rate above 1 is rejected`() {
        RewardEngine(sharpeAdaptationRate = 1.1)
    }
}
