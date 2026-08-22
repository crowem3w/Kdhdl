package org.example.test.agent

import org.example.test.bitget.DepthSnapshot

/**
 * Top-of-book pressure: how much resting size sits on the bid vs the ask
 * within the first [levels] price levels on each side. In [-1, 1] - positive
 * means more resting size on the bid (buy-side pressure), matching the same
 * sign convention as [TradeFlowAggregator.Snapshot.imbalance] so the two can
 * be compared directly in [MarketFeatureFrame].
 */
object OrderBookImbalance {
    fun compute(depth: DepthSnapshot, levels: Int = 10): Double? {
        val bidSize = depth.bids.take(levels).sumOf { it.size }
        val askSize = depth.asks.take(levels).sumOf { it.size }
        val total = bidSize + askSize
        if (total <= 0.0) return null
        return (bidSize - askSize) / total
    }

    fun midPrice(depth: DepthSnapshot): Double? {
        val bid = depth.bids.firstOrNull()?.price ?: return null
        val ask = depth.asks.firstOrNull()?.price ?: return null
        return (bid + ask) / 2.0
    }

    fun spreadBps(depth: DepthSnapshot): Double? {
        val bid = depth.bids.firstOrNull()?.price ?: return null
        val ask = depth.asks.firstOrNull()?.price ?: return null
        if (bid <= 0.0) return null
        return (ask - bid) / bid * 10_000.0
    }
}
