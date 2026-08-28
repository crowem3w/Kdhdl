package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardEngineTest {

    private val epsilon = 1e-9

    // ---- reward: all four terms, hand-computed --------------------------
    //
    // prevMidPrice=100, midPrice=102.5           -> deltaP = 2.5
    // prevPosition=0.4, position=1.0              -> deltaF = 0.6
    // halfSpread=0.10
    // feeRate=0.0005
    // fundingRate=0.0002, barDurationMs=1h, fundingIntervalMs=8h -> intervalFraction=0.125
    //
    // markToMarket = 2.5 * 0.4                    = 1.0
    // spreadCost   = 0.10 * 0.6                    = 0.06
    // feeCost      = 0.0005 * 102.5 * 0.6          = 0.03075
    // kappa        = 0.0002 * 0.125                = 0.000025
    // fundingCost  = 0.000025 * 1.0                = 0.000025
    // reward       = 1.0 - 0.06 - 0.03075 - 0.000025 = 0.909225
    @Test
    fun `reward matches hand-computed value with all four terms active`() {
        val engine = RewardEngine()
        val inputs = RewardInputs(
            prevMidPrice = 100.0,
            midPrice = 102.5,
            halfSpread = 0.10,
            prevPosition = 0.4,
            position = 1.0,
            feeRate = 0.0005,
            fundingRate = 0.0002,
            barDurationMs = 3_600_000L, // 1h
            fundingIntervalMs = 28_800_000L, // 8h
        )

        val result = engine.computeReward(inputs)

        assertEquals(1.0, result.markToMarket, epsilon)
        assertEquals(0.06, result.spreadCost, epsilon)
        assertEquals(0.03075, result.feeCost, epsilon)
        assertEquals(0.000025, result.fundingCost, epsilon)
        assertEquals(0.909225, result.reward, epsilon)
    }

    // ---- fee-only case: isolates the transaction-cost term --------------
    //
    // No price move (deltaP=0 -> markToMarket=0), no spread cost
    // (halfSpread=0), no funding (fundingRate=0). Only the fee term survives:
    // feeCost = feeRate * midPrice * |deltaF| = 0.0006 * 100 * 0.5 = 0.03
    // reward  = -0.03
    @Test
    fun `fee-only case isolates the transaction-cost term with no funding involved`() {
        val engine = RewardEngine()
        val inputs = RewardInputs(
            prevMidPrice = 100.0,
            midPrice = 100.0,
            halfSpread = 0.0,
            prevPosition = 0.0,
            position = 0.5,
            feeRate = 0.0006,
            fundingRate = 0.0,
            barDurationMs = 60_000L,
            fundingIntervalMs = 28_800_000L,
        )

        val result = engine.computeReward(inputs)

        assertEquals(0.0, result.markToMarket, epsilon)
        assertEquals(0.0, result.spreadCost, epsilon)
        assertEquals(0.0, result.fundingCost, epsilon)
        assertEquals(0.03, result.feeCost, epsilon)
        assertEquals(-0.03, result.reward, epsilon)
    }

    // ---- funding-crossing case: position held across a full funding
    // interval, split across several bars --------------------------------
    //
    // A constant position f=0.4 held across 4 bars of 2h each (=8h, one full
    // funding interval), fundingRate=0.0003 held constant for that interval
    // (it only changes at settlement, per FundingSchedule/contract §3). No
    // price move and zero fee/spread rates so only the funding term survives.
    //
    // Per bar: kappa = 0.0003 * (7_200_000 / 28_800_000) = 0.0003 * 0.25 = 0.000075
    //          fundingCost = kappa * 0.4 = 0.00003, reward = -0.00003
    // Summed over the 4 bars: fundingCost sums to 0.00012, matching the
    // fractional (per-unit-of-max-notional) form of
    // PaperTradingRepository.settleFunding's amount = notional * rate *
    // direction, i.e. fundingRate * f_t = 0.0003 * 0.4 = 0.00012 - contract
    // §3's "no drift" requirement, checked directly.
    @Test
    fun `funding accrual across a position crossing a full funding interval sums to the settlement amount`() {
        val engine = RewardEngine()
        val position = 0.4
        val fundingRate = 0.0003
        val barDurationMs = 7_200_000L // 2h
        val fundingIntervalMs = 28_800_000L // 8h
        val bars = 4 // 4 * 2h = 8h = one full funding interval

        var prevPosition = 0.0
        var totalFundingCost = 0.0
        var totalReward = 0.0
        for (i in 0 until bars) {
            val inputs = RewardInputs(
                prevMidPrice = 100.0,
                midPrice = 100.0,
                halfSpread = 0.0,
                prevPosition = prevPosition,
                position = position,
                feeRate = 0.0,
                fundingRate = fundingRate,
                barDurationMs = barDurationMs,
                fundingIntervalMs = fundingIntervalMs,
            )
            val result = engine.computeReward(inputs)

            // Isolated term: with feeRate=0 and halfSpread=0, only funding
            // (and, on the very first bar only, a zero-cost mark-to-market
            // since prevMidPrice==midPrice) contributes.
            assertEquals(0.000075 * position, result.fundingCost, epsilon)
            assertEquals(0.0, result.markToMarket, epsilon)
            assertEquals(0.0, result.spreadCost, epsilon)
            assertEquals(0.0, result.feeCost, epsilon)

            totalFundingCost += result.fundingCost
            totalReward += result.reward
            prevPosition = position
        }

        val expectedSettlementFraction = fundingRate * position // 0.00012
        assertEquals(expectedSettlementFraction, totalFundingCost, epsilon)
        assertEquals(-expectedSettlementFraction, totalReward, epsilon)
    }

    // ---- input validation -------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `zero fundingIntervalMs is rejected`() {
        RewardEngine().computeReward(
            RewardInputs(
                prevMidPrice = 100.0,
                midPrice = 100.0,
                halfSpread = 0.0,
                prevPosition = 0.0,
                position = 0.0,
                feeRate = 0.0,
                fundingRate = 0.0,
                barDurationMs = 1000L,
                fundingIntervalMs = 0L,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative barDurationMs is rejected`() {
        RewardEngine().computeReward(
            RewardInputs(
                prevMidPrice = 100.0,
                midPrice = 100.0,
                halfSpread = 0.0,
                prevPosition = 0.0,
                position = 0.0,
                feeRate = 0.0,
                fundingRate = 0.0,
                barDurationMs = -1L,
                fundingIntervalMs = 28_800_000L,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative feeRate is rejected`() {
        RewardEngine().computeReward(
            RewardInputs(
                prevMidPrice = 100.0,
                midPrice = 100.0,
                halfSpread = 0.0,
                prevPosition = 0.0,
                position = 0.0,
                feeRate = -0.001,
                fundingRate = 0.0,
                barDurationMs = 1000L,
                fundingIntervalMs = 28_800_000L,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adaptationRate above 1 is rejected`() {
        RewardEngine(adaptationRate = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adaptationRate of 0 is rejected`() {
        RewardEngine(adaptationRate = 0.0)
    }

    // ---- differential Sharpe ratio: hand-computed sequence ---------------
    //
    // eta = 0.1, rewards r = [0.1, 0.2, -0.1]
    //
    // Step 0 (r=0.1): no prior moments -> dsr=0.0, seed a0=0.1, b0=0.01
    // Step 1 (r=0.2): aPrev=0.1, bPrev=0.01
    //   deltaA=0.1, deltaB=0.03
    //   variance = bPrev - aPrev^2 = 0.01 - 0.01 = 0.0 -> dsr=0.0 (degenerate,
    //   only one prior sample so variance is exactly zero)
    //   a1 = 0.1 + 0.1*0.1 = 0.11, b1 = 0.01 + 0.1*0.03 = 0.013
    // Step 2 (r=-0.1): aPrev=0.11, bPrev=0.013
    //   deltaA = -0.1 - 0.11 = -0.21
    //   deltaB = 0.01 - 0.013 = -0.003
    //   variance = 0.013 - 0.0121 = 0.0009, variance^1.5 = 0.0009*0.03 = 0.000027
    //   numerator = 0.013*(-0.21) - 0.5*0.11*(-0.003) = -0.00273 + 0.000165 = -0.002565
    //   dsr2 = -0.002565 / 0.000027 = -95.0
    @Test
    fun `differential Sharpe ratio matches hand-computed sequence`() {
        val engine = RewardEngine(adaptationRate = 0.1)

        val dsr0 = engine.updateMoments(0.1)
        assertEquals(0.0, dsr0, epsilon)
        assertEquals(0.1, engine.meanMoment(), epsilon)
        assertEquals(0.01, engine.secondMoment(), epsilon)

        val dsr1 = engine.updateMoments(0.2)
        assertEquals(0.0, dsr1, epsilon)
        assertEquals(0.11, engine.meanMoment(), epsilon)
        assertEquals(0.013, engine.secondMoment(), epsilon)

        val dsr2 = engine.updateMoments(-0.1)
        assertEquals(-95.0, dsr2, 1e-6)
    }

    @Test
    fun `dsr is zero for the very first reward observed regardless of engine state`() {
        val engine = RewardEngine()
        assertTrue(!engine.hasObservedReward())
        val dsr = engine.updateMoments(-3.7)
        assertEquals(0.0, dsr, epsilon)
        assertTrue(engine.hasObservedReward())
    }

    @Test
    fun `step combines computeReward and updateMoments consistently`() {
        val engine = RewardEngine(adaptationRate = 0.1)
        val inputs = RewardInputs(
            prevMidPrice = 100.0,
            midPrice = 100.0,
            halfSpread = 0.0,
            prevPosition = 0.0,
            position = 0.0,
            feeRate = 0.0,
            fundingRate = 0.0,
            barDurationMs = 1000L,
            fundingIntervalMs = 28_800_000L,
        )
        // With every rate/position term zero, reward is exactly 0.0 - drive
        // the dsr sequence with a couple of manually-supplied rewards
        // afterward instead, via the two-call path, and confirm step()
        // agrees with calling computeReward()+updateMoments() directly.
        val viaStep = engine.step(inputs)

        val engine2 = RewardEngine(adaptationRate = 0.1)
        val components2 = engine2.computeReward(inputs)
        val dsr2 = engine2.updateMoments(components2.reward)

        assertEquals(components2.reward, viaStep.components.reward, epsilon)
        assertEquals(dsr2, viaStep.dsr, epsilon)
        assertEquals(0.0, viaStep.components.reward, epsilon)
    }
}
