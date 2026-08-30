package org.example.syncora.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 (`ESN_RRL_Agent_Task_Prompts.md` Prompt 8) unit-level tests for
 * [AgentKillSwitch], [PositionCaps], and [GuardrailSupervisor]'s gates in
 * isolation - fixture-driven, no [AgentOrchestrator], no live feed, same
 * "engine testable on its own" pattern every phase since 1 has followed
 * (`RewardEngineTest`, `PowerIterationTest`, etc.). End-to-end wiring
 * through [HardenedAgentLiveSession] is `GuardrailHardeningFailureModeTest`.
 */
class GuardrailSupervisorTest {

    // ---- AgentKillSwitch ----

    @Test
    fun `kill switch starts untriggered`() {
        val killSwitch = AgentKillSwitch()
        assertFalse(killSwitch.isTriggered())
        assertNull(killSwitch.reason())
    }

    @Test
    fun `kill switch trigger is idempotent and keeps the first reason`() {
        val killSwitch = AgentKillSwitch()
        killSwitch.trigger("first reason")
        killSwitch.trigger("second reason")
        assertTrue(killSwitch.isTriggered())
        assertEquals("first reason", killSwitch.reason())
    }

    @Test
    fun `kill switch reset clears triggered state and reason`() {
        val killSwitch = AgentKillSwitch()
        killSwitch.trigger("tripped")
        killSwitch.reset()
        assertFalse(killSwitch.isTriggered())
        assertNull(killSwitch.reason())
    }

    // ---- PositionCaps ----

    @Test
    fun `PositionCaps fraction cap clamps regardless of sign`() {
        val caps = PositionCaps(maxPositionFraction = 0.3f)
        assertEquals(0.3f, caps.clamp(1.0f, referencePrice = 50_000.0, positionSizeScaleBaseCoin = 1.0), 1e-6f)
        assertEquals(-0.3f, caps.clamp(-1.0f, referencePrice = 50_000.0, positionSizeScaleBaseCoin = 1.0), 1e-6f)
    }

    @Test
    fun `PositionCaps never widens a target already inside both caps`() {
        val caps = PositionCaps(maxPositionFraction = 0.8f, maxNotionalBaseCoin = 100_000.0)
        val result = caps.clamp(0.1f, referencePrice = 50_000.0, positionSizeScaleBaseCoin = 1.0)
        assertEquals(0.1f, result, 1e-6f)
    }

    @Test
    fun `PositionCaps notional cap binds tighter than fraction cap when price is high`() {
        // maxPositionSizeBaseCoin = 1.0 base coin per unit fraction, price = 100_000 ->
        // f=1.0 implies notional 100_000. A 10_000 notional cap should clamp to f=0.1
        // even though the fraction cap alone (1.0) would allow the full target.
        val caps = PositionCaps(maxPositionFraction = 1f, maxNotionalBaseCoin = 10_000.0)
        val result = caps.clamp(1.0f, referencePrice = 100_000.0, positionSizeScaleBaseCoin = 1.0)
        assertEquals(0.1f, result, 1e-4f)
    }

    @Test
    fun `PositionCaps falls back to fraction-only when price is unusable`() {
        val caps = PositionCaps(maxPositionFraction = 0.6f, maxNotionalBaseCoin = 1.0)
        val result = caps.clamp(1.0f, referencePrice = 0.0, positionSizeScaleBaseCoin = 1.0)
        assertEquals(0.6f, result, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PositionCaps rejects a fraction cap outside (0,1]`() {
        PositionCaps(maxPositionFraction = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PositionCaps rejects a non-positive notional cap`() {
        PositionCaps(maxNotionalBaseCoin = 0.0)
    }

    // ---- GuardrailSupervisor: feed freshness / dropout ----

    private fun newSupervisor(
        caps: PositionCaps = PositionCaps(),
        killSwitch: AgentKillSwitch = AgentKillSwitch(),
        maxBarStalenessMultiplier: Double = 3.0,
        maxCovarianceMagnitude: Float = 1.0e6f,
    ) = GuardrailSupervisor(caps, killSwitch, maxBarStalenessMultiplier, maxCovarianceMagnitude)

    @Test
    fun `fresh kline passes the staleness check`() {
        val supervisor = newSupervisor()
        val action = supervisor.checkFeedFreshness(nowMs = 100_000L, klineStartTime = 99_500L, expectedBarIntervalMs = 60_000L)
        assertNull(action)
    }

    @Test
    fun `stale kline is a NoOrder, not a Flatten, and does not trip the kill switch`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val nowMs = 10_000_000L
        val staleKlineStart = nowMs - 10 * 60_000L // 10 bars old on a 60s bar interval, well past the 3x threshold
        val action = supervisor.checkFeedFreshness(nowMs = nowMs, klineStartTime = staleKlineStart, expectedBarIntervalMs = 60_000L)
        assertTrue(action is GuardedAction.NoOrder)
        assertFalse(killSwitch.isTriggered())
    }

    @Test
    fun `cold-start dropout check with no prior bar is not itself a dropout`() {
        val supervisor = newSupervisor()
        val action = supervisor.checkFeedDropout(nowMs = 1_000_000L, lastBarCloseReceivedAtMs = null, expectedBarIntervalMs = 60_000L)
        assertNull(action)
    }

    @Test
    fun `feed dropout is a Flatten and trips the kill switch`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val nowMs = 10_000_000L
        val lastReceived = nowMs - 10 * 60_000L // no bar for 10 intervals
        val action = supervisor.checkFeedDropout(nowMs = nowMs, lastBarCloseReceivedAtMs = lastReceived, expectedBarIntervalMs = 60_000L)
        assertTrue(action is GuardedAction.Flatten)
        assertTrue(killSwitch.isTriggered())
    }

