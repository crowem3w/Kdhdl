package org.example.syncora.rrl

import org.example.syncora.bitget.BookSide
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.DepthUpdate
import org.example.syncora.bitget.FundingRateInfo
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PublicTrade
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Builds u_t, the external input vector to [EchoStateReservoir] (section
 * III-B1: "such external input would include order book, transaction and
 * funding information"), from the raw market data the app already streams
 * in via [org.example.syncora.bitget.BitgetKlineSocket],
 * [org.example.syncora.bitget.DepthPipeline] (checksum-verified top of
 * book, preferred over raw [org.example.syncora.bitget.BitgetDepthSocket]
 * deltas), [org.example.syncora.bitget.BitgetTradeSocket] and
 * [org.example.syncora.bitget.BitgetFundingRateClient].
 *
 * Feature layout (index -> meaning), [FEATURE_COUNT] wide:
 *  0: bias (1.0)
 *  1: 1-bar log return of the kline close
 *  2: 5-bar log return
 *  3: 20-bar log return
 *  4: rolling realised volatility of 1-bar log returns (window: [VOLATILITY_WINDOW])
 *  5: relative bid/ask spread, (ask - bid) / mid
 *  6: top-of-book order imbalance, (bidSize - askSize) / (bidSize + askSize)
 *  7: current funding rate
 *  8: normalised time until the next funding settlement, in [0, 1]
 *  9: volume z-score of the current bar's base volume vs its rolling mean/std
 * 10: taker trade-flow imbalance over the bar, (buyVolume - sellVolume) / (buyVolume + sellVolume)
 * 11: z-score of total taker volume over the bar vs its rolling mean/std
 */
class RrlFeatureExtractor(private val historyWindow: Int = 64) {

    private val closes: ArrayDeque<Double> = ArrayDeque()
    private val volumes: ArrayDeque<Double> = ArrayDeque()
    private val tradeFlowTotals: ArrayDeque<Double> = ArrayDeque()

    private var bestBid: Double? = null
    private var bestAsk: Double? = null
    private var bestBidSize: Double = 0.0
    private var bestAskSize: Double = 0.0
    private var latestFundingRate: Double = 0.0
    private var latestFundingTimestampMs: Long = 0L

    // Accumulated since the last onKline() call, i.e. taker activity during the bar
    // currently forming; captured and reset once that bar closes.
    private var buyVolumeAccum: Double = 0.0
    private var sellVolumeAccum: Double = 0.0
    private var lastBarNetFlow: Double = 0.0
    private var lastBarTotalFlow: Double = 0.0

    fun onKline(kline: Kline) {
        closes.addLast(kline.close)
        volumes.addLast(kline.baseVolume)
        while (closes.size > historyWindow) closes.removeFirst()
        while (volumes.size > historyWindow) volumes.removeFirst()

        // The trade flow accumulated up to this point belongs to the bar that just
        // closed; capture it, then start accumulating for the next bar.
        lastBarNetFlow = buyVolumeAccum - sellVolumeAccum
        lastBarTotalFlow = buyVolumeAccum + sellVolumeAccum
        tradeFlowTotals.addLast(lastBarTotalFlow)
        while (tradeFlowTotals.size > historyWindow) tradeFlowTotals.removeFirst()
        buyVolumeAccum = 0.0
        sellVolumeAccum = 0.0
    }

    /** Order book top of book from a raw, unmerged [DepthUpdate] delta. */
    fun onDepthUpdate(update: DepthUpdate) {
        update.bids.firstOrNull()?.let { bestBid = it.price; bestBidSize = it.size }
        update.asks.firstOrNull()?.let { bestAsk = it.price; bestAskSize = it.size }
    }

    /**
     * Order book top of book from a checksum-verified, fully merged
     * [DepthSnapshot] (see [org.example.syncora.bitget.DepthPipeline]).
     * Preferred over [onDepthUpdate] when a maintained order book matrix is
     * available, since a single raw delta message does not necessarily
     * touch the best bid or ask.
     */
    fun onDepthSnapshot(snapshot: DepthSnapshot) {
        snapshot.bids.firstOrNull()?.let { bestBid = it.price; bestBidSize = it.size }
        snapshot.asks.firstOrNull()?.let { bestAsk = it.price; bestAskSize = it.size }
    }

    /** Accumulates a public trade print into the current bar's taker flow. */
    fun onTrade(trade: PublicTrade) {
        if (trade.side == BookSide.BID) buyVolumeAccum += trade.size else sellVolumeAccum += trade.size
    }

    fun onFundingRate(info: FundingRateInfo) {
        latestFundingRate = info.fundingRate
        latestFundingTimestampMs = info.fetchedAt
    }

    /** Best bid/ask known so far, used by the agent to compute Delta p_t and delta_t (eq. 8, 9). */
    fun currentBidAsk(): Pair<Double, Double>? {
        val bid = bestBid ?: return null
        val ask = bestAsk ?: return null
        return bid to ask
    }

    /**
     * Returns true once enough history has accumulated for every feature to
     * be meaningful (rather than zero-padded); mirrors an echo state
     * network needing to be "driven for long enough" to wash out its
     * initial state before its output is trustworthy (section II-B).
     */
    fun isWarmedUp(): Boolean = closes.size > LONG_RETURN_WINDOW && bestBid != null && bestAsk != null

    /** Builds u_t for the current bar at [nowMs]. Call after [onKline] for this bar. */
    fun buildInput(nowMs: Long): DoubleArray {
        val features = DoubleArray(FEATURE_COUNT)
        features[0] = 1.0 // bias

        val closeList = closes
        val n = closeList.size
        features[1] = logReturn(closeList, n, 1)
        features[2] = logReturn(closeList, n, SHORT_RETURN_WINDOW)
        features[3] = logReturn(closeList, n, LONG_RETURN_WINDOW)
        features[4] = realizedVolatility(closeList)

        val bid = bestBid
        val ask = bestAsk
        if (bid != null && ask != null && bid > 0.0) {
            val mid = 0.5 * (bid + ask)
            features[5] = if (mid > 0.0) (ask - bid) / mid else 0.0
        }

        val sizeSum = bestBidSize + bestAskSize
        features[6] = if (sizeSum > 1e-12) (bestBidSize - bestAskSize) / sizeSum else 0.0

        features[7] = latestFundingRate
        features[8] = normalisedTimeToFunding(nowMs)
        features[9] = volumeZScore()
        features[10] = if (lastBarTotalFlow > 1e-12) lastBarNetFlow / lastBarTotalFlow else 0.0
        features[11] = tradeFlowZScore()

        return features
    }

    private fun logReturn(closeList: ArrayDeque<Double>, n: Int, lag: Int): Double {
        if (n <= lag) return 0.0
        val recent = closeList[n - 1]
        val past = closeList[n - 1 - lag]
        if (recent <= 0.0 || past <= 0.0) return 0.0
        return ln(recent / past)
    }

    private fun realizedVolatility(closeList: ArrayDeque<Double>): Double {
        val n = closeList.size
        val window = minOf(VOLATILITY_WINDOW, n - 1)
        if (window < 2) return 0.0
        val returns = DoubleArray(window) { i ->
            val idx = n - 1 - i
            val recent = closeList[idx]
            val past = closeList[idx - 1]
            if (recent > 0.0 && past > 0.0) ln(recent / past) else 0.0
        }
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private fun normalisedTimeToFunding(nowMs: Long): Double {
        if (nowMs <= 0L) return 0.0
        val next = FundingSchedule.nextSettlement(nowMs)
        val previous = FundingSchedule.previousSettlement(nowMs)
        val span = (next - previous).toDouble()
        if (span <= 0.0) return 0.0
        return ((next - nowMs).toDouble() / span).coerceIn(0.0, 1.0)
    }

    private fun volumeZScore(): Double {
        val n = volumes.size
        if (n < 2) return 0.0
        val mean = volumes.average()
        val variance = volumes.sumOf { (it - mean) * (it - mean) } / n
        val std = sqrt(variance.coerceAtLeast(0.0))
        if (std < 1e-12) return 0.0
        return (volumes.last() - mean) / std
    }

    private fun tradeFlowZScore(): Double {
        val n = tradeFlowTotals.size
        if (n < 2) return 0.0
        val mean = tradeFlowTotals.average()
        val variance = tradeFlowTotals.sumOf { (it - mean) * (it - mean) } / n
        val std = sqrt(variance.coerceAtLeast(0.0))
        if (std < 1e-12) return 0.0
        return (tradeFlowTotals.last() - mean) / std
    }

    companion object {
        const val FEATURE_COUNT = 12
        private const val SHORT_RETURN_WINDOW = 5
        private const val LONG_RETURN_WINDOW = 20
        private const val VOLATILITY_WINDOW = 20
    }
}
