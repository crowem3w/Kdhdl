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

    
    
    private var buyVolumeAccum: Double = 0.0
    private var sellVolumeAccum: Double = 0.0
    private var lastBarNetFlow: Double = 0.0
    private var lastBarTotalFlow: Double = 0.0

    fun onKline(kline: Kline) {
        closes.addLast(kline.close)
        volumes.addLast(kline.baseVolume)
        while (closes.size > historyWindow) closes.removeFirst()
        while (volumes.size > historyWindow) volumes.removeFirst()

        
        
        lastBarNetFlow = buyVolumeAccum - sellVolumeAccum
        lastBarTotalFlow = buyVolumeAccum + sellVolumeAccum
        tradeFlowTotals.addLast(lastBarTotalFlow)
        while (tradeFlowTotals.size > historyWindow) tradeFlowTotals.removeFirst()
        buyVolumeAccum = 0.0
        sellVolumeAccum = 0.0
    }

    
    fun onDepthUpdate(update: DepthUpdate) {
        update.bids.firstOrNull()?.let { bestBid = it.price; bestBidSize = it.size }
        update.asks.firstOrNull()?.let { bestAsk = it.price; bestAskSize = it.size }
    }

    






    fun onDepthSnapshot(snapshot: DepthSnapshot) {
        snapshot.bids.firstOrNull()?.let { bestBid = it.price; bestBidSize = it.size }
        snapshot.asks.firstOrNull()?.let { bestAsk = it.price; bestAskSize = it.size }
    }

    
    fun onTrade(trade: PublicTrade) {
        if (trade.side == BookSide.BID) buyVolumeAccum += trade.size else sellVolumeAccum += trade.size
    }

    fun onFundingRate(info: FundingRateInfo) {
        latestFundingRate = info.fundingRate
        latestFundingTimestampMs = info.fetchedAt
    }

    
    fun currentBidAsk(): Pair<Double, Double>? {
        val bid = bestBid ?: return null
        val ask = bestAsk ?: return null
        return bid to ask
    }

    





    fun isWarmedUp(): Boolean = closes.size > LONG_RETURN_WINDOW && bestBid != null && bestAsk != null

    
    fun buildInput(nowMs: Long): DoubleArray {
        val features = DoubleArray(FEATURE_COUNT)
        features[0] = 1.0 

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