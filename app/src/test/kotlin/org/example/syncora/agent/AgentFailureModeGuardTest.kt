package org.example.syncora.agent

import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 8d's final gate (`ESN_RRL_Agent_Task_Prompts.md`): with 8a's caps,
 * 8b's exchange-stop-independence verification, and 8c's kill switch all in
 * place and individually passing, this is the failure-mode test suite -
 * simulating (1) a market-data feed dropout, (2) stale klines, (3) RLS
 * divergence (covariance blow-up), and (4) NaN output from [PolicyEngine],
 * and for each asserting [AgentFailureModeGuard]'s response is either "no
 * order sent" or "flatten the position" - never a garbage or unhandled
 * order reaching the exchange path. All four must pass, with no exceptions,
 * before Prompt 9 (Phase 8: Live Trading) is even considered.
 */
class AgentFailureModeGuardTest {

    private val nHidden = 30

    /** Same shape as `AgentKillSwitchTest`'s stub - records calls and tracks the resulting position. */
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

    private fun fixtureKlines(bars: Int, seed: Long, startTime: Long = 0L): List<Kline> {
        var price = 50_000.0
        var lcgSeed = seed
        val out = ArrayList<Kline>(bars)
        repeat(bars) { i ->
            lcgSeed = (lcgSeed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val drift = (lcgSeed % 2001 - 1000) / 100_000.0
            price *= (1.0 + drift)
            out.add(
                Kline(
                    startTime = startTime + i * 60_000L,
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

    private fun liveBarCloseAt(index: Int, klines: List<Kline>): AgentOrchestrator.LiveBarClose =
        AgentOrchestrator.LiveBarClose(
            barIndex = index,
            kline = klines[index],
            klinesSoFar = klines.subList(0, index + 1),
            depth = depthFor(klines[index].close),
        )

    private fun newOrderEmitter(account: StubPaperTradingAccount): PositionOrderEmitter = PositionOrderEmitter(
        orderSink = account,
        currentPosition = account::snapshot,
        maxPositionSizeBaseCoin = 1.0,
        maxNotionalUsdt = 1_000_000.0,
        referencePrice = { 50_000.0 },
    )

    private fun newOrchestrator(
        readoutTrainer: ReadoutTrainer = ReadoutTrainer(nHidden = nHidden),
        policyEngine: PolicyEngine = PolicyEngine(nHidden = nHidden, nBack = 5, learningRate = 0.01f, seed = 2L),
    ): AgentOrchestrator {
        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 7L)
        val reservoir = ReservoirEngine(weights)
        val reward = RewardEngine()
        return AgentOrchestrator(assembler, reservoir, readoutTrainer, reward, policyEngine)
    }

    // ---------------------------------------------------------------
    // (1) Market-data feed dropout
    // ---------------------------------------------------------------

    @Test
    fun `feed dropout - checkFeedHealth flattens and no exception escapes once the gap exceeds the bound`() {
        val account = StubPaperTradingAccount()
        account.seedOpenPosition(PositionSide.LONG, size = 0.25)
        val orderEmitter = newOrderEmitter(account)
        var now = 0L
        val guard = AgentFailureModeGuard(
            orchestrator = newOrchestrator(),
            orderEmitter = orderEmitter,
            clock = { now },
            maxFeedGapMs = 60_000L,
        )

        // One healthy bar establishes lastBarProcessedAtMs.
        val klines = fixtureKlines(bars = 1, seed = 11L, startTime = now)
        val state = AgentOrchestrator.LiveInferenceState()
        val processed = guard.onLiveBarClose(liveBarCloseAt(0, klines), state)
        assertTrue("first bar should process normally", processed is AgentFailureModeGuard.Outcome.Processed)

        // A health check well within the bound is a no-op, not a flatten.
        now += 10_000L
        val healthy = guard.checkFeedHealth(now)
        assertTrue(
            "a small gap should not be treated as a dropout",
            healthy is AgentFailureModeGuard.Outcome.NoOrderSent,
        )
        assertTrue("no order should have fired yet", account.calls.isEmpty())

        // Now the feed goes silent well past the bound - no new bar ever
        // arrives, so this can only be caught by the periodic health check,
        // not by onLiveBarClose.
        now += 10 * 60_000L
        val outcome = guard.checkFeedHealth(now)

        assertTrue(
            "a stale-beyond-bound gap must flatten, not throw or silently continue",
            outcome is AgentFailureModeGuard.Outcome.Flattened,
        )
        assertEquals(
            "the seeded long position should have been closed exactly once",
            listOf(StubPaperTradingAccount.Call("close", PositionSide.LONG)),
            account.calls,
        )
        assertNull("account should be flat after the dropout flatten", account.snapshot())
    }

    @Test
    fun `feed dropout - checkFeedHealth before any bar has ever processed is a safe no-op`() {
        val account = StubPaperTradingAccount()
        val orderEmitter = newOrderEmitter(account)
        val guard = AgentFailureModeGuard(
            orchestrator = newOrchestrator(),
            orderEmitter = orderEmitter,
            clock = { 999_999_999L },
            maxFeedGapMs = 1_000L,
        )

        val outcome = guard.checkFeedHealth()
        assertTrue(outcome is AgentFailureModeGuard.Outcome.NoOrderSent)
        assertTrue("no order should ever fire before any bar has been processed", account.calls.isEmpty())
    }

    // ---------------------------------------------------------------
    // (2) Stale klines
    // ---------------------------------------------------------------

    @Test
    fun `stale klines - a bar far behind wall clock is rejected before reaching the orchestrator, no order sent`() {
        val account = StubPaperTradingAccount()
        val orderEmitter = newOrderEmitter(account)
        val nowMs = 10 * 60 * 60_000L // "now" is 10 hours after epoch-relative bar times below
        val guard = AgentFailureModeGuard(
            orchestrator = newOrchestrator(),
            orderEmitter = orderEmitter,
            clock = { nowMs },
            maxKlineStalenessMs = 5 * 60_000L,
        )

        // Bar close time is far in the past relative to "now" - simulates a
        // buffering/cached/behind feed.
        val klines = fixtureKlines(bars = 1, seed = 21L, startTime = 0L)
        val state = AgentOrchestrator.LiveInferenceState()
        val outcome = guard.onLiveBarClose(liveBarCloseAt(0, klines), state)

        assertTrue(
            "a stale bar must be rejected as no-order-sent, not processed or flattened",
            outcome is AgentFailureModeGuard.Outcome.NoOrderSent,
        )
        assertTrue("no order of any kind should have fired for a stale bar", account.calls.isEmpty())
        assertTrue("the guard must not have latched shut over a merely transient staleness", !guard.isHalted)
    }

    @Test
    fun `stale klines - a fresh bar right after a stale one is processed normally, proving staleness is transient`() {
        val account = StubPaperTradingAccount()
        val orderEmitter = newOrderEmitter(account)
        var nowMs = 10 * 60 * 60_000L
        val guard = AgentFailureModeGuard(
            orchestrator = newOrchestrator(),
            orderEmitter = orderEmitter,
            clock = { nowMs },
            maxKlineStalenessMs = 5 * 60_000L,
        )

        val staleKlines = fixtureKlines(bars = 1, seed = 22L, startTime = 0L)
        val state = AgentOrchestrator.LiveInferenceState()
        val staleOutcome = guard.onLiveBarClose(liveBarCloseAt(0, staleKlines), state)
        assertTrue(staleOutcome is AgentFailureModeGuard.Outcome.NoOrderSent)

        // A bar whose close time is current is processed normally right after.
        val freshKlines = fixtureKlines(bars = 1, seed = 22L, startTime = nowMs)
        val freshOutcome = guard.onLiveBarClose(liveBarCloseAt(0, freshKlines), state)
        assertTrue(
            "a fresh bar right after a stale one should process normally, not stay rejected",
            freshOutcome is AgentFailureModeGuard.Outcome.Processed,
        )
    }

    // ---------------------------------------------------------------
    // (3) RLS divergence (covariance blow-up)
    // ---------------------------------------------------------------

    @Test
    fun `RLS divergence - a blown-up covariance matrix halts and flattens instead of reaching the order path`() {
        val account = StubPaperTradingAccount()
        account.seedOpenPosition(PositionSide.SHORT, size = 0.1)
        val orderEmitter = newOrderEmitter(account)

        // Construct a ReadoutTrainer whose covariance is already far past
        // any sane RLS regime - simulating the end state of covariance
        // windup (see AgentFailureModeGuard's class doc) without needing to
        // replay thousands of degenerate steps to get there.
        val regressorWidth = nHidden + 1 // +1 bias, ReadoutTrainer's default includeBias=true
        val blownUpCovariance = FloatArray(regressorWidth * regressorWidth).also { p ->
            for (i in 0 until regressorWidth) p[i * regressorWidth + i] = 1.0e9f
        }
        val divergedReadout = ReadoutTrainer(
            nHidden = nHidden,
            initialCovariance = blownUpCovariance,
        )

        val guard = AgentFailureModeGuard(
            orchestrator = newOrchestrator(readoutTrainer = divergedReadout),
            orderEmitter = orderEmitter,
            maxCovarianceMagnitude = 1.0e6f,
        )

        val klines = fixtureKlines(bars = 1, seed = 31L, startTime = 0L)
        val state = AgentOrchestrator.LiveInferenceState()
        val outcome = guard.onLiveBarClose(liveBarCloseAt(0, klines), state)

        assertTrue(
            "diverged RLS covariance must flatten, never hand the bar's raw position to the order path",
            outcome is AgentFailureModeGuard.Outcome.Flattened,
        )
        assertEquals(
            "the seeded short position should have been closed exactly once",
            listOf(StubPaperTradingAccount.Call("close", PositionSide.SHORT)),
            account.calls,
        )
        assertNull("account should be flat after the divergence flatten", account.snapshot())
        assertTrue("divergence is terminal - the guard must latch shut", guard.isHalted)

        // A subsequent, otherwise-healthy bar must stay suppressed - the
        // corrupted RLS state doesn't un-corrupt itself on the next tick.
        account.calls.clear()
        val nextKlines = fixtureKlines(bars = 2, seed = 31L, startTime = 60_000L)
        val next = guard.onLiveBarClose(liveBarCloseAt(1, nextKlines), state)
        assertTrue(
            "once halted for divergence, later bars must be suppressed, not silently processed again",
            next is AgentFailureModeGuard.Outcome.NoOrderSent,
        )
        assertTrue("no further orders should fire once halted", account.calls.isEmpty())
    }

    // ---------------------------------------------------------------
    // (4) NaN output from PolicyEngine
    // ---------------------------------------------------------------

    @Test
    fun `NaN policy output - halts and flattens instead of throwing out of PositionOrderEmitter`() {
        val account = StubPaperTradingAccount()
        account.seedOpenPosition(PositionSide.LONG, size = 0.4)
        val orderEmitter = newOrderEmitter(account)

        // A policy whose every weight is NaN guarantees a NaN z (any finite
        // regressor entry times a NaN weight is NaN under IEEE 754, and 0 *
        // NaN is NaN too), so f_t = tanh(NaN) = NaN on the very first step -
        // without this guard, that NaN would reach
        // PositionOrderEmitter.onTargetPosition and throw
        // IllegalArgumentException (require(NaN in -1f..1f) is false), an
        // unhandled exception on the live bar-close thread.
        val nRegressors = nHidden + 1 + PolicyEngine.DEFAULT_N_BACK + 1
        val corruptedPolicy = PolicyEngine(
            nHidden = nHidden,
            nBack = PolicyEngine.DEFAULT_N_BACK,
            initialWeights = FloatArray(nRegressors) { Float.NaN },
        )

        val guard = AgentFailureModeGuard(
            orchestrator = newOrchestrator(policyEngine = corruptedPolicy),
            orderEmitter = orderEmitter,
        )

        val klines = fixtureKlines(bars = 1, seed = 41L, startTime = 0L)
        val state = AgentOrchestrator.LiveInferenceState()

        // The whole point of this test: onLiveBarClose itself must not
        // throw, and its return value must be one of the two safe outcomes.
        val outcome = guard.onLiveBarClose(liveBarCloseAt(0, klines), state)

        assertTrue(
            "a NaN policy output must flatten, never propagate as an unhandled exception or a raw NaN order",
            outcome is AgentFailureModeGuard.Outcome.Flattened,
        )
        assertEquals(
            "the seeded long position should have been closed exactly once",
            listOf(StubPaperTradingAccount.Call("close", PositionSide.LONG)),
            account.calls,
        )
        assertNull("account should be flat after the NaN-output flatten", account.snapshot())
        assertTrue("a NaN policy output is terminal - the guard must latch shut", guard.isHalted)
    }

    @Test
    fun `NaN policy output - PositionOrderEmitter would have thrown directly, proving the guard is load-bearing`() {
        // Sanity check underpinning the test above: confirms
        // PositionOrderEmitter.onTargetPosition really does throw for NaN
        // on its own, so AgentFailureModeGuard's interception is
        // demonstrably necessary, not redundant with an already-safe
        // downstream default.
        val account = StubPaperTradingAccount()
        val orderEmitter = newOrderEmitter(account)

        var threw = false
        try {
            orderEmitter.onTargetPosition(Float.NaN)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("PositionOrderEmitter.onTargetPosition must reject NaN by throwing, not degrade silently", threw)
    }
}