    // ---- GuardrailSupervisor: evaluateDecision ----

    private fun fixtureDecision(position: Float, barIndex: Int = 0): AgentOrchestrator.DecisionLog =
        AgentOrchestrator.DecisionLog(
            barIndex = barIndex,
            startTime = 0L,
            features = FloatArray(4),
            reservoirState = FloatArray(4),
            readoutForecast = 0f,
            previousPosition = 0f,
            position = position,
            reward = 0.0,
            markToMarketPnl = 0.0,
            transactionCost = 0.0,
            fundingCost = 0.0,
            differentialSharpe = 0.0,
        )

    @Test
    fun `healthy decision produces a capped Trade`() {
        val supervisor = newSupervisor(caps = PositionCaps(maxPositionFraction = 0.5f))
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = 0.9f),
            orchestratorStable = true,
            readoutCovarianceMagnitude = 100f,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Trade)
        assertEquals(0.5f, (action as GuardedAction.Trade).position, 1e-6f)
    }

    @Test
    fun `NaN policy output flattens and trips the kill switch`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = Float.NaN),
            orchestratorStable = true,
            readoutCovarianceMagnitude = 1f,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Flatten)
        assertTrue(killSwitch.isTriggered())
    }

    @Test
    fun `out-of-bounds policy output flattens and trips the kill switch`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = 1.5f),
            orchestratorStable = true,
            readoutCovarianceMagnitude = 1f,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Flatten)
        assertTrue(killSwitch.isTriggered())
    }

    @Test
    fun `unstable orchestrator flattens and trips the kill switch even with a finite position`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = 0.2f),
            orchestratorStable = false,
            readoutCovarianceMagnitude = 1f,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Flatten)
        assertTrue(killSwitch.isTriggered())
    }

    @Test
    fun `RLS covariance blow-up flattens and trips the kill switch`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch, maxCovarianceMagnitude = 1_000f)
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = 0.2f),
            orchestratorStable = true,
            readoutCovarianceMagnitude = 1.0e9f,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Flatten)
        assertTrue(killSwitch.isTriggered())
    }

    @Test
    fun `non-finite covariance magnitude flattens the same as an over-ceiling one`() {
        val killSwitch = AgentKillSwitch()
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = 0.2f),
            orchestratorStable = true,
            readoutCovarianceMagnitude = Float.POSITIVE_INFINITY,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Flatten)
        assertTrue(killSwitch.isTriggered())
    }

    @Test
    fun `already-engaged kill switch flattens a bar that would otherwise be perfectly healthy`() {
        val killSwitch = AgentKillSwitch()
        killSwitch.trigger("engaged before this bar")
        val supervisor = newSupervisor(killSwitch = killSwitch)
        val action = supervisor.evaluateDecision(
            decision = fixtureDecision(position = 0.1f),
            orchestratorStable = true,
            readoutCovarianceMagnitude = 1f,
            referencePrice = 50_000.0,
            positionSizeScaleBaseCoin = 1.0,
        )
        assertTrue(action is GuardedAction.Flatten)
        assertNotNull(killSwitch.reason())
    }

    // ---- ReadoutTrainer.covarianceMagnitude ----

    @Test
    fun `fresh ReadoutTrainer covariance magnitude equals its initial diagonal scale`() {
        val trainer = ReadoutTrainer(nHidden = 4, initialCovarianceScale = 100f)
        assertEquals(100f, trainer.covarianceMagnitude(), 1e-6f)
    }
}
