package org.example.syncora.agent

import kotlinx.coroutines.test.runTest
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.KlineBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 7b's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`):
 * feeding the *same* fixture sequence through [AgentOrchestrator.runBacktest]
 * (Phase 5's already-validated offline path) and through
 * [AgentOrchestrator.processLiveBar] (Prompt 7b's new live inference loop)
 * must produce bit-identical reservoir states, `W_out` values (indirectly,
 * via [AgentOrchestrator.DecisionLog.readoutForecast] and the RLS-trained
 * predictions that follow from it), and position outputs, bar by bar - so
 * that live mode is demonstrably not silently running a different
 * computation than what Phase 5 validated.
 *
 * Two independent orchestrators are built from identically-seeded engines
 * (same pattern as `AgentOrchestratorBacktestTest`'s fixtures) - one driven
 * by [AgentOrchestrator.runBacktest], the other driven bar-by-bar via
 * [AgentOrchestrator.processLiveBar] - and every [AgentOrchestrator.DecisionLog]
 * field is compared for exact equality, not approximate/tolerance
 * equality: both paths ultimately call the same private `processBar`, so
 * anything less than exact equality would indicate the live path has
 * drifted from the offline one.
 *
 * Order emission, persistence, and UI remain out of scope (Prompts 7c-7g);
 * this file only exercises the inference chain itself.
 */
class LiveInferenceEquivalenceTest {

    private val nHidden = 60

    private fun fixtureKlines(bars: Int, seed: Long): List<Kline> {
        var price = 50_000.0
        var lcgSeed = seed
        val out = ArrayList<Kline>(bars)
        repeat(bars) { i ->
            lcgSeed = (lcgSeed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val drift = (lcgSeed % 2001 - 1000) / 100_000.0 // in [-0.01, 0.01]
            price *= (1.0 + drift)
            out.add(
                Kline(
                    startTime = i * 60_000L,
                    open = price,
                    high = price * 1.0005,
                    low = price * 0.9995,
                    close = price,
                    baseVolume = 1.0,
                    quoteVolume = price,
                    usdtVolume = price,
                ),
            )
        }
        return out
    }

    private fun depthFor(close: Double, seed: Long): DepthSnapshot {
        val jitter = ((seed % 97) - 48) / 480_000.0
        val spread = close * 0.0004
        return DepthSnapshot(
            bids = listOf(DepthLevel(close - spread / 2, 2.0 + jitter), DepthLevel(close - spread, 3.0)),
            asks = listOf(DepthLevel(close + spread / 2, 2.0 - jitter), DepthLevel(close + spread, 3.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    /** Builds a fresh, identically-seeded orchestrator - the two call sites must construct engines the same way for equivalence to be a meaningful test. */
    private fun newOrchestrator(): AgentOrchestrator {
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 77L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, seed = 9L)
        return AgentOrchestrator(assembler, reservoir, readout, reward, policy)
    }

    private fun assertDecisionLogsEqual(expected: AgentOrchestrator.DecisionLog, actual: AgentOrchestrator.DecisionLog, bar: Int) {
        assertEquals("barIndex mismatch at bar $bar", expected.barIndex, actual.barIndex)
        assertEquals("startTime mismatch at bar $bar", expected.startTime, actual.startTime)
        assertArrayEquals("features mismatch at bar $bar", expected.features, actual.features, 0f)
        assertArrayEquals("reservoirState mismatch at bar $bar", expected.reservoirState, actual.reservoirState, 0f)
        assertEquals("readoutForecast mismatch at bar $bar", expected.readoutForecast, actual.readoutForecast, 0f)
        assertEquals("previousPosition mismatch at bar $bar", expected.previousPosition, actual.previousPosition, 0f)
        assertEquals("position mismatch at bar $bar", expected.position, actual.position, 0f)
        assertEquals("reward mismatch at bar $bar", expected.reward, actual.reward, 0.0)
        assertEquals("markToMarketPnl mismatch at bar $bar", expected.markToMarketPnl, actual.markToMarketPnl, 0.0)
        assertEquals("transactionCost mismatch at bar $bar", expected.transactionCost, actual.transactionCost, 0.0)
        assertEquals("fundingCost mismatch at bar $bar", expected.fundingCost, actual.fundingCost, 0.0)
        assertEquals("differentialSharpe mismatch at bar $bar", expected.differentialSharpe, actual.differentialSharpe, 0.0)
    }

    @Test
    fun `processLiveBar reproduces runBacktest's decisions bit-identically, bar by bar`() {
        val bars = 400
        val klines = fixtureKlines(bars = bars, seed = 424242L)
        val fundingRateAt: (Long) -> Double = { nowMs -> if ((nowMs / 60_000L) % 480L == 0L) 0.0001 else 0.0 }
        val feeRate = 0.0006

        val offline = newOrchestrator()
        val offlineResult = offline.runBacktest(
            klines = klines,
            depthAt = { t, k -> depthFor(k.close, seed = t.toLong()) },
            fundingRateAt = fundingRateAt,
            feeRate = feeRate,
        )

        val live = newOrchestrator()
        val liveState = AgentOrchestrator.LiveInferenceState()
        val liveDecisions = ArrayList<AgentOrchestrator.DecisionLog>(bars)
        for (t in klines.indices) {
            val kline = klines[t]
            val liveBarClose = AgentOrchestrator.LiveBarClose(
                barIndex = t,
                kline = kline,
                klinesSoFar = klines.subList(0, t + 1),
                depth = depthFor(kline.close, seed = t.toLong()),
            )
            liveDecisions.add(
                live.processLiveBar(
                    liveBarClose = liveBarClose,
                    state = liveState,
                    fundingRateAt = fundingRateAt,
                    feeRate = feeRate,
                ),
            )
        }

        assertEquals(offlineResult.decisions.size, liveDecisions.size)
        for (t in klines.indices) {
            assertDecisionLogsEqual(offlineResult.decisions[t], liveDecisions[t], t)
        }
    }

    @Test
    fun `processLiveBar reproduces runBacktest even when fed through the real LiveBarCloseSubscriber`() = runTest {
        // Same equivalence claim, but exercised through Prompt 7a's actual
        // subscriber/KlineBuffer path (same simulation style as
        // LiveBarCloseSubscriberTest) rather than hand-built LiveBarClose
        // events, so the full Prompt 7a -> 7b handoff is covered too.
        val bars = 200
        val klines = fixtureKlines(bars = bars, seed = 13579L)
        val fundingRateAt: (Long) -> Double = { 0.0 }
        val fixedDepth = depthFor(50_000.0, seed = 1L)

        val offline = newOrchestrator()
        val offlineResult = offline.runBacktest(
            klines = klines,
            depthAt = { _, _ -> fixedDepth },
            fundingRateAt = fundingRateAt,
        )

        // Live path only ever sees bars[0 until bars-1] close (the last bar
        // stays "still forming" in the simulated buffer) - so compare
        // against that same prefix of the offline run.
        val buffer = KlineBuffer(capacity = bars + 1)
        val live = newOrchestrator()
        val liveState = AgentOrchestrator.LiveInferenceState()
        val subscriber = AgentOrchestrator.LiveBarCloseSubscriber()
        val liveDecisions = ArrayList<AgentOrchestrator.DecisionLog>(bars)

        for (bar in klines) {
            val snapshot = buffer.applyUpdates(listOf(bar))
            subscriber.onSnapshot(
                snapshot = snapshot,
                depthAt = { fixedDepth },
                onBarClose = { close ->
                    liveDecisions.add(
                        live.processLiveBar(
                            liveBarClose = close,
                            state = liveState,
                            fundingRateAt = fundingRateAt,
                        ),
                    )
                },
            )
        }

        val expectedCount = bars - 1 // last bar never closes in this simulated stream
        assertEquals(expectedCount, liveDecisions.size)
        for (t in 0 until expectedCount) {
            assertDecisionLogsEqual(offlineResult.decisions[t], liveDecisions[t], t)
        }
        assertTrue("expected at least one decision to be compared", expectedCount > 0)
    }
}
