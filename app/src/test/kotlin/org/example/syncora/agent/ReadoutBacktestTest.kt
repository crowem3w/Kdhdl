package org.example.syncora.agent

import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.Kline
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Phase 3's backtest script: replays a deterministic stand-in for a
 * `KlineCacheStore` dump (same fixture-generation approach
 * `FeatureAssemblerTest`/`ReservoirEngineTest` already use for their own
 * longer fixtures - a reproducible pseudo-random walk, not a network call
 * or a real cached file) through the full
 * [FeatureAssembler] -> [ReservoirEngine] -> [ReadoutTrainer] chain, bar
 * close by bar close, and reports basic predictive metrics (correlation,
 * hit-rate) against next-bar return.
 *
 * This is explicitly *not* a P&L backtest - there is no position, no
 * reward, no [RewardEngine] wired in yet (that's Phase 4). It only checks
 * the two things Phase 3 is actually responsible for: that the RLS update
 * stays numerically stable across a full replay of history, and that the
 * chain produces a sane one-step-ahead forecast signal to hand off to
 * later phases - not that the signal is already profitable.
 */
class ReadoutBacktestTest {

    private val nHidden = 100

    // ---- fixture: a deterministic stand-in for a cached kline/depth history ----
    //
    // Same linear-congruential approach as FeatureAssemblerTest's
    // longKlineFixture: fully reproducible across runs/processes, no I/O.

    private fun replayFixture(bars: Int, seed: Long): List<Kline> {
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
        val jitter = ((seed % 97) - 48) / 480_000.0 // deterministic small size skew
        val spread = close * 0.0004
        return DepthSnapshot(
            bids = listOf(DepthLevel(close - spread / 2, 2.0 + jitter), DepthLevel(close - spread, 3.0)),
            asks = listOf(DepthLevel(close + spread / 2, 2.0 - jitter), DepthLevel(close + spread, 3.0)),
            lastUpdateMs = 0L,
            lastSeq = 1L,
        )
    }

    @Test
    fun `full Phase 1-2-3 chain replay is stable and reports predictive metrics`() {
        val bars = 4000
        val allKlines = replayFixture(bars = bars, seed = 424242L)

        val assembler = FeatureAssembler()
        val weights = ReservoirWeights.randomWeights(nInput = FeatureAssembler.FEATURE_WIDTH, nHidden = nHidden, seed = 13L)
        val reservoir = ReservoirEngine(weights)
        val trainer = ReadoutTrainer(nHidden = nHidden, forgettingFactor = 0.995f)

        var previousState: FloatArray? = null
        val predictions = ArrayList<Float>()
        val actuals = ArrayList<Float>()

        for (t in allKlines.indices) {
            val klinesSoFar = allKlines.subList(0, t + 1)
            val depth = depthFor(allKlines[t].close, seed = t.toLong())
            val u = assembler.assemble(klinesSoFar, depth, nowMs = allKlines[t].startTime)
            val state = reservoir.step(u).copyOf()

            val prior = previousState
            if (prior != null) {
                val prevClose = allKlines[t - 1].close
                val actualReturn = ((allKlines[t].close - prevClose) / prevClose).toFloat()

                // Predict *before* training on this bar's outcome - a
                // genuine one-step-ahead forecast, not hindsight.
                val predicted = trainer.predict(prior)[0]
                predictions.add(predicted)
                actuals.add(actualReturn)

                trainer.update(prior, floatArrayOf(actualReturn))

                assertTrue(
                    "RLS diverged at bar $t (non-finite W_out or covariance entry)",
                    trainer.isStable(),
                )
            }
            previousState = state
        }

        val correlation = pearsonCorrelation(predictions, actuals)
        val hitRate = hitRate(predictions, actuals)

        println(
            "Phase 3 backtest replay: $bars bars, ${predictions.size} scored predictions, " +
                "correlation=$correlation, hitRate=$hitRate",
        )

        // Sanity, not a target: Phase 4/5 own whether this signal is
        // actually profitable. Here we only assert the metrics themselves
        // came out well-formed and the readout never blew up.
        assertTrue("correlation should be a finite number, got $correlation", correlation.isFinite())
        assertTrue("hit-rate should be a finite number, got $hitRate", hitRate.isFinite())
        assertTrue("hit-rate should be a valid fraction, got $hitRate", hitRate in 0.0..1.0)
        assertTrue("expected the readout to still be stable at the end of the replay", trainer.isStable())
    }

    private fun pearsonCorrelation(a: List<Float>, b: List<Float>): Double {
        require(a.size == b.size)
        val n = a.size
        if (n < 2) return Double.NaN
        val meanA = a.sumOf { it.toDouble() } / n
        val meanB = b.sumOf { it.toDouble() } / n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        val denom = sqrt(varA) * sqrt(varB)
        return if (denom == 0.0) 0.0 else cov / denom
    }

    private fun hitRate(predictions: List<Float>, actuals: List<Float>): Double {
        require(predictions.size == actuals.size)
        if (predictions.isEmpty()) return Double.NaN
        var hits = 0
        for (i in predictions.indices) {
            val predictedSign = predictions[i] >= 0f
            val actualSign = actuals[i] >= 0f
            if (predictedSign == actualSign) hits++
        }
        return hits.toDouble() / predictions.size
    }
}
