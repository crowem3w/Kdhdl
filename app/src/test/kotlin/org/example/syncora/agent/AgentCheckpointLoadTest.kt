package org.example.syncora.agent

import kotlinx.coroutines.runBlocking
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Prompt 7e's two required exit-criterion tests (`ESN_RRL_Agent_Task_Prompts.md`):
 * a save-then-restore round trip that must reproduce bit-identical state in
 * a brand-new [AgentOrchestrator], and a missing/corrupt-checkpoint fallback
 * that must initialize cleanly rather than crash. Both exercise the real
 * [FileAgentCheckpointStore] (Prompt 7d) end to end, not just the in-memory
 * [AgentCheckpoint.toOrchestrator] restore logic, so the JSON round trip is
 * covered too.
 */
class AgentCheckpointLoadTest {

    // ReservoirWeights.randomWeights requires nHidden in [50, 150] (Phase 2's MIN_N_HIDDEN/MAX_N_HIDDEN).
    private val nHidden = 55

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

    /** Fresh, independent (but shape-matched) [FeatureAssembler]/[ReservoirWeights]/[RewardEngine] - a stand-in for "this run's configuration", built once per test so save-side and load-side agree on shape without sharing any mutable object. */
    private fun freshReservoirWeights(seed: Long = 77L) =
        ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = seed)

    private fun freshOrchestrator(reservoirWeights: ReservoirWeights): AgentOrchestrator {
        val assembler = FeatureAssembler()
        val reservoir = ReservoirEngine(reservoirWeights)
        val readout = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, learningRate = 0.005f, seed = 3L)
        return AgentOrchestrator(assembler, reservoir, readout, reward, policy)
    }

    private fun driveBars(orchestrator: AgentOrchestrator, klines: List<Kline>): AgentOrchestrator.LiveInferenceState {
        val state = AgentOrchestrator.LiveInferenceState()
        for (t in klines.indices) {
            orchestrator.processLiveBar(
                liveBarClose = AgentOrchestrator.LiveBarClose(
                    barIndex = t,
                    kline = klines[t],
                    klinesSoFar = klines.subList(0, t + 1),
                    depth = depthFor(klines[t].close),
                ),
                state = state,
                fundingRateAt = { 0.0 },
            )
        }
        return state
    }

    // ---- 1. Round-trip fidelity: save from a live-driven orchestrator, restore into a brand-new one ----

    @Test
    fun `restoring a saved checkpoint into a brand-new orchestrator reproduces bit-identical state`() = runBlocking<Unit> {
        val reservoirWeights = freshReservoirWeights()
        val klines = fixtureKlines(bars = 40, seed = 424242L)

        val original = freshOrchestrator(reservoirWeights)
        driveBars(original, klines)
        val savedCheckpoint = original.currentCheckpoint()

        val tmpDir = Files.createTempDirectory("agent-checkpoint-load-roundtrip-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        store.save(savedCheckpoint)

        val loadedCheckpoint = store.load()
        assertEquals("saved and reloaded checkpoint JSON must round-trip bit-identically", savedCheckpoint, loadedCheckpoint)
        loadedCheckpoint as AgentCheckpoint

        val restored = loadedCheckpoint.toOrchestrator(
            featureAssembler = FeatureAssembler(),
            reservoirWeights = reservoirWeights,
            rewardEngine = RewardEngine(),
        )

        // The core exit criterion: a brand-new orchestrator's state, right
        // after restore and before processing any new bar, must match the
        // original's state at save time - field for field.
        val restoredCheckpoint = restored.currentCheckpoint()
        assertEquals(
            "reservoir state mismatch",
            savedCheckpoint.reservoirState.toList(),
            restoredCheckpoint.reservoirState.toList(),
        )
        assertEquals(
            "W_out mismatch",
            savedCheckpoint.readout.wOut.toList(),
            restoredCheckpoint.readout.wOut.toList(),
        )
        assertEquals(
            "RLS covariance mismatch",
            savedCheckpoint.readout.covariance.toList(),
            restoredCheckpoint.readout.covariance.toList(),
        )
        assertEquals(
            "policy weights mismatch",
            savedCheckpoint.policyWeights.toList(),
            restoredCheckpoint.policyWeights.toList(),
        )
        assertEquals(savedCheckpoint, restoredCheckpoint)

        // Bonus confidence beyond the literal exit criterion: a restored
        // orchestrator isn't just a state snapshot that *looks* right, it
        // must actually *behave* identically to the original had it kept
        // running - same forecast, same position, on the very next bar.
        val nextBar = fixtureKlines(bars = 1, seed = 909090L).first().let { it.copy(startTime = klines.size * 60_000L) }
        val originalNextDecision = original.processLiveBar(
            liveBarClose = AgentOrchestrator.LiveBarClose(
                barIndex = klines.size,
                kline = nextBar,
                klinesSoFar = klines + nextBar,
                depth = depthFor(nextBar.close),
            ),
            state = AgentOrchestrator.LiveInferenceState(),
            fundingRateAt = { 0.0 },
        )
        val restoredNextDecision = restored.processLiveBar(
            liveBarClose = AgentOrchestrator.LiveBarClose(
                barIndex = klines.size,
                kline = nextBar,
                klinesSoFar = klines + nextBar,
                depth = depthFor(nextBar.close),
            ),
            state = AgentOrchestrator.LiveInferenceState(),
            fundingRateAt = { 0.0 },
        )
        assertEquals(originalNextDecision.readoutForecast, restoredNextDecision.readoutForecast, 0f)
        assertEquals(originalNextDecision.position, restoredNextDecision.position, 0f)

        tmpDir.deleteRecursively()
    }

    // ---- 2. Fallback: missing or corrupt checkpoint must not crash ----

    @Test
    fun `restoring from a store with no prior checkpoint falls back to a fresh orchestrator`() = runBlocking<Unit> {
        val reservoirWeights = freshReservoirWeights()
        val tmpDir = Files.createTempDirectory("agent-checkpoint-load-missing-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "does-not-exist.json"))

        val orchestrator = store.restoreOrFreshOrchestrator(
            featureAssembler = FeatureAssembler(),
            reservoirWeights = reservoirWeights,
            rewardEngine = RewardEngine(),
        )

        val checkpoint = orchestrator.currentCheckpoint()
        assertEquals(nHidden, checkpoint.reservoirState.size)
        assertTrue(
            "a fresh reservoir's state should still be the zero vector",
            checkpoint.reservoirState.all { it == 0f },
        )

        tmpDir.deleteRecursively()
    }

    @Test
    fun `restoring from a corrupt checkpoint file falls back to a fresh orchestrator instead of throwing`() = runBlocking<Unit> {
        val reservoirWeights = freshReservoirWeights()
        val tmpDir = Files.createTempDirectory("agent-checkpoint-load-corrupt-test").toFile()
        val file = File(tmpDir, "checkpoint.json")
        file.writeText("{ not valid json ")
        val store = FileAgentCheckpointStore(file)

        val orchestrator = store.restoreOrFreshOrchestrator(
            featureAssembler = FeatureAssembler(),
            reservoirWeights = reservoirWeights,
            rewardEngine = RewardEngine(),
        )

        val checkpoint = orchestrator.currentCheckpoint()
        assertEquals(nHidden, checkpoint.reservoirState.size)
        assertTrue(
            "a fresh reservoir's state should still be the zero vector",
            checkpoint.reservoirState.all { it == 0f },
        )

        tmpDir.deleteRecursively()
    }

    @Test
    fun `restoring a checkpoint whose shape no longer matches this run's configuration falls back to a fresh orchestrator`() = runBlocking<Unit> {
        val savedWithWeights = freshReservoirWeights(seed = 1L)
        val original = freshOrchestrator(savedWithWeights)
        driveBars(original, fixtureKlines(bars = 5, seed = 55L))

        val tmpDir = Files.createTempDirectory("agent-checkpoint-load-shape-mismatch-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        store.save(original.currentCheckpoint())

        // A differently-sized reservoir, as if the app's configuration
        // changed since the checkpoint was written - toOrchestrator() would
        // throw for this; restoreOrFreshOrchestrator must not propagate it.
        val differentlyShapedWeights = ReservoirWeights.randomWeights(
            nInput = FeatureAssembler.FEATURE_WIDTH,
            nHidden = nHidden + 5,
            seed = 1L,
        )

        val orchestrator = store.restoreOrFreshOrchestrator(
            featureAssembler = FeatureAssembler(),
            reservoirWeights = differentlyShapedWeights,
            rewardEngine = RewardEngine(),
        )

        val checkpoint = orchestrator.currentCheckpoint()
        assertEquals(
            "a fresh fallback orchestrator must be built against the *caller's* current reservoir shape, not the stale checkpoint's",
            nHidden + 5,
            checkpoint.reservoirState.size,
        )
        assertTrue(
            "a fresh reservoir's state should still be the zero vector",
            checkpoint.reservoirState.all { it == 0f },
        )

        tmpDir.deleteRecursively()
    }

    // ---- 3. Wiring: AgentLiveSession.start() must actually use the restored orchestrator ----

    /** A [PaperOrderSink] that does nothing - this test is about which state a session's first decision reflects, not order emission (already covered by `PositionOrderEmitterTest`). */
    private class NoopOrderSink : PaperOrderSink {
        override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) = Unit
        override fun closePosition(position: PaperPosition) = Unit
    }

    private fun newOrderEmitter() = PositionOrderEmitter(
        orderSink = NoopOrderSink(),
        currentPosition = { null },
        maxPositionSizeBaseCoin = 1.0,
    )

    @Test
    fun `AgentLiveSession start restores a prior checkpoint before its first processed bar`() = runBlocking<Unit> {
        val reservoirWeights = freshReservoirWeights()
        val warmupKlines = fixtureKlines(bars = 30, seed = 13131L)

        val original = freshOrchestrator(reservoirWeights)
        driveBars(original, warmupKlines)

        val tmpDir = Files.createTempDirectory("agent-live-session-start-restore-test").toFile()
        val store = FileAgentCheckpointStore(File(tmpDir, "checkpoint.json"))
        store.save(original.currentCheckpoint())

        val nextBar = warmupKlines.last().copy(startTime = warmupKlines.size * 60_000L)
        val nextLiveBarClose = AgentOrchestrator.LiveBarClose(
            barIndex = warmupKlines.size,
            kline = nextBar,
            klinesSoFar = warmupKlines + nextBar,
            depth = depthFor(nextBar.close),
        )

        // What a session that correctly restored the checkpoint should
        // decide on the very next bar - computed independently via
        // toOrchestrator(), not by calling AgentLiveSession.start() itself.
        val expectedDecision = original
            .currentCheckpoint()
            .toOrchestrator(
                featureAssembler = FeatureAssembler(),
                reservoirWeights = reservoirWeights,
                rewardEngine = RewardEngine(),
            )
            .processLiveBar(
                liveBarClose = nextLiveBarClose,
                state = AgentOrchestrator.LiveInferenceState(),
                fundingRateAt = { 0.0 },
            )

        val restoredSession = AgentLiveSession.start(
            checkpointStore = store,
            featureAssembler = FeatureAssembler(),
            reservoirWeights = reservoirWeights,
            rewardEngine = RewardEngine(),
            orderEmitter = newOrderEmitter(),
        )
        val restoredDecision = restoredSession.processLiveBar(nextLiveBarClose, fundingRateAt = { 0.0 })

        assertEquals(expectedDecision.readoutForecast, restoredDecision.readoutForecast, 0f)
        assertEquals(expectedDecision.position, restoredDecision.position, 0f)

        // And the negative control: a session started against an *empty*
        // store on the same bar must decide differently - proving the
        // match above came from genuinely restoring state, not from every
        // session producing the same output on this bar regardless.
        val freshStore = FileAgentCheckpointStore(File(tmpDir, "does-not-exist.json"))
        val freshSession = AgentLiveSession.start(
            checkpointStore = freshStore,
            featureAssembler = FeatureAssembler(),
            reservoirWeights = reservoirWeights,
            rewardEngine = RewardEngine(),
            orderEmitter = newOrderEmitter(),
        )
        val freshDecision = freshSession.processLiveBar(nextLiveBarClose, fundingRateAt = { 0.0 })
        assertTrue(
            "a session started against an empty store should not coincidentally match the restored session's forecast",
            expectedDecision.readoutForecast != freshDecision.readoutForecast,
        )

        tmpDir.deleteRecursively()
    }
}
