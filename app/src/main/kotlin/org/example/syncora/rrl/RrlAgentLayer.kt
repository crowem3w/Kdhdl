package org.example.syncora.rrl

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
    /**
     * When non-null, the agent's learned state is autosaved to this store
     * after every bar (see [enqueueCheckpointSave]) and can be reloaded with
     * [restoreFromCheckpoint]. When null, checkpointing is disabled entirely
     * and the agent behaves exactly as before.
     */
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

    private val _signal = MutableStateFlow<RrlStepResult?>(null)
    val signal: StateFlow<RrlStepResult?> = _signal.asStateFlow()

    private val _performance = MutableStateFlow(RrlPerformanceSummary())
    val performance: StateFlow<RrlPerformanceSummary> = _performance.asStateFlow()

    /** Status of the background autosave, e.g. for a small "saved"/"saving" indicator in the UI. */
    enum class CheckpointStatus { DISABLED, IDLE, SAVING, SAVED, SAVE_FAILED, RESTORE_FAILED }

    private val _checkpointStatus = MutableStateFlow(
        if (checkpointStore != null) CheckpointStatus.IDLE else CheckpointStatus.DISABLED,
    )
    val checkpointStatus: StateFlow<CheckpointStatus> = _checkpointStatus.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in RrlAgentLayer checkpoint scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    // Capacity 1 + CONFLATED: if a save is still in flight when the next bar arrives, the new
    // snapshot simply replaces the pending one rather than queuing up. Since a checkpoint only
    // ever needs to reflect the *latest* state, dropped intermediate snapshots cost nothing, and
    // this guarantees autosaving per bar can never block or fall behind market-data processing.
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Checkpoint autosave failed: ${e.message}")
                        _checkpointStatus.value = CheckpointStatus.SAVE_FAILED
                    }
                }
            }
        }
    }

    /** Resets the agent, running statistics and cached market state to a cold start. */
    fun reset() {
        agent.reset()
        fundingGate.reset()
        previousTimestampMs = -1L
        lastMidPrice = null
        _signal.value = null
        _performance.value = RrlPerformanceSummary()
    }

    /**
     * Cancels the background autosave coroutine. Call this when the layer is
     * being torn down (e.g. its owning screen/service is destroyed) so it
     * doesn't leak. Safe to call even if checkpointing is disabled.
     */
    fun stop() {
        pendingSaves?.close()
        scope.cancel()
    }

    /**
     * Attempts to load and apply a previously autosaved checkpoint, resuming
     * online learning where it left off instead of the cold start [reset]
     * produces. Returns `true` if a compatible checkpoint was found and
     * applied; `false` if there was none, it was unreadable, or it was
     * produced by a structurally different [RrlAgentConfig] -- in any of
     * those cases the agent is left exactly as it was before the call.
     *
     * Call this once, before the first [onKline], typically right after
     * constructing the layer.
     */
    suspend fun restoreFromCheckpoint(): Boolean {
        val store = checkpointStore ?: return false
        val checkpoint = store.load() ?: return false

        if (checkpoint.configFingerprint != config.fingerprint()) {
            Log.w(TAG, "Ignoring checkpoint saved under a different RrlAgentConfig")
            _checkpointStatus.value = CheckpointStatus.RESTORE_FAILED
            return false
        }
        if (!agent.restoreState(checkpoint.learnerState)) {
            Log.w(TAG, "Checkpoint's learner state is incompatible with the current agent dimensions")
            _checkpointStatus.value = CheckpointStatus.RESTORE_FAILED
            return false
        }

        fundingGate.restore(checkpoint.fundingLastSettlementSeen)
        previousTimestampMs = checkpoint.previousTimestampMs
        lastMidPrice = checkpoint.lastMidPrice
        latestFundingRate = checkpoint.latestFundingRate
        exchangeFeeRate = checkpoint.exchangeFeeRate
        _performance.value = checkpoint.performance
        _checkpointStatus.value = CheckpointStatus.SAVED
        return true
    }

    /** Deletes the persisted checkpoint, if any. Does not affect the agent's current in-memory state. */
    suspend fun clearCheckpoint() {
        checkpointStore?.delete()
    }

    fun onDepthUpdate(update: DepthUpdate) {
        featureExtractor.onDepthUpdate(update)
    }

    /** Preferred over [onDepthUpdate] when a merged, checksum-verified snapshot is available. */
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
        enqueueCheckpointSave()
        return result
    }

    /**
     * Builds a snapshot of the agent's current state and hands it to the
     * background autosave coroutine (see [pendingSaves]). A no-op when
     * checkpointing is disabled.
     */
    private fun enqueueCheckpointSave() {
        val pending = pendingSaves ?: return
        val checkpoint = RrlAgentCheckpoint(
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
        pending.trySend(checkpoint)
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
