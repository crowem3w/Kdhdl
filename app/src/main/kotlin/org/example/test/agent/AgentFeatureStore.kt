package org.example.test.agent

import org.example.test.bitget.DepthSnapshot
import org.example.test.bitget.Kline
import org.example.test.bitget.PublicTrade
import org.example.test.bitget.TickerSnapshot

/**
 * The "shared feature store" from design doc §7.4 - a single, thread-safe
 * place the ingestion service pushes every raw update into, and the only
 * place [MarketFeatureFrame]s get assembled from. Deliberately holds no
 * coroutines/sockets of its own (compare [org.example.test.bitget.KlineBuffer],
 * [org.example.test.bitget.DepthMatrix]): [AgentDataIngestionService] owns
 * the async wiring and just calls into this on every update plus once per
 * emission tick.
 *
 * Every `on*` method is called from whatever dispatcher the ingestion
 * service happens to be collecting on; [snapshot] can be called from
 * anywhere. A single lock over the handful of mutable fields is fine here
 * - this runs at market-tick rates (tens of Hz at most), not hot-path
 * order-book-matrix rates.
 */
class AgentFeatureStore(
    private val realizedVol5mBars: Int = 5,
    private val realizedVol1hBars: Int = 60,
    private val openInterestLookbackMs: Long = 15 * 60_000L,
    private val orderBookImbalanceLevels: Int = 10,
    tradeFlowWindowMs: Long = 60_000L,
) {
    private val lock = Any()

    private var latestKlines: List<Kline> = emptyList()
    private var latestDepth: DepthSnapshot? = null
    private var latestTicker: TickerSnapshot? = null

    private var klineUpdatedAtMs: Long? = null
    private var depthUpdatedAtMs: Long? = null
    private var tickerUpdatedAtMs: Long? = null

    private val tradeFlow = TradeFlowAggregator(tradeFlowWindowMs)
    private val openInterestHistory = OpenInterestHistory()

    /** [klinesOldestFirst] mirrors [org.example.test.bitget.KlineBuffer.snapshot]'s ordering. */
    fun onKlines(klinesOldestFirst: List<Kline>, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            latestKlines = klinesOldestFirst
            klineUpdatedAtMs = nowMs
        }
    }

    fun onDepth(depth: DepthSnapshot, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            latestDepth = depth
            depthUpdatedAtMs = nowMs
        }
    }

    fun onTicker(ticker: TickerSnapshot, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            latestTicker = ticker
            tickerUpdatedAtMs = nowMs
            ticker.openInterest?.let { oi -> openInterestHistory.record(ticker.timestampMs, oi) }
        }
    }

    fun onTrade(trade: PublicTrade) {
        tradeFlow.record(trade)
    }

    /** Builds the current [MarketFeatureFrame]. Safe to call on a fixed timer regardless of update cadence. */
    fun snapshot(nowMs: Long = System.currentTimeMillis(), staleThresholdMs: Long = 10_000L): MarketFeatureFrame {
        val klines: List<Kline>
        val depth: DepthSnapshot?
        val ticker: TickerSnapshot?
        val klineAt: Long?
        val depthAt: Long?
        val tickerAt: Long?
        synchronized(lock) {
            klines = latestKlines
            depth = latestDepth
            ticker = latestTicker
            klineAt = klineUpdatedAtMs
            depthAt = depthUpdatedAtMs
            tickerAt = tickerUpdatedAtMs
        }

        val lastKlineClose = klines.lastOrNull()?.close
        val depthMid = depth?.let { OrderBookImbalance.midPrice(it) }

        return MarketFeatureFrame(
            timestampMs = nowMs,
            lastPrice = ticker?.lastPrice ?: depthMid ?: lastKlineClose,
            markPrice = ticker?.markPrice,
            indexPrice = ticker?.indexPrice,
            basisBps = ticker?.basisBps,
            bestBid = depth?.bids?.firstOrNull()?.price ?: ticker?.bestBid,
            bestAsk = depth?.asks?.firstOrNull()?.price ?: ticker?.bestAsk,
            midPrice = depthMid,
            spreadBps = depth?.let { OrderBookImbalance.spreadBps(it) },
            orderBookImbalance = depth?.let { OrderBookImbalance.compute(it, orderBookImbalanceLevels) },
            openInterest = ticker?.openInterest,
            openInterestChangePct15m = openInterestHistory.changePct(openInterestLookbackMs, nowMs),
            fundingRate = ticker?.fundingRate,
            nextFundingTimeMs = ticker?.nextFundingTimeMs,
            tradeFlow = tradeFlow.snapshot(nowMs),
            realizedVol5m = RealizedVolatility.annualizedFromKlines(klines, realizedVol5mBars),
            realizedVol1h = RealizedVolatility.annualizedFromKlines(klines, realizedVol1hBars),
            klineBarCount = klines.size,
            quality = MarketFeatureFrame.DataQuality(
                klineAgeMs = klineAt?.let { nowMs - it },
                depthAgeMs = depthAt?.let { nowMs - it },
                tickerAgeMs = tickerAt?.let { nowMs - it },
                staleThresholdMs = staleThresholdMs,
            ),
        )
    }

    fun reset() {
        synchronized(lock) {
            latestKlines = emptyList()
            latestDepth = null
            latestTicker = null
            klineUpdatedAtMs = null
            depthUpdatedAtMs = null
            tickerUpdatedAtMs = null
        }
        tradeFlow.clear()
        openInterestHistory.clear()
    }
}
