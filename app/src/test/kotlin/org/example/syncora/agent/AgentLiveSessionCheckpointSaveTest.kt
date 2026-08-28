package org.example.syncora.agent

import kotlinx.coroutines.runBlocking
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Prompt 7d's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`):
 * driving [AgentLiveSession] through several live-style bars, then forcing
 * a simulated background/stop lifecycle event via [AgentLiveSession.stop],
 * must write a checkpoint file containing all four required components -
 * reservoir state, `W_out`, RLS covariance, and policy weights - with
 * values matching in-memory state at the moment of the stop signal, field
 * for field.
 */
class AgentLiveSessionCheckpointSaveTest {

    private val nHidden = 60

    /** A [PaperOrderSink] that does nothing - this test exercises checkpoint save, not order emission (already covered by `PositionOrderEmitterTest`, Prompt 7c). */
    private class NoopOrderSink : PaperOrderSink {
        override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) = Unit
        override fun closePosition(position: PaperPosition) = Unit
    }

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

    private fun depthFor(close: Double): DepthSnapshot {
        val spread = close * 0.0004
        return DepthSnapshot(
            bids = listOf(DepthLevel(close - spread / 2, 2.0), DepthLevel(close - spread, 3.0)),
            asks = listOf(DepthLevel(close + spread / 2, 2.0), DepthLevel(close + spread, 3.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    private fun newOrchestrator(): AgentOrchestrator {
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 55L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, learningRate = 0.005f, seed = 3L)
        return AgentOrchestrator(assembler, reservoir, readout, reward, policy)
    }

    private fun newSession(orchestrator: AgentOrchestrator, store: AgentCheckpointStore): AgentLiveSession {
        val orderEmitter = PositionOrderEmitter(
            orderSink = NoopOrderSink(),
            currentPosition = { null },
            maxPositionSizeBaseCoin = 1.0,
        )
        return AgentLiveSession(orchestrator = orchestrator, orderEmitter = orderEmitter, checkpointStore = store)
    }

    @Test
    fun `stop saves a checkpoint with all four components matching in-memory state at the moment of the stop signal`() = runBlocking<Unit> {
        val bars = 50
        val klines = fixtureKlines(bars = bars, seed = 909090L)
        val fundingRateAt: (Long) -> Double = { 0.0 }

        val orchestrator = newOrchestrator()
        val tmpDir = Files.createTempDirectory("agent-checkpoint-save-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        val session = newSession(orchestrator, store)

        for (t in klines.indices) {
            val kline = klines[t]
            session.processLiveBar(
                liveBarClose = AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = kline,
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depthFor(kline.close),
                ),
                fundingRateAt = fundingRateAt,
            )
        }

        // What the in-memory state actually is right as the stop signal
        // fires - the test's own independent read of "ground truth",
        // captured the same way AgentLiveSession.stop() captures it
        // internally (AgentOrchestrator.currentCheckpoint), so nothing in
        // between the two calls has a chance to mutate orchestrator state.
        val expected = orchestrator.currentCheckpoint()

        // Simulated background/stop lifecycle event.
        session.stop().join()

        val loaded = store.load()
        assertNotNull("checkpoint file must exist after stop()", loaded)
        loaded as AgentCheckpoint

        assertArrayEquals("reservoir state mismatch", expected.reservoirState, loaded.reservoirState, 0f)
        assertEquals("W_out mismatch", expected.readout.wOut.toList(), loaded.readout.wOut.toList())
        assertEquals("RLS covariance mismatch", expected.readout.covariance.toList(), loaded.readout.covariance.toList())
        assertArrayEquals("policy weights mismatch", expected.policyWeights, loaded.policyWeights, 0f)
        assertEquals("policy nHidden mismatch", expected.policyNHidden, loaded.policyNHidden)
        assertEquals("policy nBack mismatch", expected.policyNBack, loaded.policyNBack)
        assertEquals(expected, loaded)

        tmpDir.deleteRecursively()
    }

    @Test
    fun `stop is safe to call before any bar has been processed`() = runBlocking<Unit> {
        val orchestrator = newOrchestrator()
        val tmpDir = Files.createTempDirectory("agent-checkpoint-save-empty-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        val session = newSession(orchestrator, store)

        session.stop().join()

        val loaded = store.load()
        assertNotNull(loaded)
        loaded as AgentCheckpoint
        assertEquals(nHidden, loaded.reservoirState.size)
        assertTrue("a fresh reservoir's state should still be the zero vector", loaded.reservoirState.all { it == 0f })

        tmpDir.deleteRecursively()
    }

    @Test
    fun `a second stop call re-saves whatever the state is at that later moment`() = runBlocking<Unit> {
        val klines = fixtureKlines(bars = 10, seed = 55555L)
        val orchestrator = newOrchestrator()
        val tmpDir = Files.createTempDirectory("agent-checkpoint-save-twice-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        val session = newSession(orchestrator, store)

        session.processLiveBar(
            liveBarClose = AgentOrchestrator.LiveBarClose(
                barIndex = 0,
                kline = klines[0],
                klinesSoFar = klines.subList(0, 1),
                depth = depthFor(klines[0].close),
            ),
        )
        session.stop().join()
        val firstSave = store.load()

        for (t in 1 until klines.size) {
            session.processLiveBar(
                liveBarClose = AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = klines[t],
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depthFor(klines[t].close),
                ),
            )
        }
        session.stop().join()
        val secondSave = store.load()

        assertEquals(orchestrator.currentCheckpoint(), secondSave)
        assertTrue(
            "second save should reflect the additional bars processed since the first save",
            firstSave != secondSave,
        )

        tmpDir.deleteRecursively()
    }
}
