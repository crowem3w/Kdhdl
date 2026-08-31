package org.example.syncora.agent

import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor

/**
 * Phase 7 (`ESN_RRL_Agent_Task_Prompts.md` Prompt 8) end-to-end tests:
 * "Write failure-mode tests that simulate a market-data feed dropout, stale
 * klines, RLS divergence (e.g. covariance blow-up), and NaN output from
 * `PolicyEngine`, and confirm in each case the orchestrator's response is
 * either 'no order sent' or 'flatten the position' - never a garbage or
 * unhandled order reaching the exchange path."
 *
 * Each test drives a real [HardenedAgentLiveSession] wired to a real
 * [AgentOrchestrator] (not just [GuardrailSupervisor] in isolation - that's
 * `GuardrailSupervisorTest`) against a stub [PaperOrderSink] that records
 * every call, so "never a garbage or unhandled order" is checked the same
 * way `PositionOrderEmitterTest` checks its own three scripted cases: by
 * asserting the *exact* sequence of calls the sink received, not just the
 * [GuardedAction] returned.
 *
 * This phase "has no partial credit: the exit criterion is that every
 * single failure-mode test results in a safe outcome, with no exceptions" -
 * every `@Test` below is one such check.
 */
class GuardrailHardeningFailureModeTest {

    private val nHidden = 50 // ReservoirWeights.randomWeights requires nHidden in [50, 150] (Phase 2's design range)
    private val barIntervalMs = 60_000L

    /** Same recording-stub shape `PositionOrderEmitterTest` uses, trimmed to just what these tests need. */
    private class RecordingOrderSink : PaperOrderSink {
        data class Call(val type: String, val side: PositionSide, val size: Double)

        val calls = mutableListOf<Call>()
        var position: PaperPosition? = null

        override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) {
            val size = sizeInBaseCoin.toDouble()
            calls.add(Call("open", side, size))
            val existing = position
            position = if (existing != null && existing.side == side) {
                existing.copy(total = existing.total + size)
            } else {
                PaperPosition(
                    symbol = "BTCUSDT",
                    side = side,
                    total = size,
                    available = size,
                    entryPrice = 50_000.0,
                    markPrice = 50_000.0,
                    leverage = leverage,
                    marginSize = size * 50_000.0 / leverage,
                    unrealizedPnl = 0.0,
                )
            }
        }

