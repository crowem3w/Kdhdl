package org.example.syncora.agent

import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.Kline
import kotlin.math.sqrt

/**
 * Builds the fixed-width feature vector `u_t` (see the reference architecture
 * diagram / `docs/agent-design-contract.md`) that will eventually feed
 * [org.example.syncora.agent.ReservoirEngine]'s `W_input` (Phase 2). No
 * reservoir or model code exists yet - this class only assembles inputs.
 *
 * This is a pure, stateless-between-calls Kotlin class: every call to
 * [assemble] recomputes the vector fresh from the [Kline] history, the
 * [DepthSnapshot], and the wall-clock time handed to it. There is no hidden
 * internal state that could make two calls with identical arguments produce
 * different output, which is exactly the property the determinism test in
 * `FeatureAssemblerTest` checks. It also makes zero network calls; all three
 * data sources ([Kline] history, [DepthSnapshot], funding) are expected to
 * already be in memory by the time this is called (owned by
 * [org.example.syncora.bitget.TradingChartPipeline], [org.example.syncora.bitget.DepthMatrix],
 * and [FundingSchedule] respectively).
 *
 * ### Hot-path allocation
 * The only allocation per [assemble] call is the returned [FloatArray]
 * itself. Everything else - the return/volatility computation, the depth
 * walk - is done with index-based loops over the caller-owned [List]s
 * (`list[i]`, not `for (x in list)`) so no [Iterator] or list-view object is
 * created, and no intermediate array is needed since returns are recomputed
 * in a cheap second pass rather than cached in a scratch buffer.
 *
 * ### Funding rate / relative basis
 * Bitget's own funding rate is itself derived from the perpetual-vs-index
 * basis, and this codebase has no separate index-price feed (no
 * `IndexPriceClient` exists anywhere in `bitget/`). Rather than invent a new
 * network dependency inside a class that is required to make zero network
 * calls, [FeatureAssembler] takes the *current* funding rate as a plain
 * value via [fundingRateProvider] - the same "provider lambda reads an
 * already-fetched cached value" pattern [org.example.syncora.SyncoraApplication]
 * already uses for `markPriceProvider`, so no new network call is introduced
 * anywhere. The provider is called at most once per [assemble] call and must
 * not itself perform I/O.
 */
