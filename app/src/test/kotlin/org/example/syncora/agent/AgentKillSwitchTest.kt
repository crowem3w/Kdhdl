package org.example.syncora.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Prompt 8c's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`,
 * building on 8a's caps and 8b's stop-independence verification - both
 * left untouched here): [AgentKillSwitch.engage] must still halt the live
 * agent and flatten its position within an acceptable bound *even while
 * the UI thread is completely stalled* - see [AgentKillSwitch]'s class
 * doc, "Why this cannot be a plain UI button callback".
 *
 * ### How the stall is simulated
 * A single-thread [ExecutorService] stands in for the app's main/UI
 * thread - the same role `Dispatchers.Main`/a `Looper` plays in the real
 * app. It is deliberately jammed by submitting a task that blocks on a
 * [CountDownLatch] the test controls, occupying that thread completely
 * before [AgentKillSwitch.engage] is ever called - exactly the
 * "unresponsive UI thread" scenario the prompt calls for. [AgentKillSwitch.engage]
 * is then called from the JUnit test thread itself, standing in for a
 * background watchdog reaching for the switch - never routed through the
 * jammed executor.
 */
class AgentKillSwitchTest {

    // Must be >= ReservoirWeights.MIN_N_HIDDEN (50, per Phase 2's design -
    // ReservoirEngine.kt) or ReservoirWeights.randomWeights throws.
    private val nHidden = 50

    /** Same shape as `PositionOrderEmitterTest`'s stub - records calls and tracks the resulting position so a flatten's effect is directly observable. */
    private class StubPaperTradingAccount : PaperOrderSink {
        data class Call(val type: String, val side: PositionSide)

        val calls = mutableListOf<Call>()
        private var position: PaperPosition? = null

        fun seedOpenPosition(side: PositionSide, size: Double) {
            position = PaperPosition(
                symbol = "BTCUSDT",
                side = side,
                total = size,
                available = size,
                entryPrice = 50_000.0,
                markPrice = 50_000.0,
                leverage = 1,
                marginSize = size * 50_000.0,
                unrealizedPnl = 0.0,
            )
        }

        override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) {
            calls.add(Call("open", side))
            position = PaperPosition(
                symbol = "BTCUSDT",
                side = side,
                total = sizeInBaseCoin.toDouble(),
                available = sizeInBaseCoin.toDouble(),
                entryPrice = 50_000.0,
                markPrice = 50_000.0,
                leverage = leverage,
                marginSize = 0.0,
                unrealizedPnl = 0.0,
            )
        }

        override fun closePosition(position: PaperPosition) {
            calls.add(Call("close", position.side))
            this.position = null
        }

