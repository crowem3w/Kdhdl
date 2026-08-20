package org.example.test.agent

import org.example.test.bitget.DepthLevel
import org.example.test.bitget.Kline
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * T urns raw market data already flowing through the app (candles, top-of-book
 * depth, and the agent's own position) into the fixed-size feature vector
 * [MlpQLearningPolicy] consumes.
 *
 * Every feature is squashed into roughly [-1, 1] with [tanh] so a small
 * linear model can learn from them directly, without a separate running-mean
 * / running-variance normalization pipeline to maintain online.
 */
object MarketFeatureExtractor {
    const val FEATURE_COUNT = 8

    /**
     * @param candles most-recent-last kline history (whatever [org.example.test.bitget.TradingChartPipeline]
     *   currently has buffered - only the trailing ~20 bars are actually used).
     * @param bids top-of-book bids, best first.
     * @param asks top-of-book asks, best first.
     * @param positionSideSign -1 short, 0 flat, +1 long - what the agent itself currently holds.
     * @param unrealizedPnlPercent unrealized PnL as a percent of margin on the agent's held position, 0 if flat.
     */
    fun extract(
        candles: List<Kline>,
        bids: List<DepthLevel>,
        asks: List<DepthLevel>,
        positionSideSign: Int,
        unrealizedPnlPercent: Double,
    ): DoubleArray {
        val closes = candles.map { it.close }
        val last = closes.lastOrNull() ?: 0.0

        fun returnOver(bars: Int): Double {
            if (closes.size <= bars || last == 0.0) return 0.0
            val past = closes[closes.size - 1 - bars]
            if (past == 0.0) return 0.0
            // x20 before squashing - a typical single-bar BTC move (a few
            // tenths of a percent) should already move the feature off zero
            // rather than needing a much larger move to register.
            return tanh(((last - past) / past) * 20.0)
        }

        val ret1 = returnOver(1)
        val ret5 = returnOver(5)
        val ret15 = returnOver(15)

        val recentReturns = closes.takeLast(20).zipWithNext { a, b -> if (a != 0.0) (b - a) / a else 0.0 }
        val volatility = if (recentReturns.size >= 2) {
            val mean = recentReturns.average()
            val variance = recentReturns.sumOf { (it - mean) * (it - mean) } / recentReturns.size
            tanh(sqrt(variance) * 50.0)
        } else {
            0.0
        }

        val bestBid = bids.firstOrNull()?.price ?: 0.0
        val bestAsk = asks.firstOrNull()?.price ?: 0.0
        val spread = if (bestBid > 0.0 && bestAsk > 0.0) {
            tanh(((bestAsk - bestBid) / bestAsk) * 200.0)
        } else {
            0.0
        }

        val bidVolume = bids.take(10).sumOf { it.size }
        val askVolume = asks.take(10).sumOf { it.size }
        val imbalance = if (bidVolume + askVolume > 0.0) {
            (bidVolume - askVolume) / (bidVolume + askVolume)
        } else {
            0.0
        }

        return doubleArrayOf(
            ret1,
            ret5,
            ret15,
            volatility,
            spread,
            imbalance,
            positionSideSign.toDouble(),
            tanh(unrealizedPnlPercent / 20.0),
        )
    }
}