class FeatureAssembler(
    private val realizedVolWindow: Int = DEFAULT_REALIZED_VOL_WINDOW,
    private val depthLevelsForImbalance: Int = DEFAULT_DEPTH_LEVELS_FOR_IMBALANCE,
    private val fundingRateProvider: () -> Double? = { null },
) {

    companion object {
        /** Number of entries in every vector [assemble] returns. */
        const val FEATURE_WIDTH = 6

        const val RETURN_INDEX = 0
        const val REALIZED_VOL_INDEX = 1
        const val SPREAD_INDEX = 2
        const val ORDER_FLOW_IMBALANCE_INDEX = 3
        const val RELATIVE_BASIS_INDEX = 4
        const val TIME_TO_FUNDING_INDEX = 5

        const val DEFAULT_REALIZED_VOL_WINDOW = 20
        const val DEFAULT_DEPTH_LEVELS_FOR_IMBALANCE = 5
    }

    init {
        require(realizedVolWindow >= 2) { "realizedVolWindow must be >= 2, was $realizedVolWindow" }
        require(depthLevelsForImbalance >= 1) { "depthLevelsForImbalance must be >= 1, was $depthLevelsForImbalance" }
    }

    /**
     * Assembles `u_t` from [klines] (oldest first, same ordering as
     * [org.example.syncora.bitget.TradingChartPipeline.klines] /
     * [org.example.syncora.bitget.KlineBuffer.snapshot]), the current order
     * book [depth], and the wall-clock time [nowMs] used to compute
     * time-to-next-funding against [FundingSchedule].
     *
     * [klines] must contain at least one bar; returns a zero vector's worth
     * of price-derived features (return, realized vol) if fewer than two
     * bars are available yet, since a return needs a previous close - this
     * only happens transiently right after the pipeline cold-starts.
     */
    fun assemble(klines: List<Kline>, depth: DepthSnapshot, nowMs: Long): FloatArray {
        require(klines.isNotEmpty()) { "klines must not be empty" }

        val out = FloatArray(FEATURE_WIDTH)
        out[RETURN_INDEX] = lastReturn(klines).toFloat()
        out[REALIZED_VOL_INDEX] = realizedVolatility(klines).toFloat()
        out[SPREAD_INDEX] = relativeSpread(depth).toFloat()
        out[ORDER_FLOW_IMBALANCE_INDEX] = orderFlowImbalance(depth).toFloat()
        out[RELATIVE_BASIS_INDEX] = (fundingRateProvider() ?: 0.0).toFloat()
        out[TIME_TO_FUNDING_INDEX] = timeToNextFundingNormalized(nowMs).toFloat()
        return out
    }

    /** `(close_t - close_{t-1}) / close_{t-1}`, or 0.0 if there's no previous bar yet. */
    private fun lastReturn(klines: List<Kline>): Double {
        val n = klines.size
        if (n < 2) return 0.0
        val prevClose = klines[n - 2].close
        val close = klines[n - 1].close
        return if (prevClose != 0.0) (close - prevClose) / prevClose else 0.0
    }

    /**
     * Population standard deviation of simple bar-over-bar returns across
     * the trailing [realizedVolWindow] bars (fewer if not enough history
     * yet). Computed in two passes over [klines] by index so no return
     * array needs to be allocated or cached: pass one accumulates the mean,
     * pass two accumulates the variance against that mean.
     */
    private fun realizedVolatility(klines: List<Kline>): Double {
        val n = klines.size
        // Need at least 2 closes to form 1 return, so at most n-1 returns are available.
        val availableReturns = n - 1
        if (availableReturns < 1) return 0.0
        val count = minOf(availableReturns, realizedVolWindow)
        // Returns used are r_i = (close_i - close_{i-1}) / close_{i-1} for the last `count`
        // consecutive bar pairs, i.e. i in [n-count, n-1].
        val startIndex = n - count

        var sum = 0.0
        for (i in startIndex until n) {
            val prevClose = klines[i - 1].close
            val close = klines[i].close
            if (prevClose != 0.0) sum += (close - prevClose) / prevClose
        }
        val mean = sum / count

        var sumSquaredDiff = 0.0
        for (i in startIndex until n) {
            val prevClose = klines[i - 1].close
            val close = klines[i].close
            val r = if (prevClose != 0.0) (close - prevClose) / prevClose else 0.0
            val diff = r - mean
            sumSquaredDiff += diff * diff
        }
        return sqrt(sumSquaredDiff / count)
    }

    /** `(bestAsk - bestBid) / midPrice`, or 0.0 if either side of the book is empty. */
    private fun relativeSpread(depth: DepthSnapshot): Double {
        val bids = depth.bids
        val asks = depth.asks
        if (bids.isEmpty() || asks.isEmpty()) return 0.0
        val bestBid = bids[0].price
        val bestAsk = asks[0].price
        val mid = 0.5 * (bestBid + bestAsk)
        return if (mid != 0.0) (bestAsk - bestBid) / mid else 0.0
    }

    /**
     * `(bidSize - askSize) / (bidSize + askSize)` aggregated over the top
     * [depthLevelsForImbalance] levels per side (fewer if the book is
     * thinner than that). Positive means more resting size on the bid
     * (buy-side pressure); negative means more on the ask. 0.0 if the book
     * is empty on both sides.
     */
    private fun orderFlowImbalance(depth: DepthSnapshot): Double {
        val bids = depth.bids
        val asks = depth.asks

        var bidSize = 0.0
        val bidLevels = minOf(depthLevelsForImbalance, bids.size)
        for (i in 0 until bidLevels) bidSize += bids[i].size

        var askSize = 0.0
        val askLevels = minOf(depthLevelsForImbalance, asks.size)
        for (i in 0 until askLevels) askSize += asks[i].size

        val total = bidSize + askSize
        return if (total != 0.0) (bidSize - askSize) / total else 0.0
    }

    /**
     * Time to the next funding settlement per [FundingSchedule], normalized
     * to `[0, 1]` where 1.0 means funding just settled (a full 8h interval
     * remains) and 0.0 means settlement is right now.
     */
    private fun timeToNextFundingNormalized(nowMs: Long): Double {
        val next = FundingSchedule.nextSettlement(nowMs)
        val remaining = (next - nowMs).toDouble()
        return (remaining / FundingSchedule.INTERVAL_MS).coerceIn(0.0, 1.0)
    }
}
