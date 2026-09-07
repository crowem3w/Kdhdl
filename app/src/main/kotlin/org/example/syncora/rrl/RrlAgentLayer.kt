package org.example.syncora.rrl

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.syncora.bitget.DepthSnapshot
import org.example.syncora.bitget.DepthUpdate
import org.example.syncora.bitget.FeeRates
import org.example.syncora.bitget.FundingRateInfo
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.PublicTrade
import org.example.syncora.log.AppLog
import org.example.syncora.log.LogLevel






data class RrlPerformanceSummary(
    val steps: Int = 0,
    val averagePosition: Double = 0.0,
    val cumulativePriceReturn: Double = 0.0,
    val cumulativeExecutionCost: Double = 0.0,
    val cumulativeFundingCarry: Double = 0.0,
    val cumulativeReward: Double = 0.0,
    val informationRatio: Double = 0.0,
)













class RrlAgentLayer(
    private val config: RrlAgentConfig = RrlAgentConfig(),
    





    private val checkpointStore: RrlCheckpointStore? = null,
) {
    private companion object {
        const val TAG = "RrlAgentLayer"
    }

    private val featureExtractor = RrlFeatureExtractor()
    private val agent = RecurrentReinforcementLearner(config)
    private val fundingGate = FundingSettlementGate()

    private var exchangeFeeRate = config.exchangeFeeRate
    private var previousTimestampMs: Long = -1L
    private var latestFundingRate: Double = 0.0
    private var lastMidPrice: Double? = null
    private var warmupLogged = false
    private var lastLoggedPositionSign = 0

    private val _signal = MutableStateFlow<RrlStepResult?>(null)
    val signal: StateFlow<RrlStepResult?> = _signal.asStateFlow()

    private val _performance = MutableStateFlow(RrlPerformanceSummary())
    val performance: StateFlow<RrlPerformanceSummary> = _performance.asStateFlow()

    
    enum class CheckpointStatus { DISABLED, IDLE, SAVING, SAVED, SAVE_FAILED, RESTORE_FAILED }

    private val _checkpointStatus = MutableStateFlow(
        if (checkpointStore != null) CheckpointStatus.IDLE else CheckpointStatus.DISABLED,
    )
    val checkpointStatus: StateFlow<CheckpointStatus> = _checkpointStatus.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in RrlAgentLayer checkpoint scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    
    
    
    
    private val pendingSaves: Channel<RrlAgentCheckpoint>? =
        checkpointStore?.let { Channel(capacity = Channel.CONFLATED) }

    init {
        if (checkpointStore != null && pendingSaves != null) {
            scope.launch {
                for (checkpoint in pendingSaves) {
                    _checkpointStatus.value = CheckpointStatus.SAVING
                    try {
                        checkpointStore.save(checkpoint)
                        _checkpointStatus.value = CheckpointStatus.SAVED
                        AppLog.agent(LogLevel.SUCCESS, "Checkpoint saved (step ${checkpoint.performance.steps})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Checkpoint autosave failed: ${e.message}")
                        _checkpointStatus.value = CheckpointStatus.SAVE_FAILED
                        AppLog.agent(LogLevel.ERROR, "Checkpoint autosave failed: ${e.message}")
                    }
                }
            }
        }
    }

    
    fun reset() {
        agent.reset()
        fundingGate.reset()
        previousTimestampMs = -1L
        lastMidPrice = null
        warmupLogged = false
        lastLoggedPositionSign = 0
        _signal.value = null
        _performance.value = RrlPerformanceSummary()
    }

    




    fun stop() {
        pendingSaves?.close()
        scope.cancel()
    }

    










    suspend fun restoreFromCheckpoint(): Boolean {
        val store = checkpointStore ?: return false
        val checkpoint = store.load() ?: return false
        return applyCheckpoint(checkpoint)
    }

    

















    suspend fun restoreFromUri(uri: Uri): Boolean {
        val store = checkpointStore ?: return false
        val checkpoint = store.loadFrom(uri) ?: return false
        return applyCheckpoint(checkpoint)
    }

    
    suspend fun exportTo(uri: Uri): Boolean {
        val store = checkpointStore ?: return false
        return try {
            store.saveTo(uri, buildCheckpoint())
            AppLog.agent(LogLevel.SUCCESS, "Checkpoint exported (step ${_performance.value.steps})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Checkpoint export failed: ${e.message}")
            AppLog.agent(LogLevel.ERROR, "Checkpoint export failed: ${e.message}")
            false
        }
    }

    private fun applyCheckpoint(checkpoint: RrlAgentCheckpoint): Boolean {
        if (checkpoint.configFingerprint != config.fingerprint()) {
            Log.w(TAG, "Ignoring checkpoint saved under a different RrlAgentConfig")
            _checkpointStatus.value = CheckpointStatus.RESTORE_FAILED
            AppLog.agent(LogLevel.WARNING, "Checkpoint ignored - saved under a different agent configuration")
            return false
        }
        if (!agent.restoreState(checkpoint.learnerState)) {
            Log.w(TAG, "Checkpoint's learner state is incompatible with the current agent dimensions")
            _checkpointStatus.value = CheckpointStatus.RESTORE_FAILED
            AppLog.agent(LogLevel.ERROR, "Checkpoint restore failed - incompatible learner state")
            return false
        }

        fundingGate.restore(checkpoint.fundingLastSettlementSeen)
        previousTimestampMs = checkpoint.previousTimestampMs
        lastMidPrice = checkpoint.lastMidPrice
        latestFundingRate = checkpoint.latestFundingRate
        exchangeFeeRate = checkpoint.exchangeFeeRate
        _performance.value = checkpoint.performance
        _checkpointStatus.value = CheckpointStatus.SAVED
        AppLog.agent(LogLevel.SUCCESS, "Checkpoint restored (resuming from step ${checkpoint.performance.steps})")
        return true
    }

    
    suspend fun clearCheckpoint() {
        checkpointStore?.delete()
    }

    fun onDepthUpdate(update: DepthUpdate) {
        featureExtractor.onDepthUpdate(update)
    }

    
    fun onDepthSnapshot(snapshot: DepthSnapshot) {
        featureExtractor.onDepthSnapshot(snapshot)
    }

    fun onTrade(trade: PublicTrade) {
        featureExtractor.onTrade(trade)
    }

    fun onFundingRate(info: FundingRateInfo) {
        featureExtractor.onFundingRate(info)
        latestFundingRate = info.fundingRate
    }

    fun onFeeRates(feeRates: FeeRates) {
        exchangeFeeRate = feeRates.takerRate
    }

    





    fun onKline(kline: Kline): RrlStepResult? {
        featureExtractor.onKline(kline)
        if (!featureExtractor.isWarmedUp()) return null
        if (!warmupLogged) {
            warmupLogged = true
            AppLog.agent(LogLevel.SUCCESS, "Agent warmed up - live training and decisions now active")
        }

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
        logDecision(result)
        enqueueCheckpointSave()
        return result
    }

    private fun logDecision(result: RrlStepResult) {
        
        
        
        val sign = when {
            result.position > 0.05 -> 1
            result.position < -0.05 -> -1
            else -> 0
        }
        if (sign != lastLoggedPositionSign) {
            lastLoggedPositionSign = sign
            val stance = when (sign) {
                1 -> "LONG"
                -1 -> "SHORT"
                else -> "FLAT"
            }
            AppLog.agent(
                LogLevel.INFO,
                "Agent decision: $stance (position=${"%.3f".format(result.position)}, expectedReturn=${"%.5f".format(result.expectedReturn)})",
            )
        } else {
            AppLog.agent(
                LogLevel.DEBUG,
                "step position=${"%.3f".format(result.position)} reward=${"%.5f".format(result.reward)}",
            )
        }
    }

    




    private fun enqueueCheckpointSave() {
        val pending = pendingSaves ?: return
        pending.trySend(buildCheckpoint())
    }

    private fun buildCheckpoint(): RrlAgentCheckpoint = RrlAgentCheckpoint(
        configFingerprint = config.fingerprint(),
        savedAtMs = System.currentTimeMillis(),
        learnerState = agent.snapshotState(),
        fundingLastSettlementSeen = fundingGate.snapshot(),
        previousTimestampMs = previousTimestampMs,
        lastMidPrice = lastMidPrice,
        latestFundingRate = latestFundingRate,
        exchangeFeeRate = exchangeFeeRate,
        performance = _performance.value,
    )

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