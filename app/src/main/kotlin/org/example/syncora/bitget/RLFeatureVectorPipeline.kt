package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.ln

/**
 * One time-step of the external data the RL agent's echo-state reservoir
 * consumes, per `recurrent-reinforcement-learning-crypto-agent.md`
 * (Borrageiro, Firoozye, Barucca - IEEE Access, 2022) Appendix A and
 * §3.2.1, cross-referenced against design doc §9's mapping table.
 *
 * This class is deliberately just the *data* side of that mapping -
 * [uT], [deltaP], [executionCostLong]/[executionCostShort], [kappaT], and
 * [yHat] are exactly the quantities Appendix A calls u_t, Δp_t, δ_t, κ_t,
 * and ŷ_t. Building and training the echo state network itself
 * (W^input, W^hidden, W^back, the tanh reservoir recurrence in §3.2.1) and
 * the direct-RL readout (w_out, the EKF update in the paper's Algorithm 1)
 * is a distinct model-training concern that consumes this pipeline's
 * output - it is not implemented here. See [EchoStateReservoir] and
 * [DirectRLReadout] respectively.
 */
data class RLFeatureSample(
    val timestampMs: Long,

    /** u_t - external input vector (order-book, trade, and funding features). See [RLFeatureVectorPipeline] companion for index layout. */
    val uT: List<Float>,

    /**
     * Δp_t - mid-price change since the previous sample (paper Eq. 8's
     * price term): `0.5 * (bid_t + ask_t - bid_{t-1} - ask_{t-1})`. Zero
     * on the very first sample, since there is no previous mid yet.
     */
    val deltaP: Double,

    /**
     * δ_t - the price-taker execution cost a marketable order of
     * `referenceOrderSize` would incur *right now*: the live-book slippage
     * (see [OrderBookWalker]) *plus* the taker exchange fee (paper §3.3:
     * spread cost + 5bps exchange fee), rather than either a flat
     * half-spread or slippage alone - design doc §9 is explicit that this
     * pipeline should source the spread portion of δ_t by walking the book,
     * and the paper's own experiment design (§3.3) is explicit that δ_t
     * also includes the exchange's taker fee. Always a non-negative price
     * quantity (same units as [deltaP]). Null only if the relevant side of
     * the book isn't primed yet.
     *
     * Both directions are reported because a feature snapshot is taken
     * before the agent has chosen f_t; a consumer computing a realized
     * r_t (Eq. 8) should pick [executionCostLong] or [executionCostShort]
     * to match the sign of that step's Δf_t.
     */
    val executionCostLong: Double?,
    val executionCostShort: Double?,

    /** κ_t - the current (not-yet-settled) funding rate, Eq. 4 / design doc §7. Null until the first funding-rate refresh lands. */
    val kappaT: Double?,

    /**
     * ŷ_t - feedback vector of the readout's own last `n_back` *gated*
     * positions (Eq. 11), oldest first, zero-padded until that much real
     * history exists. Per Eq. 11, this is the model's own past output
     * `[f_{t-n_back}, ..., f_{t-1}]`, not realized account state - it's
     * sourced from [DirectRLPositionPipeline] via [rawPositionProvider],
     * which pushes each tick's `gatedPosition` (not `targetPosition`, and
     * not a fill or balance read) back into this pipeline after
     * [DirectRLReadout.step] runs. `gatedPosition` (not the raw target) is
     * used so this self-referential feedback matches what the model
     * actually held/traded that tick (see [DirectRLReadout]'s Fix 4 note),
     * consistent with the funding/cost terms Eq. 8 charges against the
     * same gated position. Defaults to `0f` before the first decision
     * exists (e.g. before [DirectRLPositionPipeline] has started, or on
     * the very first ticks of a fresh run).
     */
    val yHat: List<Float>,
)

/**
 * Assembles [RLFeatureSample]s from the market-data pipelines this app
 * already runs for the chart and order-book UI (design doc §9's mapping
 * table), so the RL agent's inputs come from the same live feeds as
 * everything else instead of a separate, possibly-diverging data path.
 *
 * Every input here is read off pipelines that already exist for other
 * consumers:
 *  - [chartPipeline] / [depthPipeline] - the same kline and L2 depth state
 *    the chart and depth-heatmap UI render.
 *  - [tradeSocket] - the same public trade-print stream
 *    [QueuePositionTracker] already consumes.
 *  - [fundingRateProvider] - typically `{ paperTradingRepository.currentFunding.value }`
 *    or the live-trading equivalent, so κ_t matches whatever the trading
 *    engine is actually accruing against open positions.
 *  - [feeRateProvider] - typically `{ paperTradingRepository.feeRates.value }`
 *    or the live-trading equivalent, so δ_t's fee component matches
 *    whatever the trading engine would actually be charged.
 *  - [rawPositionProvider] - the readout's own last `gatedPosition`
 *    (Eq. 11's ŷ_t is the model's own past output, not realized account
 *    state - see [RLFeatureSample.yHat] and [DirectRLReadout]'s Fix 4
 *    note); wired to `DirectRLPositionPipeline.lastGatedPosition` once
 *    that pipeline exists, defaulting to always-0f otherwise so this
 *    pipeline is still usable standalone (e.g. in tests) before a readout
 *    is in the loop.
 *
 * This does not own or start/stop [chartPipeline], [depthPipeline], or
 * [tradeSocket] - those have their own lifecycles (see
 * `SyncoraApplication`); this pipeline only subscribes to their existing
 * `StateFlow`s/`SharedFlow`s.
 */
