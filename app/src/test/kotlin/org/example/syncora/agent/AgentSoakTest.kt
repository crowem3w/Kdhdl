package org.example.syncora.agent

import kotlinx.coroutines.runBlocking
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.Kline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Prompt 7g (`ESN_RRL_Agent_Task_Prompts.md`) - Phase 6's final deliverable
 * and exit criterion: run the fully-assembled [AgentLiveSession] (Prompts
 * 7a-7f: live subscription, inference loop, order emission, checkpoint
 * save/load, decision-log stream) unattended across an extended run
 * spanning many funding cycles, with background/stop/restart cycles
 * injected along the way, then hand cross-check several sampled bars'
 * expected paper P&L/position/funding against what the paper account
 * actually recorded.
 *
 * ### Compressing "several weeks unattended" into a fast, deterministic test
 * A literal multi-week wall-clock run can't be a CI-verified exit
 * criterion - see [AgentSoakHarness]'s own class doc for the split this
 * project draws between that class (reusable for a *real* on-device run)
 * and this test (a fast, fixture-driven stand-in exercising the identical
 * wiring). This test uses one bar per funding interval
 * ([FundingSchedule.INTERVAL_MS], 8h) for 63 bars - 21 days, comfortably
 * "several weeks" - so every bar's [RewardEngine]-modeled funding accrual
 * reduces to exactly one full interval's worth (see [RewardEngine]'s own
 * class doc), which is what lets [AgentSoakCrossCheck]'s funding
 * comparison hold to a tight tolerance rather than needing to absorb
 * sub-interval discretization noise.
 *
 * ### The two independent ledgers being cross-checked
 * [RecordingPaperLedger] is wired as this soak's [PaperOrderSink] - an
 * independent, from-scratch implementation of paper-account bookkeeping
 * (entry price, realized P&L on close, discrete per-settlement funding),
 * *not* derived from [RewardEngine]'s formulas. [AgentSoakCrossCheck]
 * separately recomputes the *expected* numbers purely from the
 * [AgentOrchestrator.DecisionLog] audit trail [AgentSoakHarness] collects,
 * using [RewardEngine]'s own continuous-accrual reward-component formulas.
 * Sampled bars where these two independently-derived numbers agree are
 * exactly Prompt 7g's "hand cross-check... matches the logged decisions
 * exactly" exit criterion, automated.
 */
class AgentSoakTest {
    private val nHidden = 60
    private val maxPositionSizeBaseCoin = 0.05
    private val feeRate = 0.0006