        fun snapshot(): PaperPosition? = position
    }

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
            bids = listOf(DepthLevel(close - spread / 2, 2.0), DepthLevel(close - spread, 3.0)),
            asks = listOf(DepthLevel(close + spread / 2, 2.0), DepthLevel(close + spread, 3.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    private fun newSession(orderEmitter: PositionOrderEmitter): AgentLiveSession {
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 7L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, learningRate = 0.01f, seed = 2L)
        val orchestrator = AgentOrchestrator(assembler, reservoir, readout, reward, policy)
        return AgentLiveSession(orchestrator = orchestrator, orderEmitter = orderEmitter)
    }

    private fun liveBarCloseAt(index: Int, klines: List<Kline>): AgentOrchestrator.LiveBarClose =
        AgentOrchestrator.LiveBarClose(
            barIndex = index,
            kline = klines[index],
            klinesSoFar = klines.subList(0, index + 1),
            depth = depthFor(klines[index].close),
        )

    @Test
    fun `engage flattens the position and halts guard within a bound, even while the UI thread is jammed`() {
        val account = StubPaperTradingAccount()
        account.seedOpenPosition(PositionSide.LONG, size = 0.3)
        val orderEmitter = PositionOrderEmitter(
            orderSink = account,
            currentPosition = account::snapshot,
            maxPositionSizeBaseCoin = 1.0,
            maxNotionalUsdt = 1_000_000.0,
            referencePrice = { 50_000.0 },
        )
        val session = newSession(orderEmitter)
        val killSwitch = AgentKillSwitch(
            orderEmitter = orderEmitter,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        // ---- Jam the "UI thread" completely before engage() is ever called ----
        val uiThread: ExecutorService = Executors.newSingleThreadExecutor()
        val releaseUiThread = CountDownLatch(1)
        val uiThreadIsStuck = CountDownLatch(1)
        uiThread.submit {
            uiThreadIsStuck.countDown()
            // Blocks this thread indefinitely until the test explicitly
            // releases it - simulating a hung/unresponsive main thread.
            releaseUiThread.await()
        }
        assertTrue(
            "the fake UI thread should be jammed before engage() is called",
            uiThreadIsStuck.await(2, TimeUnit.SECONDS),
        )

        try {
            // ---- engage() is called from the test thread itself - a stand-in for a background watchdog, never routed through the jammed uiThread ----
            val flattenJob = killSwitch.engage(reason = "simulated watchdog: UI thread unresponsive")

            assertTrue("isEngaged should flip synchronously on the calling thread", killSwitch.isEngaged)

            // The flatten runs on the kill switch's own IO scope, entirely
            // independent of uiThread, which is still fully blocked at this
            // point (releaseUiThread has not been counted down).
            runBlocking {
                withTimeout(2_000) { flattenJob.join() }
            }

            assertEquals(
                "expected exactly one close order flattening the seeded long position",
                listOf(StubPaperTradingAccount.Call("close", PositionSide.LONG)),
                account.calls,
            )
            assertNull("the account should be flat after the flatten", account.snapshot())

            // ---- Now prove the halt itself: a bar-close queued on the still-jammed uiThread must be suppressed by guard() once it finally runs ----
            val klines = fixtureKlines(bars = 1, seed = 99L)
            val guardedDecisionFuture = uiThread.submit<AgentOrchestrator.DecisionLog?> {
                killSwitch.guard(session, liveBarCloseAt(0, klines))
            }

            // uiThread is still stuck on releaseUiThread - the submitted
            // guard() call cannot have run yet, proving it was genuinely
            // queued behind the stall rather than short-circuited some
            // other way.
            assertTrue(
                "the guarded call should still be pending while the UI thread remains jammed",
                !guardedDecisionFuture.isDone,
            )
        } finally {
            releaseUiThread.countDown()
            uiThread.shutdown()
        }

        val uiThreadTaskCompleted = uiThread.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue("the fake UI thread should finish once released", uiThreadTaskCompleted)
    }

    @Test
    fun `guard suppresses processing entirely once engaged - no orchestrator, policy, or order call is reached`() {
        val account = StubPaperTradingAccount()
        val orderEmitter = PositionOrderEmitter(
            orderSink = account,
            currentPosition = account::snapshot,
            maxPositionSizeBaseCoin = 1.0,
            maxNotionalUsdt = 1_000_000.0,
            referencePrice = { 50_000.0 },
        )
        val session = newSession(orderEmitter)
        val killSwitch = AgentKillSwitch(orderEmitter = orderEmitter)
        val klines = fixtureKlines(bars = 3, seed = 5L)

        // One normal bar before engaging, to establish guard() behaves
        // like a pass-through when not engaged.
        val firstDecision = killSwitch.guard(session, liveBarCloseAt(0, klines))
        assertTrue("guard() should pass bars through unchanged before engage()", firstDecision != null)

        val engageJob = killSwitch.engage("test: manual stop")
        runBlocking { withTimeout(2_000) { engageJob.join() } }
        account.calls.clear() // only the flatten mattered above; isolate what follows

        val suppressed = killSwitch.guard(session, liveBarCloseAt(1, klines))
        assertNull("guard() must return null once engaged, without touching the orchestrator", suppressed)
        assertTrue("no order should have been emitted for a suppressed bar", account.calls.isEmpty())

        // Repeated bars after engagement stay suppressed - the halt is terminal, not a one-shot skip.
        val stillSuppressed = killSwitch.guard(session, liveBarCloseAt(2, klines))
        assertNull(stillSuppressed)
        assertTrue(account.calls.isEmpty())
    }
}
