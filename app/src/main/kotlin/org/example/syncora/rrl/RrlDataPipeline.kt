package org.example.syncora.rrl

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    checkpointStore: RrlCheckpointStore? = null,
) {
    private companion object {
        const val TAG = "RrlDataPipeline"
        const val FEE_RATE_REFRESH_INTERVAL_MS = 15 * 60_000L
    }

    private val agent = RrlAgentLayer(config, checkpointStore)

    
    val checkpointStatus: StateFlow<RrlAgentLayer.CheckpointStatus> = agent.checkpointStatus

    
    suspend fun exportCheckpoint(uri: Uri): Boolean = agent.exportTo(uri)

    
    suspend fun importCheckpoint(uri: Uri): Boolean = agent.restoreFromUri(uri)

    
    suspend fun restoreLastAutosave(): Boolean = agent.restoreFromCheckpoint()

    /** Whether the current account mode (paper/live) wants the agent live-fed. Driven by AccountManager. */
    @Volatile
    private var modeActive = false

    /** Manual override from the Pause/Resume Agent button, independent of account mode. */
    @Volatile
    private var manuallyPaused = false

    private val _isPaused = MutableStateFlow(false)

    /** True when the agent is manually paused, for the Pause/Resume Agent button to reflect. */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** Effective feed-through gate: on only when the account mode wants it *and* it isn't manually paused. */
    private val active: Boolean
        get() = modeActive && !manuallyPaused

    fun setActive(enabled: Boolean) {
        modeActive = enabled
    }

    fun isActive(): Boolean = active

    /** Pauses agent training/inference without touching account mode or resetting state. */
    fun pause() {
        if (manuallyPaused) return
        manuallyPaused = true
        _isPaused.value = true
        AppLog.agent(LogLevel.INFO, "Agent paused")
    }

    /** Resumes agent training/inference after a manual pause. */
    fun resume() {
        if (!manuallyPaused) return
        manuallyPaused = false
        _isPaused.value = false
        AppLog.agent(LogLevel.INFO, "Agent resumed")
    }

    /** Flips the manual pause state; returns the new paused value. */
    fun togglePause(): Boolean {
        if (manuallyPaused) resume() else pause()
        return manuallyPaused
    }

    
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