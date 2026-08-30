package org.example.syncora.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.example.syncora.bitget.StopLossGuard
import org.example.syncora.bitget.StopLossOrder
import org.example.syncora.bitget.StopLossOrderClient
import org.example.syncora.bitget.StopLossPercentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Prompt 8b's whole exit criterion (`ESN_RRL_Agent_Task_Prompts.md`,
 * building on 8a's caps - left untouched here): the exchange-side stop
 * from [org.example.syncora.bitget.RiskSettingsStore] (via [StopLossGuard])
 * must reach the exchange path regardless of what [PolicyEngine] or
 * [RewardEngine] compute - `docs/agent-design-contract.md` §2's
 * "the policy is not the sole line of defense", made concrete.
 *
 * ### Why this already holds today, and what this test proves
 * [StopLossGuard] is fed [org.example.syncora.bitget.LiveTradingRepository.positions]
 * - the exchange's own reported position state - never anything from
 * [AgentOrchestrator]/[PolicyEngine]/[PositionOrderEmitter]. There is
 * simply no import from [StopLossGuard] into the `agent` package, and no
 * import the other way either (see [StopLossGuard]'s own class doc,
 * "Independence from the RRL agent"). That's a structural guarantee, but
 * "nothing wires them together" isn't yet something a test enforces - this
 * file is that explicit check, instantiating **both** halves of "the full
 * order path" side by side in one test:
 *
 * 1. The agent's own order path - a real [AgentOrchestrator] (Phases 1-5)
 *    driving a real [PositionOrderEmitter] (Prompt 7c) - but with
 *    [PolicyEngine] deliberately blanked (see [blankedPolicyEngine]), so
 *    its output is inert by construction, not just "happened to be flat
 *    this run".
 * 2. The exchange-side stop path - a real [StopLossGuard] backed by fakes
 *    for [StopLossOrderClient]/[StopLossPercentSource] (Prompt 8b's own
 *    addition - see those interfaces' docs for why they exist) instead of
 *    a real network call, fed a position that is open on the exchange
 *    *regardless* of what path 1 just decided.
 *
 * The assertion that matters is the second path's: [StopLossGuard] places
 * its stop even though the agent's policy output was blanked. Path 1's own
 * assertion (the blanked policy really did produce ~0 and place no order)
 * exists only to rule out the boring failure mode of this test vacuously
 * passing because the policy happened to want the same thing anyway.
 */
class ExchangeStopIndependenceTest {

    // Must be >= ReservoirWeights.MIN_N_HIDDEN (50, per Phase 2's design -
    // ReservoirEngine.kt) or ReservoirWeights.randomWeights throws.
    private val nHidden = 50

    // ---- Fakes for Prompt 8b's own new interfaces (StopLossOrderClient / StopLossPercentSource) ----

    private class FakeStopLossOrderClient(
        private val existingStops: List<StopLossOrder> = emptyList(),
    ) : StopLossOrderClient {
        data class PlacedStop(
            val symbol: String,
            val holdSide: PositionSide,
            val triggerPrice: String,
            val sizeInBaseCoin: String,
        )

        val placed = mutableListOf<PlacedStop>()
        val cancelledOrderIds = mutableListOf<String>()

        override suspend fun placeStopLoss(
            symbol: String,
            holdSide: PositionSide,
            triggerPrice: String,
            sizeInBaseCoin: String,
        ): String {
            placed.add(PlacedStop(symbol, holdSide, triggerPrice, sizeInBaseCoin))
            return "fake-stop-${placed.size}"
        }

        override suspend fun fetchPendingStopLossOrders(symbol: String): List<StopLossOrder> =
            existingStops.filter { it.symbol == symbol }

        override suspend fun cancelStopLoss(symbol: String, orderId: String) {
            cancelledOrderIds.add(orderId)
        }
    }

    private class FakeStopLossPercentSource(override val stopLossPercent: Double) : StopLossPercentSource

    // ---- A stub standing in for the agent's own paper order path (same shape as PositionOrderEmitterTest's) ----

    private class StubPaperTradingAccount : PaperOrderSink {
        data class Call(val type: String, val side: PositionSide)

        val calls = mutableListOf<Call>()
        private var position: PaperPosition? = null

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

    // ---- Fixture helpers (same LCG-walk approach as AgentOrchestratorBacktestTest / LiveInferenceEquivalenceTest) ----

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

    /**
     * A [PolicyEngine] with every weight forced to exactly zero and a
     * vanishing learning rate: `z_t = w · regressor_t = 0` for *any*
     * regressor, so `f_t = tanh(0) = 0` on the very first [PolicyEngine.step]
     * call regardless of what the reservoir state or readout forecast are -
     * not a policy that happens to output near-zero, but one that cannot
     * output anything else. The vanishing learning rate keeps it that way
     * across the few bars this test drives, since [AgentOrchestrator] has
     * no code path that skips calling [PolicyEngine.update].
     */
    private fun blankedPolicyEngine(nHidden: Int): PolicyEngine {
        val nRegressors = nHidden + 1 + PolicyEngine.DEFAULT_N_BACK + 1
        return PolicyEngine(
            nHidden = nHidden,
            nBack = PolicyEngine.DEFAULT_N_BACK,
            learningRate = 1e-12f,
            initialWeights = FloatArray(nRegressors), // all zero
        )
    }

    private fun openLivePosition(
        side: PositionSide = PositionSide.LONG,
        entryPrice: Double = 50_000.0,
    ): PaperPosition = PaperPosition(
        symbol = "BTCUSDT",
        side = side,
        total = 0.5,
        available = 0.5,
        entryPrice = entryPrice,
        markPrice = entryPrice * 1.01,
        leverage = 5,
        marginSize = entryPrice * 0.5 / 5,
        unrealizedPnl = 0.0,
    )

    @Test
    fun `exchange-side stop is placed for an open position even with the policy's output blanked`() = runTest {
        // ---- Path 1: the agent's own order path, driven by a policy whose output cannot be anything but 0 ----
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 11L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden)
        val reward = RewardEngine()
        val policy = blankedPolicyEngine(nHidden)
        val orchestrator = AgentOrchestrator(assembler, reservoir, readout, reward, policy)

        val klines = fixtureKlines(bars = 3, seed = 42L)
        val liveState = AgentOrchestrator.LiveInferenceState()
        var lastDecision: AgentOrchestrator.DecisionLog? = null
        for (t in klines.indices) {
            val kline = klines[t]
            val liveBarClose = AgentOrchestrator.LiveBarClose(
                barIndex = t,
                kline = kline,
                klinesSoFar = klines.subList(0, t + 1),
                depth = depthFor(kline.close, seed = t.toLong()),
            )
            lastDecision = orchestrator.processLiveBar(liveBarClose = liveBarClose, state = liveState)
        }

        // Sanity check that this test is exercising the claimed condition,
        // not vacuously passing because the policy happened to want flat anyway.
        assertTrue(
            "expected the blanked policy's output to be ~0, was ${lastDecision!!.position}",
            abs(lastDecision.position) < 1e-6f,
        )

        val agentAccount = StubPaperTradingAccount()
        val agentOrderEmitter = PositionOrderEmitter(
            orderSink = agentAccount,
            currentPosition = agentAccount::snapshot,
            maxPositionSizeBaseCoin = 1.0,
            maxNotionalUsdt = 1_000_000.0,
            referencePrice = { 50_000.0 },
        )
        agentOrderEmitter.onTargetPosition(lastDecision.position)
        assertTrue(
            "the blanked policy's own order path should place no order at all - got ${agentAccount.calls}",
            agentAccount.calls.isEmpty(),
        )

        // ---- Path 2: the exchange-side stop, fed a position that is open on the exchange regardless of what path 1 just decided ----
        val fakeClient = FakeStopLossOrderClient()
        val fakeRiskSettings = FakeStopLossPercentSource(stopLossPercent = 0.03)
        val guard = StopLossGuard(client = fakeClient, riskSettingsStore = fakeRiskSettings, scope = this)

        val livePosition = openLivePosition(side = PositionSide.LONG, entryPrice = 50_000.0)
        val positions = MutableStateFlow(listOf(livePosition))
        guard.start(positions)
        advanceUntilIdle()

        assertEquals(
            "expected exactly one stop placed for the one open position, got ${fakeClient.placed}",
            1,
            fakeClient.placed.size,
        )
        val placedStop = fakeClient.placed.single()
        assertEquals("BTCUSDT", placedStop.symbol)
        assertEquals(PositionSide.LONG, placedStop.holdSide)
        assertEquals(0.5, placedStop.sizeInBaseCoin.toDouble(), 1e-6)
        val expectedTrigger = 50_000.0 * (1.0 - 0.03)
        assertEquals(expectedTrigger, placedStop.triggerPrice.toDouble(), 5.0) // formatPrice rounds to one decimal

        guard.stop()
    }

    @Test
    fun `the stop's placement never once reads PolicyEngine, RewardEngine, or AgentOrchestrator state`() = runTest {
        // The strongest version of the independence claim: no AgentOrchestrator, PolicyEngine, or
        // RewardEngine is even constructed in this test - StopLossGuard, fed only an exchange-reported
        // position and its own two Prompt-8b interfaces, must still place the stop on its own.
        val fakeClient = FakeStopLossOrderClient()
        val fakeRiskSettings = FakeStopLossPercentSource(stopLossPercent = 0.05)
        val guard = StopLossGuard(client = fakeClient, riskSettingsStore = fakeRiskSettings, scope = this)

        val livePosition = openLivePosition(side = PositionSide.SHORT, entryPrice = 60_000.0)
        val positions = MutableStateFlow(listOf(livePosition))
        guard.start(positions)
        advanceUntilIdle()

        assertEquals(1, fakeClient.placed.size)
        val placedStop = fakeClient.placed.single()
        assertEquals(PositionSide.SHORT, placedStop.holdSide)
        val expectedTrigger = 60_000.0 * (1.0 + 0.05)
        assertEquals(expectedTrigger, placedStop.triggerPrice.toDouble(), 5.0)

        guard.stop()
    }

    @Test
    fun `a flat exchange position - no position at all - places no stop, independent of the agent`() = runTest {
        // The mirror case: StopLossGuard's decision to place (or not place) a stop tracks the
        // exchange's own reported position, never anything the agent computed.
        val fakeClient = FakeStopLossOrderClient()
        val guard = StopLossGuard(client = fakeClient, riskSettingsStore = FakeStopLossPercentSource(0.03), scope = this)

        val positions = MutableStateFlow(emptyList<PaperPosition>())
        guard.start(positions)
        advanceUntilIdle()

        assertTrue("no open position should mean no stop is placed", fakeClient.placed.isEmpty())
        guard.stop()
    }
}
