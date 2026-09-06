package org.example.syncora.rrl

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.syncora.bitget.BitgetCredentials
import org.example.syncora.bitget.BitgetFeeRateClient
import org.example.syncora.bitget.BitgetFundingRateClient
import org.example.syncora.bitget.BitgetTradeSocket
import org.example.syncora.bitget.DepthPipeline
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.TradingChartPipeline
import org.example.syncora.log.AppLog
import org.example.syncora.log.LogLevel
















class RrlDataPipeline(
    private val symbol: String = "BTCUSDT",
    private val productType: String = "usdt-futures",
    private val klinePipeline: TradingChartPipeline,
    private val depthPipeline: DepthPipeline,
    private val tradeSocket: BitgetTradeSocket,
    private val fundingRateClient: BitgetFundingRateClient = BitgetFundingRateClient(),
    private val feeRateClient: BitgetFeeRateClient = BitgetFeeRateClient(),
    
    private val feeRateCredentialsProvider: (() -> BitgetCredentials?)? = null,
    config: RrlAgentConfig = RrlAgentConfig(),
) {
    private companion object {
        const val TAG = "RrlDataPipeline"
        const val FEE_RATE_REFRESH_INTERVAL_MS = 15 * 60_000L
    }

    private val agent = RrlAgentLayer(config)

    @Volatile
    private var active = false

    /**
     * Controls whether the training/fine-tuning agent is allowed to learn from live market data right now.
     * Set by [org.example.syncora.account.AccountManager] so that training and fine-tuning are always
     * scoped to whichever account (Paper or Live) is currently selected, and pause entirely when no
     * account is selected. Market-data collection (klines/depth/trades/funding) keeps flowing regardless;
     * only the agent's learning step is gated, so resuming picks up cleanly without losing buffered state.
     */
    fun setActive(enabled: Boolean) {
        active = enabled
    }

    fun isActive(): Boolean = active

    
    val signal: StateFlow<RrlStepResult?> = agent.signal

    
    val performance: StateFlow<RrlPerformanceSummary> = agent.performance

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in RrlDataPipeline coroutine scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private var klineJob: Job? = null
    private var depthJob: Job? = null
    private var tradeJob: Job? = null
    private var fundingJob: Job? = null
    private var feeJob: Job? = null

    
    private var lastClosedBarStartTime: Long = -1L

    






    fun start() {
        stop()
        agent.reset()
        lastClosedBarStartTime = -1L

        klineJob = klinePipeline.klines
            .onEach { snapshot -> onKlineSnapshot(snapshot) }
            .catch { e ->
                Log.e(TAG, "Error feeding kline into RRL agent; dropping bar", e)
                AppLog.agent(LogLevel.ERROR, "Kline feed error, bar dropped: ${e.message}")
            }
            .launchIn(scope)

        depthJob = depthPipeline.depth
            .onEach { snapshot -> if (active) agent.onDepthSnapshot(snapshot) }
            .catch { e ->
                Log.e(TAG, "Error feeding depth snapshot into RRL agent; dropping update", e)
                AppLog.agent(LogLevel.ERROR, "Depth feed error, update dropped: ${e.message}")
            }
            .launchIn(scope)

        tradeJob = tradeSocket.trades
            .onEach { trade -> if (active) agent.onTrade(trade) }
            .catch { e ->
                Log.e(TAG, "Error feeding trade into RRL agent; dropping print", e)
                AppLog.agent(LogLevel.ERROR, "Trade feed error, print dropped: ${e.message}")
            }
            .launchIn(scope)

        fundingJob = scope.launch { runFundingLoop() }
        feeJob = scope.launch { runFeeRateLoop() }
    }

    
    fun stop() {
        klineJob?.cancel(); klineJob = null
        depthJob?.cancel(); depthJob = null
        tradeJob?.cancel(); tradeJob = null
        fundingJob?.cancel(); fundingJob = null
        feeJob?.cancel(); feeJob = null
    }

    
    fun reset() {
        agent.reset()
        lastClosedBarStartTime = -1L
    }

    







    private fun onKlineSnapshot(klines: List<Kline>) {
        if (klines.size < 2) return
        val closed = klines[klines.size - 2]
        if (closed.startTime > lastClosedBarStartTime) {
            lastClosedBarStartTime = closed.startTime
            if (active) agent.onKline(closed)
        }
    }

    





    private suspend fun runFundingLoop() {
        refreshFundingRate()
        while (scope.isActive) {
            val next = FundingSchedule.nextSettlement(System.currentTimeMillis())
            val waitMs = (next - System.currentTimeMillis()).coerceAtLeast(1_000L)
            delay(waitMs)
            refreshFundingRate()
        }
    }

    private suspend fun refreshFundingRate() {
        runCatching { fundingRateClient.fetchCurrentFundingRate(symbol = symbol, productType = productType) }
            .onSuccess { agent.onFundingRate(it) }
            .onFailure { e ->
                Log.w(TAG, "Funding rate refresh failed: ${e.message}")
                AppLog.agent(LogLevel.WARNING, "Funding rate refresh failed: ${e.message}")
            }
    }

    private suspend fun runFeeRateLoop() {
        while (scope.isActive) {
            refreshFeeRates()
            delay(FEE_RATE_REFRESH_INTERVAL_MS)
        }
    }

    private suspend fun refreshFeeRates() {
        val credentials = feeRateCredentialsProvider?.invoke()
        if (credentials != null) {
            val accountRates = runCatching {
                feeRateClient.fetchAccountFeeRates(credentials, symbol = symbol)
            }.getOrNull()
            if (accountRates != null) {
                agent.onFeeRates(accountRates)
                return
            }
        }
        runCatching { feeRateClient.fetchStandardFeeRates(symbol = symbol, productType = productType) }
            .onSuccess { agent.onFeeRates(it) }
            .onFailure { e ->
                Log.w(TAG, "Fee rate refresh failed: ${e.message}")
                AppLog.agent(LogLevel.WARNING, "Fee rate refresh failed: ${e.message}")
            }
    }
}