        override fun closePosition(position: PaperPosition) {
            calls.add(Call("close", position.side, this.position?.total ?: 0.0))
            this.position = null
        }
    }

    private fun fixtureKline(index: Int, startTime: Long, price: Double = 50_000.0): Kline = Kline(
        startTime = startTime,
        open = price,
        high = price * 1.0005,
        low = price * 0.9995,
        close = price,
        baseVolume = 1.0,
        quoteVolume = price,
        usdtVolume = price,
    )

    private fun fixtureDepth(price: Double = 50_000.0): DepthSnapshot {
        val spread = price * 0.0004
        return DepthSnapshot(
            bids = listOf(DepthLevel(price - spread / 2, 2.0)),
            asks = listOf(DepthLevel(price + spread / 2, 2.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    private fun fixtureLiveBarClose(barIndex: Int, startTime: Long, price: Double = 50_000.0): AgentOrchestrator.LiveBarClose {
        val kline = fixtureKline(barIndex, startTime, price)
        return AgentOrchestrator.LiveBarClose(
            barIndex = barIndex,
            kline = kline,
            klinesSoFar = listOf(kline),
            depth = fixtureDepth(price),
        )
    }

    /** A healthy, freshly-constructed [AgentOrchestrator] - the "nothing has gone wrong yet" baseline every test starts from unless it deliberately corrupts one component below. */
    private fun healthyOrchestrator(
        readout: ReadoutTrainer = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f),
        policy: PolicyEngine = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 3L),
    ): AgentOrchestrator {
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 21L)
        val reservoir = ReservoirEngine(weights)
        val reward = RewardEngine()
        return AgentOrchestrator(assembler, reservoir, readout, reward, policy)
    }

    private fun newSession(
        orchestrator: AgentOrchestrator,
        orderSink: RecordingOrderSink,
        killSwitch: AgentKillSwitch = AgentKillSwitch(),
        caps: PositionCaps = PositionCaps(),
        maxCovarianceMagnitude: Float = 1.0e6f,
        nowMs: () -> Long,
    ): HardenedAgentLiveSession {
        val emitter = PositionOrderEmitter(
            orderSink = orderSink,
            currentPosition = { orderSink.position },
            maxPositionSizeBaseCoin = 1.0,
            leverage = 1,
        )
        val supervisor = GuardrailSupervisor(
            caps = caps,
            killSwitch = killSwitch,
            maxCovarianceMagnitude = maxCovarianceMagnitude,
        )
        return HardenedAgentLiveSession(
            orchestrator = orchestrator,
            orderEmitter = emitter,
            guardrailSupervisor = supervisor,
            killSwitch = killSwitch,
            expectedBarIntervalMs = barIntervalMs,
            positionSizeScaleBaseCoin = 1.0,
            nowMs = nowMs,
        )
    }

    // ---- Failure mode 1: market-data feed dropout ----

    @Test
    fun `feed dropout after an open position flattens it, never leaves a garbage order`() {
        val orderSink = RecordingOrderSink()
        var clock = 0L
        val killSwitch = AgentKillSwitch()
        val session = newSession(
            orchestrator = healthyOrchestrator(policy = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 3L, initialWeights = FloatArray(nHidden + 2 + 1) { 2f })),
            orderSink = orderSink,
            killSwitch = killSwitch,
            nowMs = { clock },
        )

        // First bar: healthy, opens a position (large initial weights push tanh toward +1).
        val first = session.processLiveBar(fixtureLiveBarClose(0, startTime = clock), referencePrice = 50_000.0)
        assertTrue("expected a Trade on the first healthy bar, got $first", first is GuardedAction.Trade)
        assertTrue("expected the healthy first bar to actually open a position", orderSink.calls.any { it.type == "open" })

        // Simulate a long feed dropout: wall clock jumps far ahead with no bar-close events in between.
        clock += 10 * barIntervalMs
        val second = session.processLiveBar(fixtureLiveBarClose(1, startTime = clock), referencePrice = 50_000.0)

        assertTrue("expected Flatten on feed dropout, got $second", second is GuardedAction.Flatten)
        assertTrue("expected the kill switch to engage on a feed dropout", killSwitch.isTriggered())
        assertEquals(listOf("open", "close"), orderSink.calls.map { it.type })
        assertNull("position should be flat after a dropout-triggered flatten", orderSink.position)
    }

    @Test
    fun `feed dropout while already flat sends no order`() {
        // Zero initial policy weights: PolicyEngine.step's z is exactly 0
        // on the very first bar (no reservoir/readout contribution has been
        // trained in yet either, since W_out starts at zero too), so
        // tanh(0) = 0 - the session stays flat through the first, healthy
        // bar before the dropout is simulated, matching the "already flat"
        // premise this test is named for.
        val flatPolicy = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 3L, initialWeights = FloatArray(nHidden + 2 + 1))
        val orderSink = RecordingOrderSink()
        var clock = 0L
        val session = newSession(orchestrator = healthyOrchestrator(policy = flatPolicy), orderSink = orderSink, nowMs = { clock })

        val first = session.processLiveBar(fixtureLiveBarClose(0, startTime = clock), referencePrice = 50_000.0)
        assertTrue("expected the first bar to be a healthy (flat) Trade, got $first", first is GuardedAction.Trade)
        assertTrue("policy should have stayed flat with all-zero initial weights", orderSink.calls.isEmpty())

        clock += 10 * barIntervalMs
        val action = session.processLiveBar(fixtureLiveBarClose(1, startTime = clock), referencePrice = 50_000.0)

        assertTrue("expected a Flatten decision for a genuine dropout, got $action", action is GuardedAction.Flatten)
        assertTrue("no position was ever open, so flattening it should be a no-op order-wise", orderSink.calls.isEmpty())
    }

    // ---- Failure mode 2: stale klines ----

    @Test
    fun `stale kline sends no order and does not trip the kill switch`() {
        val orderSink = RecordingOrderSink()
        val killSwitch = AgentKillSwitch()
        val nowMs = 100 * barIntervalMs
        val session = newSession(orchestrator = healthyOrchestrator(), orderSink = orderSink, killSwitch = killSwitch, nowMs = { nowMs })

        val staleBar = fixtureLiveBarClose(0, startTime = nowMs - 10 * barIntervalMs)
        val action = session.processLiveBar(staleBar, referencePrice = 50_000.0)

        assertTrue("expected NoOrder for a stale kline, got $action", action is GuardedAction.NoOrder)
        assertTrue("a stale kline should not place any order", orderSink.calls.isEmpty())
        assertFalse("a single stale bar should not halt the whole session", killSwitch.isTriggered())
    }

    // ---- Failure mode 3: RLS divergence (covariance blow-up) ----

    @Test
    fun `diverged RLS covariance flattens even though every individual value is still finite`() {
        val orderSink = RecordingOrderSink()
        val killSwitch = AgentKillSwitch()
        // A ReadoutTrainer seeded with an already-blown-up (but finite) covariance -
        // exactly Prompt 8's "RLS divergence (e.g. covariance blow-up)" scenario,
        // simulated directly rather than hoping many bars of pathological input
        // happen to reproduce it.
        val divergedCovariance = FloatArray((nHidden + 1) * (nHidden + 1)).also { p ->
            for (i in 0 until nHidden + 1) p[i * (nHidden + 1) + i] = 1.0e10f
        }
        val readout = ReadoutTrainer(nHidden = nHidden, initialCovariance = divergedCovariance)
        var clock = 0L
        val session = newSession(
            orchestrator = healthyOrchestrator(readout = readout),
            orderSink = orderSink,
            killSwitch = killSwitch,
            maxCovarianceMagnitude = 1.0e6f,
            nowMs = { clock },
        )

        val action = session.processLiveBar(fixtureLiveBarClose(0, startTime = clock), referencePrice = 50_000.0)

        assertTrue("expected Flatten for a diverged covariance, got $action", action is GuardedAction.Flatten)
        assertTrue("RLS divergence should halt the session", killSwitch.isTriggered())
        assertTrue("no order or, at most, a flatten-of-nothing should have been placed", orderSink.calls.isEmpty())
    }

    // ---- Failure mode 4: NaN output from PolicyEngine ----

    @Test
    fun `NaN policy output flattens an existing position rather than sending a garbage order`() {
        // A PolicyEngine seeded with a NaN weight - any regressor with a
        // nonzero coefficient at that index makes z, and therefore
        // tanh(z), NaN on the very first step.
        val corruptedWeights = FloatArray(nHidden + 2 + 1)
        corruptedWeights[0] = Float.NaN
        val policy = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 3L, initialWeights = corruptedWeights)

        val orderSink = RecordingOrderSink()
        val killSwitch = AgentKillSwitch()
        var clock = 0L
        val session = newSession(orchestrator = healthyOrchestrator(policy = policy), orderSink = orderSink, killSwitch = killSwitch, nowMs = { clock })

        val action = session.processLiveBar(fixtureLiveBarClose(0, startTime = clock), referencePrice = 50_000.0)

        assertTrue("expected Flatten for a NaN policy output, got $action", action is GuardedAction.Flatten)
        assertTrue("a NaN policy output should halt the session", killSwitch.isTriggered())
        assertTrue("no order should reach the sink from a NaN target position", orderSink.calls.isEmpty())
    }

    // ---- Hard caps: enforced regardless of the policy's own output ----

    @Test
    fun `hard position and notional caps bind even when the policy would go further`() {
        // Large positive weights push tanh(z) toward +1 - the policy "wants"
        // max long - but a tight fraction cap must still bind.
        val eagerWeights = FloatArray(nHidden + 2 + 1) { 3f }
        val policy = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 3L, initialWeights = eagerWeights)

        val orderSink = RecordingOrderSink()
        var clock = 0L
        val session = newSession(
            orchestrator = healthyOrchestrator(policy = policy),
            orderSink = orderSink,
            caps = PositionCaps(maxPositionFraction = 0.25f),
            nowMs = { clock },
        )

        val action = session.processLiveBar(fixtureLiveBarClose(0, startTime = clock), referencePrice = 50_000.0)

        assertTrue(action is GuardedAction.Trade)
        val traded = (action as GuardedAction.Trade).position
        assertTrue("policy output should have been well above the cap before clamping", traded <= 0.25f + 1e-6f)
        assertEquals("open", orderSink.calls.single().type)
        assertTrue("emitted size should reflect the capped fraction (0.25 * maxPositionSizeBaseCoin), not the policy's raw near-1.0 output", orderSink.calls.single().size <= 0.25 + 1e-6)
    }

    // ---- Kill switch: reachable and immediate, independent of any dispatcher ----

    @Test
    fun `engaging the kill switch flattens synchronously without needing another bar or a coroutine to run`() {
        val orderSink = RecordingOrderSink()
        val killSwitch = AgentKillSwitch()

        // Open a position first via a healthy bar with eager weights.
        val eagerWeights = FloatArray(nHidden + 2 + 1) { 3f }
        val eagerSession = newSession(
            orchestrator = healthyOrchestrator(policy = PolicyEngine(nHidden = nHidden, nBack = 2, seed = 3L, initialWeights = eagerWeights)),
            orderSink = orderSink,
            killSwitch = killSwitch,
            nowMs = { 0L },
        )
        eagerSession.processLiveBar(fixtureLiveBarClose(0, startTime = 0L), referencePrice = 50_000.0)
        assertTrue(orderSink.calls.any { it.type == "open" })

        // The kill switch call itself is plain, synchronous Kotlin - no
        // CoroutineScope, no suspend, no join() needed - so the flatten is
        // already visible the instant this call returns.
        eagerSession.engageKillSwitch("test-triggered halt")

        assertTrue(killSwitch.isTriggered())
        assertEquals("test-triggered halt", killSwitch.reason())
        assertNull("position must already be flat immediately after engageKillSwitch returns", orderSink.position)
        assertEquals(listOf("open", "close"), orderSink.calls.map { it.type })
    }

    // ---- Exchange-side stop independence (docs/agent-design-contract.md §2) ----

    /**
     * "Confirmation that the exchange-side stop from `RiskSettingsStore` is
     * placed independently of the agent's own risk assessment" (Prompt 8):
     * asserted here by reflection over every constructor parameter of this
     * phase's own new classes, the same "true by construction" reasoning
     * [AgentOrchestrator]'s own class doc uses for "zero live or paper
     * orders" - if none of these classes so much as *declares* a parameter
     * of type `RiskSettingsStore`/`StopLossGuard`/`StopLossOrder`, there is
     * no code path by which this guardrail layer could read, weaken, or
     * depend on the exchange-side stop's presence, matching design doc
     * §2's "nothing in Phases 5-9 wires [agent output] into
     * `RiskSettingsStore` or into `StopLossGuard`'s guard loop."
     */
    @Test
    fun `Phase 7 guardrail classes declare no dependency on the exchange-side stop-loss classes`() {
        val forbiddenSimpleNames = setOf("RiskSettingsStore", "StopLossGuard", "StopLossOrder")
        val guardedClasses = listOf(
            AgentKillSwitch::class.java,
            PositionCaps::class.java,
            GuardrailSupervisor::class.java,
            HardenedAgentLiveSession::class.java,
        )

        for (clazz in guardedClasses) {
            val offendingParams = clazz.declaredConstructors
                .flatMap { it.parameterTypes.toList() }
                .filter { it.simpleName in forbiddenSimpleNames }
            assertTrue(
                "$clazz must not declare a constructor parameter of any of $forbiddenSimpleNames, " +
                    "found: $offendingParams",
                offendingParams.isEmpty(),
            )

            val offendingFields = clazz.declaredFields
                .filter { it.type.simpleName in forbiddenSimpleNames }
            assertTrue(
                "$clazz must not declare a field of any of $forbiddenSimpleNames, found: $offendingFields",
                offendingFields.isEmpty(),
            )
        }
    }

    /** Sanity check on the reflection approach itself: it must actually be able to detect a real dependency, not just always pass. */
    @Test
    fun `reflection independence check is itself sound - it would catch a real dependency`() {
        class WouldBeCoupled(@Suppress("UNUSED_PARAMETER") store: org.example.syncora.bitget.RiskSettingsStore)

        val ctor: Constructor<*> = WouldBeCoupled::class.java.declaredConstructors.single()
        val hasForbiddenParam = ctor.parameterTypes.any { it.simpleName == "RiskSettingsStore" }
        assertTrue(hasForbiddenParam)
    }
}
