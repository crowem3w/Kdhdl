package org.example.syncora.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Prompt 7f's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`): with
 * full UI (View) testing impractical in this repo (no Robolectric - see
 * `build.gradle.kts`'s `testOptions` comment and
 * [org.example.syncora.ui.AgentStatusLogPanel]'s class doc), this test
 * exercises the decision-log *stream* the panel consumes -
 * [AgentLiveSession.decisionLog] - directly: driving several live-style
 * bars through the orchestrator must deliver exactly one
 * correctly-populated [AgentDecisionLogEntry] per bar, in order, with no
 * dropped or duplicated entries.
 *
 * The collector below uses [Dispatchers.Unconfined] deliberately (not the
 * default coroutine test dispatcher) so that [AgentLiveSession.decisionLog]'s
 * `tryEmit` calls - made synchronously from [AgentLiveSession.processLiveBar],
 * not from a suspend function - are received by the collector essentially
 * immediately, the same way a real [org.example.syncora.ui.AgentStatusLogPanel]
 * actively collecting on the main thread would see them: subscribed
 * *before* any bar is processed, exactly mirroring how a status panel is
 * expected to already be on screen and collecting before the live session
 * starts producing bars.
 */
class AgentLiveSessionDecisionLogTest {

    // Must fall within ReservoirEngine.MIN_N_HIDDEN..MAX_N_HIDDEN (50..150,
    // per Phase 2's design) - ReservoirWeights.randomWeights rejects
    // anything outside that range.
    private val nHidden = 50

    private fun fixtureKlines(bars: Int, seed: Long): List<Kline> {
        var price = 50_000.0
        var lcgSeed = seed
        val out = ArrayList<Kline>(bars)
        repeat(bars) { i ->
            lcgSeed = (lcgSeed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val drift = (lcgSeed % 2001 - 1000) / 100_000.0
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

    private fun depthFor(close: Double): DepthSnapshot {
        val spread = close * 0.0004
        return DepthSnapshot(
            bids = listOf(DepthLevel(close - spread / 2, 2.0)),
            asks = listOf(DepthLevel(close + spread / 2, 2.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    /** No-op order sink: this test is only about the decision-log stream, not order emission (Prompt 7c, already covered elsewhere). */
    private class NoopOrderSink : PaperOrderSink {
        override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) = Unit
        override fun closePosition(position: PaperPosition) = Unit
    }

    private fun newSession(): AgentLiveSession {
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 5L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, learningRate = 0.005f, seed = 3L)
        val orchestrator = AgentOrchestrator(assembler, reservoir, readout, reward, policy)
        val orderEmitter = PositionOrderEmitter(
            orderSink = NoopOrderSink(),
            currentPosition = { null },
            maxPositionSizeBaseCoin = 1.0,
        )
        return AgentLiveSession(orchestrator = orchestrator, orderEmitter = orderEmitter)
    }

    @Test
    fun `decisionLog delivers exactly one correctly-populated entry per bar, in order, with no drops or duplicates`() {
        val bars = 50
        val klines = fixtureKlines(bars = bars, seed = 99L)
        val session = newSession()

        val received = Collections.synchronizedList(ArrayList<AgentDecisionLogEntry>())
        val collectorScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val collectorJob: Job = collectorScope.launch {
            session.decisionLog.collect { entry -> received.add(entry) }
        }

        val expectedDecisions = ArrayList<AgentOrchestrator.DecisionLog>(bars)
        for (t in klines.indices) {
            val kline = klines[t]
            val liveBarClose = AgentOrchestrator.LiveBarClose(
                barIndex = t,
                kline = kline,
                klinesSoFar = klines.subList(0, t + 1),
                depth = depthFor(kline.close),
            )
            expectedDecisions.add(session.processLiveBar(liveBarClose))
        }

        collectorJob.cancel()
        collectorScope.cancel()

        // No drops, no duplicates: exactly `bars` entries received.
        assertEquals("expected exactly one entry per bar", bars, received.size)

        // In order, bar-index-sequential, and each matching the DecisionLog
        // AgentLiveSession.processLiveBar actually returned for that bar -
        // i.e. every entry is "correctly populated", not just present.
        for (t in klines.indices) {
            val expected = AgentDecisionLogEntry.fromDecisionLog(expectedDecisions[t])
            val actual = received[t]
            assertEquals("barIndex mismatch at bar $t", expected.barIndex, actual.barIndex)
            assertEquals("timestampMs mismatch at bar $t", expected.timestampMs, actual.timestampMs)
            assertEquals("featuresSummary mismatch at bar $t", expected.featuresSummary, actual.featuresSummary)
            assertEquals("previousPosition mismatch at bar $t", expected.previousPosition, actual.previousPosition, 0f)
            assertEquals("position mismatch at bar $t", expected.position, actual.position, 0f)
            assertEquals("reward mismatch at bar $t", expected.reward, actual.reward, 0.0)
            assertEquals("differentialSharpe mismatch at bar $t", expected.differentialSharpe, actual.differentialSharpe, 0.0)
        }

        // barIndex strictly increasing by exactly 1 - the "no duplicates,
        // no gaps" property restated in terms of what a panel would render.
        for (t in 1 until received.size) {
            assertEquals(received[t - 1].barIndex + 1, received[t].barIndex)
        }
        assertTrue("expected at least one bar to have been processed", received.isNotEmpty())
    }

    @Test
    fun `a panel that subscribes late sees no entries from before it subscribed, never a stale replay`() {
        // Documents decisionLog's replay = 0 contract (see AgentLiveSession's
        // class doc): a panel dropped onto the screen mid-session should
        // start empty and only grow from that point forward, never suddenly
        // backfill with everything that already happened.
        val bars = 10
        val klines = fixtureKlines(bars = bars, seed = 7L)
        val session = newSession()

        for (t in 0 until 5) {
            val kline = klines[t]
            session.processLiveBar(
                AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = kline,
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depthFor(kline.close),
                ),
            )
        }

        val received = Collections.synchronizedList(ArrayList<AgentDecisionLogEntry>())
        val collectorScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val collectorJob = collectorScope.launch {
            session.decisionLog.collect { entry -> received.add(entry) }
        }

        for (t in 5 until bars) {
            val kline = klines[t]
            session.processLiveBar(
                AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = kline,
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depthFor(kline.close),
                ),
            )
        }

        collectorJob.cancel()
        collectorScope.cancel()

        assertEquals(bars - 5, received.size)
        assertEquals(5, received.first().barIndex)
        assertEquals(bars - 1, received.last().barIndex)
    }
}
