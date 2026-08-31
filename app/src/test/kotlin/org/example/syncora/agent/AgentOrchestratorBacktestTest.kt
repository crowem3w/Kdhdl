package org.example.syncora.agent

import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5's end-to-end backtest (`ESN_RRL_Agent_Task_Prompts.md` Prompt 6):
 * replays the full chain ([FeatureAssembler] -> [ReservoirEngine] ->
 * [PolicyEngine] -> [RewardEngine], with [ReadoutTrainer] running
 * alongside as a diagnostics-only signal per gap-closure #2 - see
 * [AgentOrchestrator]'s class doc) via [AgentOrchestrator] over a
 * deterministic fixture history (same reproducible-fixture approach
 * `ReadoutBacktestTest` uses for Phase 3 - no I/O, no network, fully
 * reproducible across runs), and checks the two things this phase is
 * actually responsible for:
 *
 * 1. The full run has no `NaN`s and no divergence
 *    ([AgentOrchestrator.BacktestResult.stable]).
 * 2. The resulting trade frequency and drawdown look sane - no runaway
 *    over-trading, no absurd position flipping.
 *
 * The information ratio is reported (and printed) purely as the
 * "directional sanity check against the source paper's reported 1.46 IR"
 * Prompt 6 calls for - explicitly *not* asserted against any target value,
 * since matching the paper's exact performance on a short synthetic
 * fixture is explicitly not this phase's bar.
 */
class AgentOrchestratorBacktestTest {

    private val nHidden = 100

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

    @Test
    fun `full Phase 1-5 backtest replay runs end-to-end with no NaNs or divergence`() {
        val bars = 3000
        val klines = fixtureKlines(bars = bars, seed = 909090L)

        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 21L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, seed = 3L)

        val orchestrator = AgentOrchestrator(assembler, reservoir, readout, reward, policy)

        val result = orchestrator.runBacktest(
            klines = klines,
            depthAt = { t, k -> depthFor(k.close, seed = t.toLong()) },
            fundingRateAt = { nowMs -> if ((nowMs / 60_000L) % 480L == 0L) 0.0001 else 0.0 }, // occasional funding accrual
            feeRate = 0.0006,
        )

        println(
            "Phase 5 backtest: $bars bars, ${result.decisions.size} decisions, " +
                "meanReturn=${result.meanReturn}, stdReturn=${result.stdReturn}, " +
                "IR=${result.informationRatio}, trades=${result.tradeCount}, maxDrawdown=${result.maxDrawdown}",
        )

        // ---- exit criterion 1: no NaNs, no divergence ----
        assertTrue("expected the full chain to remain stable across the replay", result.stable)
        assertTrue("meanReturn should be finite", result.meanReturn.isFinite())
        assertTrue("stdReturn should be finite and non-negative", result.stdReturn.isFinite() && result.stdReturn >= 0.0)
        assertTrue("informationRatio should be finite", result.informationRatio.isFinite())
        assertTrue("maxDrawdown should be finite and non-negative", result.maxDrawdown.isFinite() && result.maxDrawdown >= 0.0)
        for (d in result.decisions) {
            assertTrue("position out of bounds at bar ${d.barIndex}: ${d.position}", d.position in -1f..1f)
            assertTrue("non-finite reward at bar ${d.barIndex}", d.reward.isFinite())
            assertTrue("non-finite dsr at bar ${d.barIndex}", d.differentialSharpe.isFinite())
        }

        // ---- exit criterion 2: trade frequency and drawdown look sane ----
        // Not a target metric - a smoke test against "runaway over-trading"
        // and "absurd position flipping": the policy shouldn't be forced to
        // trade every single bar by construction, and cumulative loss
        // shouldn't run away to a magnitude wildly disproportionate to the
        // per-bar reward scale.
        val tradeFraction = result.tradeCount.toDouble() / bars
        assertTrue(
            "trade fraction should not be pathologically saturated at ~100% of bars (was $tradeFraction)",
            tradeFraction < 0.95,
        )
        val meanAbsReward = result.returnSeries.map { kotlin.math.abs(it) }.average()
        assertTrue(
            "max drawdown should be a finite multiple of the per-bar reward scale, not an unbounded blowup " +
                "(maxDrawdown=${result.maxDrawdown}, meanAbsReward=$meanAbsReward)",
            meanAbsReward == 0.0 || result.maxDrawdown < meanAbsReward * bars,
        )
    }

    @Test
    fun `a flat policy (nBack = 0, near-zero EKF gain) still produces a stable, well-formed run`() {
        // A degenerate but well-defined configuration - checks the
        // orchestrator's own plumbing (not the policy's learning dynamics)
        // stays sane even when the policy is frozen near its small-random
        // initialization the whole run. A huge ridge penalty (beta) makes
        // the EKF's initial covariance P_0 = I/beta ~ 0, so every weight
        // delta stays near-zero for the whole run - the EKF-era equivalent
        // of the old gradient-ascent updater's near-zero learningRate.
        val bars = 500
        val klines = fixtureKlines(bars = bars, seed = 5150L)

        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 4L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden)
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 0, beta = 1e9f, seed = 1L)

        val orchestrator = AgentOrchestrator(assembler, reservoir, readout, reward, policy)
        val result = orchestrator.runBacktest(
            klines = klines,
            depthAt = { t, k -> depthFor(k.close, seed = t.toLong()) },
        )

        assertTrue(result.stable)
        assertTrue(result.decisions.size == bars)
    }

    // ---- gap-closure #2: readout is never part of the decision path ----

    @Test
    fun `AgentOrchestrator never calls ReadoutTrainer predict or update when diagnosticsOnly is false`() {
        // ReadoutTrainer itself has no call-counting hook, so this test
        // proves the point the way that matters for gap-closure #2: run a
        // full backtest with diagnosticsOnly = false and confirm every
        // DecisionLog.readoutForecast is NaN (the sentinel this class's doc
        // promises when the readout is never invoked) and that the
        // readout's own state (W_out, covariance) never moves from its
        // untouched construction-time value - the only way that can happen
        // is if predict()/update() were genuinely never called.
        val bars = 200
        val klines = fixtureKlines(bars = bars, seed = 424242L)

        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 8L)
        val reservoir = ReservoirEngine(weights)
        val readout = ReadoutTrainer(nHidden = nHidden)
        val untouchedWOut = readout.wOutSnapshot()
        val untouchedCovariance = readout.covarianceSnapshot()
        val reward = RewardEngine()
        val policy = PolicyEngine(nHidden = nHidden, nBack = 5, seed = 3L)

        val orchestrator = AgentOrchestrator(assembler, reservoir, readout, reward, policy, diagnosticsOnly = false)
        val result = orchestrator.runBacktest(
            klines = klines,
            depthAt = { t, k -> depthFor(k.close, seed = t.toLong()) },
        )

        assertTrue("run should still be stable with the readout disabled", result.stable)
        for (d in result.decisions) {
            assertTrue(
                "readoutForecast should be NaN at bar ${d.barIndex} when diagnosticsOnly = false",
                d.readoutForecast.isNaN(),
            )
            assertTrue("position should still be well-formed without the readout", d.position in -1f..1f)
        }
        assertTrue(
            "W_out must be untouched (never updated) when diagnosticsOnly = false",
            readout.wOutSnapshot().toList() == untouchedWOut.toList(),
        )
        assertTrue(
            "RLS covariance must be untouched (never updated) when diagnosticsOnly = false",
            readout.covarianceSnapshot().toList() == untouchedCovariance.toList(),
        )
    }

    @Test
    fun `PolicyEngine decision is identical whether or not a readout is wired in, given the same reservoir`() {
        // Structural proof that the readout cannot influence f_t: two
        // orchestrators sharing the same reservoir weights/seed and policy
        // seed, one with diagnosticsOnly = true (readout runs and is
        // logged) and one with diagnosticsOnly = false (readout never
        // runs), must produce bit-identical positions bar for bar - the
        // only way that could fail is if the forecast were leaking into
        // PolicyEngine somehow.
        val bars = 150
        val klines = fixtureKlines(bars = bars, seed = 13131L)
        fun freshChain(diagnosticsOnly: Boolean): AgentOrchestrator {
            val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 8L)
            return AgentOrchestrator(
                featureAssembler = FeatureAssembler(),
                reservoir = ReservoirEngine(weights),
                readoutTrainer = ReadoutTrainer(nHidden = nHidden),
                rewardEngine = RewardEngine(),
                policyEngine = PolicyEngine(nHidden = nHidden, nBack = 5, seed = 3L),
                diagnosticsOnly = diagnosticsOnly,
            )
        }

        val withDiagnostics = freshChain(diagnosticsOnly = true).runBacktest(
            klines = klines,
            depthAt = { t, k -> depthFor(k.close, seed = t.toLong()) },
        )
        val withoutDiagnostics = freshChain(diagnosticsOnly = false).runBacktest(
            klines = klines,
            depthAt = { t, k -> depthFor(k.close, seed = t.toLong()) },
        )

        for (i in withDiagnostics.decisions.indices) {
            assertTrue(
                "position at bar $i must match regardless of diagnosticsOnly - the readout must not influence f_t",
                withDiagnostics.decisions[i].position == withoutDiagnostics.decisions[i].position,
            )
        }
    }
}
