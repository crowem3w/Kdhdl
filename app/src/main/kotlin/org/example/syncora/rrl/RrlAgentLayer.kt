package org.example.syncora.rrl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.syncora.bitget.DepthUpdate
import org.example.syncora.bitget.FeeRates
import org.example.syncora.bitget.FundingRateInfo
import org.example.syncora.bitget.Kline

/**
 * Cumulative performance summary, analogous to Table 1 of the paper
 * (position / execution / carry / pnl columns, summed and expressed as an
 * online information ratio estimate).
 */
data class RrlPerformanceSummary(
    val steps: Int = 0,
    val averagePosition: Double = 0.0,
    val cumulativePriceReturn: Double = 0.0,
    val cumulativeExecutionCost: Double = 0.0,
    val cumulativeFundingCarry: Double = 0.0,
    val cumulativeReward: Double = 0.0,
    val informationRatio: Double = 0.0,
)

/**
 * Wires live market data (klines, order-book depth and funding rate) into
 * the [EchoStateReservoir] + [RecurrentReinforcementLearner] pipeline
 * described in the paper, and exposes the resulting position signal and
 * performance decomposition as [StateFlow]s for the UI layer, in the same
 * spirit as [org.example.syncora.bitget.DepthPipeline] and
 * [org.example.syncora.bitget.LiveTradingRepository].
 *
 * The agent steps once per kline close (the paper samples every five
 * minutes; any kline interval works here). Depth and funding updates are
 * cached and folded into the next kline-driven step.
 */
class RrlAgentLayer(
    private val config: RrlAgentConfig = RrlAgentConfig(),
) {
    private val featureExtractor = RrlFeatureExtractor()
    private val agent = RecurrentReinforcementLearner(config)
    private val fundingGate = FundingSettlementGate()

    private var exchangeFeeRate = config.exchangeFeeRate
    private var previousTimestampMs: Long = -1L
    private var latestFundingRate: Double = 0.0
    private var lastMidPrice: Double? = null

    private val _signal = MutableStateFlow<RrlStepResult?>(null)
    val signal: StateFlow<RrlStepResult?> = _signal.asStateFlow()

    private val _performance = MutableStateFlow(RrlPerformanceSummary())
    val performance: StateFlow<RrlPerformanceSummary> = _performance.asStateFlow()

    /** Resets the agent, running statistics and cached market state to a cold start. */
    fun reset() {
        agent.reset()
        fundingGate.reset()
        previousTimestampMs = -1L
        _signal.value = null
        _performance.value = RrlPerformanceSummary()
    }

    fun onDepthUpdate(update: DepthUpdate) {
        featureExtractor.onDepthUpdate(update)
    }

    fun onFundingRate(info: FundingRateInfo) {
        featureExtractor.onFundingRate(info)
        latestFundingRate = info.fundingRate
    }

    fun onFeeRates(feeRates: FeeRates) {
        exchangeFeeRate = feeRates.takerRate
    }

    /**
     * Advances the agent by one bar. Returns null if the reservoir has not
     * yet warmed up (insufficient price/order-book history), matching the
     * paper's expectation that the model is "driven for long enough" before
     * its output can be trusted.
     */
    fun onKline(kline: Kline): RrlStepResult? {
        featureExtractor.onKline(kline)
        if (!featureExtractor.isWarmedUp()) return null

        val (bid, ask) = featureExtractor.currentBidAsk() ?: return null
        val mid = 0.5 * (bid + ask)
        val halfSpread = 0.5 * (ask - bid)

        val timestampMs = kline.startTime
        val settled = fundingGate.didSettle(previousTimestampMs, timestampMs)
        val fundingRateForBar = if (settled) latestFundingRate else 0.0

        val previousMid = lastMidPrice
        val deltaPrice = if (previousMid != null) mid - previousMid else 0.0
        lastMidPrice = mid

        val observation = MarketObservation(
            timestampMs = timestampMs,
            bid = bid,
            ask = ask,
            features = featureExtractor.buildInput(timestampMs),
            fundingRate = fundingRateForBar,
        )

        val totalExecutionCost = halfSpread + exchangeFeeRate
        val result = agent.step(observation, deltaPrice = deltaPrice, executionCost = totalExecutionCost)

        previousTimestampMs = timestampMs
        _signal.value = result
        updatePerformance(result)
        return result
    }

    private fun updatePerformance(result: RrlStepResult) {
        val previous = _performance.value
        val steps = previous.steps + 1
        val cumulativePriceReturn = previous.cumulativePriceReturn + result.priceReturn
        val cumulativeExecutionCost = previous.cumulativeExecutionCost + result.executionCost
        val cumulativeFundingCarry = previous.cumulativeFundingCarry + result.fundingCarry
        val cumulativeReward = previous.cumulativeReward + result.reward
        val averagePosition = ((previous.averagePosition * previous.steps) + result.position) / steps

        _performance.value = RrlPerformanceSummary(
            steps = steps,
            averagePosition = averagePosition,
            cumulativePriceReturn = cumulativePriceReturn,
            cumulativeExecutionCost = cumulativeExecutionCost,
            cumulativeFundingCarry = cumulativeFundingCarry,
            cumulativeReward = cumulativeReward,
            informationRatio = result.informationRatio,
        )
    }
}