class RLFeatureVectorPipeline(
    private val chartPipeline: TradingChartPipeline,
    private val depthPipeline: DepthPipeline,
    private val tradeSocket: BitgetTradeSocket,
    private val fundingRateProvider: () -> FundingRateInfo?,
    // Taker fee rate a marketable order would actually be charged - see
    // [RLFeatureSample.executionCostLong]/[executionCostShort]. Same
    // "read it, never assume it" pattern as [fundingRateProvider]; falls
    // back to [DEFAULT_TAKER_FEE_RATE] (the paper's own 5bps) whenever the
    // live rate isn't known yet, so training never silently reverts to a
    // zero fee.
    private val feeRateProvider: () -> FeeRates?,
    // ŷ_t's feedback source (Fix 2, Option A) - the readout's own last
    // gated position, *not* realized account state. Defaults to always-0f
    // (flat) so a caller that hasn't wired a readout yet still gets valid,
    // if uninteresting, samples rather than a crash.
    private val rawPositionProvider: () -> Float = { 0f },
    // Order size (base coin) used to probe execution cost via
    // [OrderBookWalker] - should roughly track the size the trading engine
    // actually trades, since slippage is size-dependent.
    private val referenceOrderSize: Double = 0.01,
    // How far back "recent" public trades reach for the trade-flow
    // imbalance feature (index [IDX_TRADE_FLOW_IMBALANCE]).
    private val tradeFlowWindowMs: Long = 60_000L,
    // n_back in the paper (§3.3: 10) - how many of the agent's own past
    // positions feed back into ŷ_t.
    private val nBack: Int = 10,
    // How many recent bars the volume-normalisation feature averages over.
    private val volumeAverageBars: Int = 20,
    // How many price levels per side count toward the order-book-imbalance feature.
    private val bookImbalanceLevels: Int = 10,
) {
    companion object {
        private const val TAG = "RLFeatureVectorPipeline"

        /**
         * Fallback taker fee rate used when [feeRateProvider] hasn't
         * returned live data yet - the paper's own stated 5bps (§3.3:
         * "cost = Eq. (9) [half-spread] + 5 bps exchange fee"), so the
         * training cost signal never silently drops the fee component to
         * zero just because a live fee refresh hasn't landed.
         */
        const val DEFAULT_TAKER_FEE_RATE: Double = 0.0005

        // u_t layout - keep in sync with buildFeatureVector().
        const val IDX_KLINE_RETURN = 0
        const val IDX_KLINE_RANGE = 1
        const val IDX_KLINE_VOLUME_REL = 2
        const val IDX_BOOK_IMBALANCE = 3
        const val IDX_RELATIVE_SPREAD = 4
        const val IDX_LIQUIDITY_SHELF_SKEW = 5
        const val IDX_TRADE_FLOW_IMBALANCE = 6
        const val IDX_FUNDING_RATE = 7
        const val FEATURE_COUNT = 8

        /** Human-readable names for [RLFeatureSample.uT], in index order - for logging/debugging, never for training. */
        val FEATURE_NAMES = listOf(
            "kline_log_return",
            "kline_range_pct",
            "kline_volume_relative",
            "book_imbalance",
            "relative_spread",
            "liquidity_shelf_skew",
            "trade_flow_imbalance",
            "funding_rate",
        )
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in RLFeatureVectorPipeline coroutine scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var combineJob: Job? = null
    private var tradeFlowJob: Job? = null

    private val _featureVector = MutableStateFlow<RLFeatureSample?>(null)
    val featureVector: StateFlow<RLFeatureSample?> = _featureVector.asStateFlow()

    private val historyLock = Any()
    private val recentTrades = ArrayDeque<PublicTrade>()
    private val positionHistory = ArrayDeque<Float>()

    @Volatile
    private var previousMid: Double? = null

    fun start() {
        stop()
        previousMid = null
        synchronized(historyLock) {
            recentTrades.clear()
            positionHistory.clear()
            repeat(nBack) { positionHistory.addLast(0f) }
        }

        tradeFlowJob = tradeSocket.trades
            .onEach { trade -> onTradePrinted(trade) }
            .catch { e -> Log.e(TAG, "Error processing trade print; dropping", e) }
            .launchIn(scope)

        combineJob = combine(
            chartPipeline.klines,
            depthPipeline.depth,
            depthPipeline.liquidityShelves,
        ) { klines, depth, shelves -> buildSample(klines, depth, shelves) }
            .onEach { sample -> if (sample != null) _featureVector.value = sample }
            .catch { e -> Log.e(TAG, "Error building RL feature sample; dropping tick", e) }
            .launchIn(scope)
    }

    fun stop() {
        combineJob?.cancel()
        tradeFlowJob?.cancel()
    }

    private fun onTradePrinted(trade: PublicTrade) {
        synchronized(historyLock) {
            recentTrades.addLast(trade)
            val cutoff = trade.timestampMs - tradeFlowWindowMs
            while (recentTrades.isNotEmpty() && recentTrades.first().timestampMs < cutoff) {
                recentTrades.removeFirst()
            }
        }
    }

    private fun buildSample(
        klines: List<Kline>,
        depth: DepthSnapshot,
        shelves: List<LiquidityShelf>,
    ): RLFeatureSample? {
        val lastBar = klines.lastOrNull() ?: return null
        val bestBid = depth.bids.firstOrNull() ?: return null
        val bestAsk = depth.asks.firstOrNull() ?: return null
        val mid = 0.5 * (bestBid.price + bestAsk.price)

        val deltaP = previousMid?.let { mid - it } ?: 0.0
        previousMid = mid

        val prevBar = klines.getOrNull(klines.size - 2)
        val klineReturn = if (prevBar != null && prevBar.close > 0.0 && lastBar.close > 0.0) {
            ln(lastBar.close / prevBar.close)
        } else {
            0.0
        }
        val klineRange = if (lastBar.close > 0.0) (lastBar.high - lastBar.low) / lastBar.close else 0.0
        val avgVolume = klines.takeLast(volumeAverageBars).map { it.baseVolume }.average()
        val volumeRel = if (avgVolume > 0.0) lastBar.baseVolume / avgVolume else 1.0

        val bookImbalance = run {
            val bidVol = depth.bids.take(bookImbalanceLevels).sumOf { it.size }
            val askVol = depth.asks.take(bookImbalanceLevels).sumOf { it.size }
            val total = bidVol + askVol
            if (total > 0.0) (bidVol - askVol) / total else 0.0
        }

        val relativeSpread = if (mid > 0.0) (bestAsk.price - bestBid.price) / mid else 0.0

        val shelfSkew = run {
            val bidVol = shelves.filter { it.side == BookSide.BID }.sumOf { it.totalVolume }
            val askVol = shelves.filter { it.side == BookSide.ASK }.sumOf { it.totalVolume }
            val total = bidVol + askVol
            if (total > 0.0) (bidVol - askVol) / total else 0.0
        }

        val tradeFlowImbalance = synchronized(historyLock) {
            val buyVol = recentTrades.filter { it.side == BookSide.BID }.sumOf { it.size }
            val sellVol = recentTrades.filter { it.side == BookSide.ASK }.sumOf { it.size }
            val total = buyVol + sellVol
            if (total > 0.0) (buyVol - sellVol) / total else 0.0
        }

        val kappaT = fundingRateProvider()?.fundingRate

        val uT = listOf(
            klineReturn.toFloat(),
            klineRange.toFloat(),
            volumeRel.toFloat(),
            bookImbalance.toFloat(),
            relativeSpread.toFloat(),
            shelfSkew.toFloat(),
            tradeFlowImbalance.toFloat(),
            (kappaT ?: 0.0).toFloat(),
        )

        // δ_t via OrderBookWalker, not a flat half-spread (design doc §9),
        // plus the taker exchange fee (paper §3.3). LONG walks the asks
        // upward, so vwap >= reference and slippage is already a
        // non-negative cost. SHORT walks the bids downward, so vwap <=
        // reference and slippage is <= 0 - negate it to express the same
        // "cost as a positive price quantity" convention. The fee is
        // always a cost regardless of direction, so it adds on both sides;
        // only the slippage term is direction-signed.
        val takerFeeRate = feeRateProvider()?.takerRate ?: DEFAULT_TAKER_FEE_RATE
        val executionCostLong = OrderBookWalker.walk(depth, PositionSide.LONG, referenceOrderSize)
            ?.let { it.slippage + it.vwapPrice * takerFeeRate }
        val executionCostShort = OrderBookWalker.walk(depth, PositionSide.SHORT, referenceOrderSize)
            ?.let { -it.slippage + it.vwapPrice * takerFeeRate }

        val yHatSnapshot: List<Float>
        val newPosition = rawPositionProvider()
        synchronized(historyLock) {
            yHatSnapshot = positionHistory.toList()
            positionHistory.addLast(newPosition)
            if (positionHistory.size > nBack) positionHistory.removeFirst()
        }

        return RLFeatureSample(
            timestampMs = maxOf(lastBar.startTime, depth.lastUpdateMs),
            uT = uT,
            deltaP = deltaP,
            executionCostLong = executionCostLong,
            executionCostShort = executionCostShort,
            kappaT = kappaT,
            yHat = yHatSnapshot,
        )
    }
}
