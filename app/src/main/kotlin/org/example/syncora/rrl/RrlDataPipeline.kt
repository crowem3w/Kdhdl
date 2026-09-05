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

/**
 * Wires the app's existing live market-data pipelines
 * ([TradingChartPipeline], [DepthPipeline], [BitgetTradeSocket]) plus
 * REST polling for funding ([BitgetFundingRateClient]) and exchange fees
 * ([BitgetFeeRateClient]) into an [RrlAgentLayer], so the crypto agent of
 * Borrageiro, Firoozye & Barucca steps once per closed kline bar using the
 * order book, transaction and funding information described in section
 * III-B1 of the paper.
 *
 * This class owns no sockets itself; it only subscribes to flows already
 * exposed by the pipelines passed in, mirroring how
 * [org.example.syncora.bitget.PaperTradingRepository] consumes the same
 * shared data sources. Callers are expected to start/stop those underlying
 * pipelines separately (see [org.example.syncora.SyncoraApplication]).
 */
class RrlDataPipeline(
    private val symbol: String = "BTCUSDT",
    private val productType: String = "usdt-futures",
    private val klinePipeline: TradingChartPipeline,
    private val depthPipeline: DepthPipeline,
    private val tradeSocket: BitgetTradeSocket,
    private val fundingRateClient: BitgetFundingRateClient = BitgetFundingRateClient(),
    private val feeRateClient: BitgetFeeRateClient = BitgetFeeRateClient(),
    /** Optional: when non-null and it returns credentials, account-specific fee rates are used. */
    private val feeRateCredentialsProvider: (() -> BitgetCredentials?)? = null,
    config: RrlAgentConfig = RrlAgentConfig(),
) {
    private companion object {
        const val TAG = "RrlDataPipeline"
        const val FEE_RATE_REFRESH_INTERVAL_MS = 15 * 60_000L
    }

    private val agent = RrlAgentLayer(config)

    /** f_t and its full reward/utility decomposition for the most recently closed bar. */
    val signal: StateFlow<RrlStepResult?> = agent.signal

    /** Running cumulative performance summary, analogous to Table 1 of the paper. */
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

    /** startTime of the last closed bar fed to the agent, to avoid double-stepping. */
    private var lastClosedBarStartTime: Long = -1L

    /**
     * Starts subscribing to the underlying market-data flows and stepping
     * the agent. Does not start [klinePipeline], [depthPipeline] or
     * [tradeSocket] themselves; the caller controls their lifecycle since
     * they are typically shared with other consumers (charts, paper/live
     * trading).
     */
    fun start() {
        stop()
        agent.reset()
        lastClosedBarStartTime = -1L

        klineJob = klinePipeline.klines
            .onEach { snapshot -> onKlineSnapshot(snapshot) }
            .catch { e -> Log.e(TAG, "Error feeding kline into RRL agent; dropping bar", e) }
            .launchIn(scope)

        depthJob = depthPipeline.depth
            .onEach { snapshot -> agent.onDepthSnapshot(snapshot) }
            .catch { e -> Log.e(TAG, "Error feeding depth snapshot into RRL agent; dropping update", e) }
            .launchIn(scope)

        tradeJob = tradeSocket.trades
            .onEach { trade -> agent.onTrade(trade) }
            .catch { e -> Log.e(TAG, "Error feeding trade into RRL agent; dropping print", e) }
            .launchIn(scope)

        fundingJob = scope.launch { runFundingLoop() }
        feeJob = scope.launch { runFeeRateLoop() }
    }

    /** Stops all subscriptions. Does not reset the agent's learned state; call [reset] for that. */
    fun stop() {
        klineJob?.cancel(); klineJob = null
        depthJob?.cancel(); depthJob = null
        tradeJob?.cancel(); tradeJob = null
        fundingJob?.cancel(); fundingJob = null
        feeJob?.cancel(); feeJob = null
    }

    /** Resets the agent's reservoir, learned weights and running statistics to a cold start. */
    fun reset() {
        agent.reset()
        lastClosedBarStartTime = -1L
    }

    /**
     * [klinePipeline] exposes a rolling buffer whose last element is the bar
     * currently forming (it is updated in place until a new bar begins, see
     * [org.example.syncora.bitget.KlineBuffer]). The second-to-last element
     * is therefore always a fully closed bar once it exists; feed exactly
     * once per distinct closed bar, matching "one call to step per sampling
     * interval" in [RecurrentReinforcementLearner].
     */
    private fun onKlineSnapshot(klines: List<Kline>) {
        if (klines.size < 2) return
        val closed = klines[klines.size - 2]
        if (closed.startTime > lastClosedBarStartTime) {
            lastClosedBarStartTime = closed.startTime
            agent.onKline(closed)
        }
    }

    /**
     * Funding is only meaningful right at an 8-hourly settlement boundary
     * (eq. 4); poll immediately on start for a warm value, then again right
     * after each boundary, mirroring
     * [org.example.syncora.bitget.PaperTradingRepository.runFundingLoop].
     */
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
            .onFailure { e -> Log.w(TAG, "Funding rate refresh failed: ${e.message}") }
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
            .onFailure { e -> Log.w(TAG, "Fee rate refresh failed: ${e.message}") }
    }
}