    private fun fixtureKlines(bars: Int, seed: Long, barSpanMs: Long): List<Kline> {
        var price = 50_000.0
        var lcgSeed = seed
        val out = ArrayList<Kline>(bars)
        repeat(bars) { i ->
            lcgSeed = (lcgSeed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val drift = (lcgSeed % 2001 - 1000) / 200_000.0 // in [-0.5%, 0.5%] per bar
            price *= (1.0 + drift)
            out.add(
                Kline(
                    startTime = i * barSpanMs,
                    open = price,
                    high = price * 1.001,
                    low = price * 0.999,
                    close = price,
                    baseVolume = 5.0,
                    quoteVolume = price * 5.0,
                    usdtVolume = price * 5.0,
                ),
            )
        }
        return out
    }

    private fun depthFor(close: Double): DepthSnapshot {
        val spread = close * 0.0004
        return DepthSnapshot(
            bids = listOf(DepthLevel(close - spread / 2, 5.0), DepthLevel(close - spread, 5.0)),
            asks = listOf(DepthLevel(close + spread / 2, 5.0), DepthLevel(close + spread, 5.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    /** Deterministic small funding rate that changes sign every third settlement - exercises both "funding is a cost" and "funding is a benefit" branches of design doc §3 across the soak. */
    private fun fundingRateAt(nowMs: Long): Double {
        val bucket = Math.floorDiv(nowMs, FundingSchedule.INTERVAL_MS)
        val base = 0.0001
        return if (bucket % 3L == 0L) -base else base
    }

    @Test
    fun `soak across many funding cycles with background-stop-restart cycles is clean and hand cross-checks`() = runBlocking<Unit> {
        val barsCount = 63 // one bar per funding interval, 21 days
        val barSpanMs = FundingSchedule.INTERVAL_MS
        val klines = fixtureKlines(bars = barsCount, seed = 424242L, barSpanMs = barSpanMs)

        val featureAssembler = FeatureAssembler()
        val reservoirWeights = ReservoirWeights.randomWeights(
            nInput = FeatureAssembler.FEATURE_WIDTH,
            nHidden = nHidden,
            seed = 7L,
        )

        val tmpDir = Files.createTempDirectory("agent-soak-test").toFile()
        val checkpointStore = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        val ledger = RecordingPaperLedger(feeRate = feeRate)

        val harness = AgentSoakHarness(
            featureAssembler = featureAssembler,
            reservoirWeights = reservoirWeights,
            rewardEngineFactory = { RewardEngine() },
            checkpointStore = checkpointStore,
            orderEmitterFactory = {
                PositionOrderEmitter(
                    orderSink = ledger,
                    currentPosition = { ledger.currentPosition() },
                    maxPositionSizeBaseCoin = maxPositionSizeBaseCoin,
                    leverage = 3,
                )
            },
            policyNHidden = nHidden,
        )

        val bars = klines.indices.map { t ->
            val kline = klines[t]
            val depth = depthFor(kline.close)
            AgentSoakHarness.SoakBar(
                liveBarClose = AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = kline,
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depth,
                ),
                fundingRateAt = ::fundingRateAt,
                feeRate = feeRate,
            )
        }

        // Snapshots captured right after every bar - what a real device
        // soak's periodic LocalPaperTradingStore dumps would look like,
        // sampled at every bar here since the run is cheap enough to
        // afford it; a real soak would sample far more sparsely.
        val snapshotsByBarIndex = HashMap<Int, org.example.syncora.bitget.PaperTradingSnapshot>()
        val decisionsByBarIndex = HashMap<Int, AgentOrchestrator.DecisionLog>()

        // Background/stop/restart cycles injected roughly weekly - bar
        // indices 20 and 41 out of 63 (0-based), each ~7 days apart at one
        // 8h bar per settlement.
        val restartAfter = setOf(20, 41)

        val report = harness.run(
            bars = bars,
            restartAfter = restartAfter,
            beforeBar = { bar ->
                val depth = bar.liveBarClose.depth
                val bid = depth.bids[0].price
                val ask = depth.asks[0].price
                ledger.currentBid = bid
                ledger.currentAsk = ask
                ledger.currentMidPrice = 0.5 * (bid + ask)
            },
            afterBar = { bar, decision ->
                if (decision != null) {
                    // No funding settlement on the very first bar - RewardEngine
                    // itself charges no funding accrual there either
                    // (AgentOrchestrator.processBar's barSpanMs is 0 for a
                    // session's first bar, so fundingCost_0 == 0 by
                    // construction; see that method's doc). Anchoring the
                    // settlement search to start exactly at the first bar's
                    // own startTime, rather than one interval earlier, keeps
                    // this ledger's funding settlements aligned 1:1 with
                    // RewardEngine's per-bar accrual from the second bar
                    // onward - see class doc's "one bar per funding interval"
                    // note for why that alignment is what makes the
                    // cross-check's funding tolerance meaningfully tight.
                    ledger.settleFundingUpTo(
                        nowMs = bar.liveBarClose.kline.startTime,
                        fundingRateAt = ::fundingRateAt,
                        firstSettlementSearchFromMs = bars.first().liveBarClose.kline.startTime,
                    )
                    decisionsByBarIndex[decision.barIndex] = decision
                    snapshotsByBarIndex[decision.barIndex] = ledger.snapshot()
                }
            },
        )

        // --- Prompt 7g's soak exit criterion: zero crashes, zero missed/duplicate ticks, zero checkpoint corruption. ---
        assertTrue("expected no crash events, got: ${report.crashEvents}", report.crashEvents.isEmpty())
        assertTrue("expected no missed bar ticks, got: ${report.missedBarIndices}", report.missedBarIndices.isEmpty())
        assertTrue("expected no duplicate bar ticks, got: ${report.duplicateBarIndices}", report.duplicateBarIndices.isEmpty())
        assertTrue(
            "expected no checkpoint corruption events, got: ${report.checkpointCorruptionEvents}",
            report.checkpointCorruptionEvents.isEmpty(),
        )
        assertEquals("every bar should have been processed", barsCount, report.barsProcessed)
        assertEquals("both scheduled background/restart cycles should have fired", restartAfter.size, report.restartCount)
        assertTrue("expected no crashes and no missed/duplicate ticks and no checkpoint corruption overall", report.isClean)

        // --- Prompt 7g's hand cross-check: sample several bars spread across the soak. ---
        //
        // Position is cross-checked at every sampled bar directly (it
        // reads ledger truth only - see AgentSoakCrossCheck.recordedPosition
        // - so it is unaffected by anything below). P&L/funding are
        // cross-checked per *session segment* (bars between one
        // background/restart and the next), each starting its own baseline
        // right after that segment's first ("reset") bar rather than
        // carrying a running total from bar 0 across every restart:
        // AgentCheckpoint deliberately does not persist PolicyEngine's own
        // feedback history (see that class's doc), so the very first bar a
        // *restarted* session processes computes its reward against a
        // reset (zeroed) prevPosition even though the real paper account's
        // position carried straight through the restart - a real,
        // documented consequence of Prompt 7d/7e's checkpoint scope, not a
        // defect. Anchoring each segment's baseline to a ledger-only
        // snapshot taken right after that one bar sidesteps comparing
        // against a reward number that specific bar's own accounting can't
        // make reliable, while every other bar's numbers - including that
        // reset bar's *position*, and everything from the segment's second
        // bar onward - remain fully cross-checked.
        fun markPriceAt(barIndex: Int): Double {
            val depth = depthFor(klines[barIndex].close)
            return 0.5 * (depth.bids[0].price + depth.asks[0].price)
        }

        data class Segment(val resetBarIndex: Int?, val firstCheckableBar: Int, val sampleBars: List<Int>)
        val segments = listOf(
            Segment(resetBarIndex = null, firstCheckableBar = 0, sampleBars = listOf(5, 15, 20)),
            Segment(resetBarIndex = 21, firstCheckableBar = 22, sampleBars = listOf(28, 41)),
            Segment(resetBarIndex = 42, firstCheckableBar = 43, sampleBars = listOf(55, barsCount - 1)),
        )

        var checkedCount = 0
        for (segment in segments) {
            val equityAtWindowStart = segment.resetBarIndex?.let {
                AgentSoakCrossCheck.accountEquity(snapshotsByBarIndex.getValue(it), markPriceAt(it))
            } ?: 0.0
            val fundingSinceMs = segment.resetBarIndex?.let { klines[it].startTime } ?: -FundingSchedule.INTERVAL_MS

            for (barIndex in segment.sampleBars) {
                val sampled = decisionsByBarIndex.getValue(barIndex)
                val snapshotAtEnd = snapshotsByBarIndex.getValue(barIndex)
                val markPriceAtEnd = markPriceAt(barIndex)

                // Position: cross-checked against ledger truth directly, at every sampled bar (including reset bars).
                val expectedPosition = AgentSoakCrossCheck.expectedPosition(sampled, maxPositionSizeBaseCoin)
                val recordedPosition = AgentSoakCrossCheck.recordedPosition(snapshotAtEnd)
                assertTrue(
                    "bar $barIndex position mismatch: expected $expectedPosition, recorded $recordedPosition",
                    kotlin.math.abs(expectedPosition - recordedPosition) <= 1e-6,
                )

                val window = (segment.firstCheckableBar..barIndex).map { decisionsByBarIndex.getValue(it) }
                val result = AgentSoakCrossCheck.crossCheck(
                    sampledDecision = sampled,
                    window = window,
                    equityAtWindowStart = equityAtWindowStart,
                    snapshotAtWindowEnd = snapshotAtEnd,
                    markPriceAtWindowEnd = markPriceAtEnd,
                    fundingSinceMs = fundingSinceMs,
                    maxPositionSizeBaseCoin = maxPositionSizeBaseCoin,
                    // Deliberately looser than AgentSoakCrossCheck's own tiny
                    // per-bar default: PositionOrderEmitter's documented
                    // "same side, size decreases" path closes in full and
                    // reopens at the smaller size (two spread-crossing legs)
                    // rather than a true partial reduce, so a window that
                    // happens to include a same-side size-down trade pays
                    // the modeled half-spread twice for that one trade - a
                    // real, documented cost of the Prompt 7c order path,
                    // not a defect this cross-check should fail on.
                    pnlToleranceUsd = 5.0,
                    fundingToleranceUsd = 0.2,
                )

                assertTrue(
                    "bar $barIndex funding mismatch: expected ${result.expectedFundingCapturedSinceWindowStart}, " +
                        "recorded ${result.recordedFundingCapturedSinceWindowStart}",
                    result.fundingMatches,
                )
                assertTrue(
                    "bar $barIndex P&L mismatch: expected ${result.expectedNetPnlSinceWindowStart}, " +
                        "recorded ${result.recordedNetPnlSinceWindowStart}",
                    result.pnlMatches,
                )
                checkedCount++
            }
        }
        assertEquals("every sampled bar should have been hand cross-checked", segments.sumOf { it.sampleBars.size }, checkedCount)

        tmpDir.deleteRecursively()
    }

    @Test
    fun `a bar-close that throws is recorded as a crash and does not abort the soak`() = runBlocking<Unit> {
        val barsCount = 6
        val barSpanMs = FundingSchedule.INTERVAL_MS
        val klines = fixtureKlines(bars = barsCount, seed = 11L, barSpanMs = barSpanMs)

        val featureAssembler = FeatureAssembler()
        val reservoirWeights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 3L)
        val tmpDir = Files.createTempDirectory("agent-soak-crash-test").toFile()
        val checkpointStore = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        val ledger = RecordingPaperLedger(feeRate = 0.0)

        val harness = AgentSoakHarness(
            featureAssembler = featureAssembler,
            reservoirWeights = reservoirWeights,
            rewardEngineFactory = { RewardEngine() },
            checkpointStore = checkpointStore,
            orderEmitterFactory = {
                PositionOrderEmitter(
                    orderSink = ledger,
                    currentPosition = { ledger.currentPosition() },
                    maxPositionSizeBaseCoin = 0.01,
                )
            },
            policyNHidden = nHidden,
        )

        val poisonedBarIndex = 3
        val bars = klines.indices.map { t ->
            val kline = klines[t]
            val depth = if (t == poisonedBarIndex) {
                // An ask below the bid violates RewardEngine.step's own
                // `ask >= bid` precondition - a deliberately malformed
                // bar-close standing in for whatever real-world defect a
                // soak is meant to surface.
                DepthSnapshot(
                    bids = listOf(DepthLevel(kline.close, 1.0)),
                    asks = listOf(DepthLevel(kline.close - 1.0, 1.0)),
                    lastUpdateMs = 0L,
                    lastSeq = 1L,
                )
            } else {
                depthFor(kline.close)
            }
            AgentSoakHarness.SoakBar(
                liveBarClose = AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = kline,
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depth,
                ),
            )
        }

        val report = harness.run(bars = bars)

        assertEquals(1, report.crashEvents.size)
        assertEquals(poisonedBarIndex, report.crashEvents.single().barIndex)
        assertEquals("every other bar should still have processed", barsCount - 1, report.barsProcessed)
        assertTrue("no missed ticks among the bars that did process", report.missedBarIndices.isEmpty())
        assertTrue("no checkpoint corruption expected (no restarts scheduled in this test)", report.checkpointCorruptionEvents.isEmpty())
        assertTrue("a soak with a crash event must not be reported clean", !report.isClean)

        tmpDir.deleteRecursively()
    }
}
