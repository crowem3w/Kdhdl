package org.example.syncora.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import org.example.syncora.bitget.BitgetTradingRestClient
import org.example.syncora.bitget.OrderTicket
import org.example.syncora.bitget.PositionSide
import org.example.syncora.bitget.RiskSettingsStore
import org.example.syncora.bitget.StateVectorBuilder
import org.example.syncora.bitget.StateVectorUnavailableException
import org.example.syncora.bitget.TradingChartPipeline
import org.example.syncora.ml.PolicyInferenceEngine
import org.example.syncora.risk.EntrySafetyResult
import org.example.syncora.risk.ExitSafetyResult
import org.example.syncora.risk.PreTradeSafetyGate
import org.example.syncora.risk.VolatilityCircuitBreaker
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.sign

/**
 * What happened on one decision-boundary tick - exposed via [DecisionLoopScheduler.lastDecision]
 * for UI/telemetry, since a live trading loop that only logs to Logcat is
 * hard to trust.
 */
sealed class DecisionOutcome {
    /** Nothing was dispatched this tick, and why - most of these are normal, not errors. */
    data class Skipped(val reason: String) : DecisionOutcome()

    /** A snapshot/inference/order call threw. The loop itself keeps running; only this tick was lost. */
    data class Failed(val message: String) : DecisionOutcome()

    /** At least one order was placed. */
    data class Dispatched(
        val meanAction: Double,
        val noisyAction: Double,
        val currentPositionSize: Double,
        val targetPositionSize: Double,
        val orderIds: List<String>,
    ) : DecisionOutcome()
}

data class DecisionRecord(
    val timestampMs: Long,
    val klineStartTime: Long,
    val outcome: DecisionOutcome,
)

/**
 * The design doc's decision loop (§3.6 "Decision cadence vs. data cadence"):
 * fires exactly once per kline close on whatever [Timeframe][org.example.syncora.bitget.Timeframe]
 * [chartPipeline] is currently streaming, and on each fire:
 *
 * 1. Pulls a fresh `S_t` from [stateVectorBuilder].
 * 2. Runs it through [policyInferenceEngine] to get the policy-mean target
 *    leverage `a_t ∈ [-L_max, +L_max]`.
 * 3. Adds a small, clipped Gaussian exploration term around that mean
 *    (§3.6 "The on-policy/live-deployment tension") and re-clamps to
 *    `[-L_max, +L_max]` so exploration can never push the dispatched action
 *    outside the same leverage cap [policyInferenceEngine] already enforces
 *    on its raw output.
 * 4. Converts the resulting target leverage into a signed order-size delta
 *    in BTCUSDT base-coin terms against the account's current position,
 *    and dispatches whatever combination of open/close calls realizes that
 *    delta through [tradingClient] (itself already wired to
 *    [org.example.syncora.bitget.BitgetRequestSigner] for HMAC auth).
 *
 * **Close detection.** Rather than running its own timer that could drift
 * from Bitget's actual bar boundaries, this watches [chartPipeline]'s own
 * `klines` [StateFlow] for its last element's `startTime` advancing to a
 * new value - that only happens once a bar has fully closed and the
 * pipeline has appended the next one, so it's a direct read of the same
 * boundary the design doc means by "each kline close," not an
 * approximation of it. The very first emission (the already-primed buffer
 * from pipeline startup) is dropped since it isn't a close event.
 *
 * **Kill switch.** [riskSettingsStore.autoTradingEnabled] gates step 4
 * only - state is still pulled and the policy still runs (and the result
 * still lands in [lastDecision]) with dispatch disabled, so the loop can be
 * observed/tuned before it's trusted with live orders.
 *
 * **Independent safety checks (design doc §5).** Before *any* order this
 * class dispatches is actually transmitted, it goes through
 * [safetyGate] ([org.example.syncora.risk.PreTradeSafetyGate]) and
 * [volatilityCircuitBreaker] - both run their own checks against fresh,
 * non-cached exchange/market data and are not derived from, or trusted to
 * defer to, this class's own `meanAction`/`noisyAction`/`targetSize`
 * computation:
 *
 * - The volatility circuit breaker ([VolatilityCircuitBreaker], sourced
 *   from Deribit's DVOL) is consulted first, before sizing is even
 *   computed against the policy's action - if it's tripped, the target
 *   position is forced to flat regardless of what the policy said, on
 *   every subsequent tick until the breaker clears.
 * - Every entry (exposure-increasing) order goes through
 *   [PreTradeSafetyGate.evaluateEntry], which independently re-checks the
 *   volatility breaker, fetches a live (not cached) Bitget balance and
 *   confirms it's non-negative, and clamps the order to a hard
 *   leverage/margin cap - regardless of what target leverage the policy
 *   computed.
 * - Every exit (exposure-reducing) order goes through
 *   [PreTradeSafetyGate.evaluateExit] instead, since a close/flatten must
 *   never be blocked by the same checks that gate entries (see that
 *   method's kdoc).
 *
 * **Not this class's job.** The exchange-side dead-man's-switch stop-loss
 * (§2.2/§5) is [org.example.syncora.bitget.StopLossGuard]'s responsibility,
 * driven off the same position stream this loop trades against - it runs
 * independently and doesn't need this scheduler alive to keep protecting
 * an open position. The experience log / two-phase reward backfill (§3.6)
 * and the batch PPO retrain (§3.3/§4) are separate, not-yet-built
 * components; this class only owns live inference + dispatch.
 */
class DecisionLoopScheduler(
    private val chartPipeline: TradingChartPipeline,
    private val stateVectorBuilder: StateVectorBuilder,
    private val policyInferenceEngine: PolicyInferenceEngine,
    private val tradingClient: BitgetTradingRestClient,
    private val riskSettingsStore: RiskSettingsStore,
    /** Independent, policy-output-agnostic checks run before every order this loop transmits - see class kdoc. */
    private val safetyGate: PreTradeSafetyGate,
    /** Consulted before sizing, to force the target position to flat when tripped - see class kdoc. */
    private val volatilityCircuitBreaker: VolatilityCircuitBreaker,
    private val symbol: String = "BTCUSDT",
    /** Must match (or be tighter than) [PolicyInferenceEngine]'s own `actionLeverageCap` - see kdoc above. */
    private val actionLeverageCap: Double = 3.0,
    /**
     * Standard deviation of the exploration Gaussian, in the same target-
     * leverage units as the policy's action - design doc §3.6: "a
     * hyperparameter, tuned so worst-case per-decision deviation stays
     * inside the leverage/margin guardrails."
     */
    private val explorationNoiseStdDev: Double = 0.05,
    /** Hard clip on a single sampled noise draw, independent of [explorationNoiseStdDev] - bounds a fat-tailed draw, not just the typical case. */
    private val explorationNoiseClip: Double = 0.15,
    /** Below this, a computed order-size delta is treated as noise/rounding, not a real rebalance - avoids dust-order churn every close. */
    private val minOrderSizeInBaseCoin: Double = 0.0002,
    /** Bitget per-symbol margin leverage used for the order tickets this class places - a separate knob from the policy's *target* leverage `a_t`, which already determines position *size* via `a_t * balance`. */
    private val positionLeverage: Int = 3,
    private val marginMode: String = "crossed",
    private val random: Random = Random(),
) {
    private companion object {
        const val TAG = "DecisionLoopScheduler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _lastDecision = MutableStateFlow<DecisionRecord?>(null)
    val lastDecision: StateFlow<DecisionRecord?> = _lastDecision.asStateFlow()

    private var lastActedStartTimeMs: Long = -1L

    /** Idempotent, like the other lifecycle-bound pieces ([org.example.syncora.bitget.StopLossGuard], `TradingChartPipeline`) - safe to call repeatedly. */
    fun start() {
        stop()
        job = chartPipeline.klines
            .mapNotNull { it.lastOrNull()?.startTime }
            .distinctUntilChanged()
            .drop(1) // first emission is the pipeline's already-primed buffer, not a close event
            .onEach { newBarStartTime -> onKlineClose(newBarStartTime) }
            .launchIn(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun onKlineClose(newBarStartTime: Long) {
        // A new bar opening at newBarStartTime means the bar immediately
        // before it just closed - that close is this tick's decision
        // boundary. Guard against acting twice on the same boundary (e.g.
        // a StateFlow replay to a new collector) or on a backward jump
        // (e.g. the pipeline re-priming from a cold-start REST snapshot).
        if (newBarStartTime <= lastActedStartTimeMs) return
        lastActedStartTimeMs = newBarStartTime

        val record = try {
            runDecisionTick()
        } catch (e: Exception) {
            Log.e(TAG, "Decision tick failed: ${e.message}", e)
            DecisionOutcome.Failed(e.message ?: e.toString())
        }
        _lastDecision.value = DecisionRecord(
            timestampMs = System.currentTimeMillis(),
            klineStartTime = newBarStartTime,
            outcome = record,
        )
    }

    private suspend fun runDecisionTick(): DecisionOutcome {
        if (!policyInferenceEngine.ensureLoaded()) {
            return DecisionOutcome.Skipped("no policy model loaded yet")
        }

        val snapshot = stateVectorBuilder.snapshot().fold(
            onSuccess = { it },
            onFailure = { e ->
                val reason = (e as? StateVectorUnavailableException)?.reason?.toString() ?: (e.message ?: "unknown")
                return DecisionOutcome.Skipped("state vector unavailable: $reason")
            },
        )

        val meanAction = policyInferenceEngine.infer(snapshot)
            ?: return DecisionOutcome.Skipped("inference failed/no output")

        // Bounded exploration (design doc §3.6): sample around the policy
        // mean, clip the draw itself, then re-clamp the sum to the same
        // leverage cap the policy's own raw output is held to - noise can
        // widen the action within the cap, never past it.
        val rawNoise = random.nextGaussian() * explorationNoiseStdDev
        val clippedNoise = rawNoise.coerceIn(-explorationNoiseClip, explorationNoiseClip)
        val noisyAction = (meanAction + clippedNoise).coerceIn(-actionLeverageCap, actionLeverageCap)

        if (snapshot.markPrice <= 0.0) {
            return DecisionOutcome.Skipped("no valid mark price to size against")
        }

        val currentSize = snapshot.positionSize
        val targetNotional = noisyAction * snapshot.balance
        var targetSize = targetNotional / snapshot.markPrice

        // Volatility circuit breaker (design doc §5): consulted before
        // sizing is dispatched, independent of the policy's own output.
        // When tripped, the target is forced to flat on every tick until
        // the breaker clears - this overrides whatever the policy computed
        // above, it does not merely block a would-be add.
        if (volatilityCircuitBreaker.shouldFlattenToCash()) {
            Log.w(TAG, "Volatility circuit breaker tripped (${volatilityCircuitBreaker.status.value}); forcing target position to flat")
            targetSize = 0.0
        }

        val orderIds = dispatchTargetPosition(
            currentSize = currentSize,
            targetSize = targetSize,
            markPrice = snapshot.markPrice,
        ) ?: return DecisionOutcome.Skipped("auto-trading disabled in risk settings")

        return if (orderIds.isEmpty()) {
            DecisionOutcome.Skipped("delta below minimum order size")
        } else {
            DecisionOutcome.Dispatched(
                meanAction = meanAction,
                noisyAction = noisyAction,
                currentPositionSize = currentSize,
                targetPositionSize = targetSize,
                orderIds = orderIds,
            )
        }
    }

    /**
     * Realizes `targetSize - currentSize` (signed, base coin) as the
     * minimum set of open/close calls against [tradingClient]:
     *
     * - Flat/same-direction and growing: a single `open` add in that direction.
     * - Same-direction and shrinking: a single reduce-only `close`, no flip.
     * - Opposite-direction (including target crossing through zero): a
     *   reduce-only `close` of the *entire* current position, then - only
     *   if [targetSize] isn't itself flat - a fresh `open` in the new
     *   direction. Bitget's one-way position mode can't hold both sides at
     *   once, so a flip has to fully close before it can open the other way.
     *
     * Returns `null` (nothing evaluated, nothing dispatched) if
     * [RiskSettingsStore.autoTradingEnabled] is off; an empty list if the
     * delta rounds to nothing (or every leg [safetyGate] would have sized
     * is rejected outright); otherwise the placed order IDs. Every add goes
     * through [safetyGate]'s entry check (volatility breaker + live balance
     * + hard leverage cap) and may be dispatched **smaller** than requested
     * if the leverage cap clamps it; every close goes through its exit
     * check instead - see [PreTradeSafetyGate] kdoc.
     */
    private suspend fun dispatchTargetPosition(
        currentSize: Double,
        targetSize: Double,
        markPrice: Double,
    ): List<String>? {
        if (!riskSettingsStore.autoTradingEnabled) return null

        val currentSign = sign(currentSize).toInt()
        val targetSign = sign(targetSize).toInt()
        val orderIds = mutableListOf<String>()

        if (currentSign == 0 || currentSign == targetSign) {
            if (abs(targetSize) >= abs(currentSize)) {
                val addSize = abs(targetSize) - abs(currentSize)
                if (addSize < minOrderSizeInBaseCoin) return orderIds
                val side = if (targetSign >= 0) PositionSide.LONG else PositionSide.SHORT
                placeApprovedOpen(side, addSize, abs(currentSize), markPrice)?.let { orderIds += it }
            } else {
                val reduceSize = abs(currentSize) - abs(targetSize)
                if (reduceSize < minOrderSizeInBaseCoin) return orderIds
                val currentSide = if (currentSign >= 0) PositionSide.LONG else PositionSide.SHORT
                placeApprovedClose(currentSide, reduceSize)?.let { orderIds += it }
            }
        } else {
            // Opposite signs: flatten the existing side first, then open
            // the new side if the target isn't itself flat.
            if (abs(currentSize) >= minOrderSizeInBaseCoin) {
                val currentSide = if (currentSign >= 0) PositionSide.LONG else PositionSide.SHORT
                placeApprovedClose(currentSide, abs(currentSize))?.let { orderIds += it }
            }
            if (abs(targetSize) >= minOrderSizeInBaseCoin) {
                val targetSide = if (targetSign >= 0) PositionSide.LONG else PositionSide.SHORT
                // The flip's close leg (if any) above already reduced
                // exposure toward flat; the open leg below starts from flat.
                placeApprovedOpen(targetSide, abs(targetSize), currentPositionSizeAbs = 0.0, markPrice = markPrice)?.let { orderIds += it }
            }
        }
        return orderIds
    }

    /** Runs [safetyGate]'s entry check and, if approved, dispatches the (possibly clamped) open. Returns `null` if rejected. */
    private suspend fun placeApprovedOpen(
        side: PositionSide,
        requestedAddSize: Double,
        currentPositionSizeAbs: Double,
        markPrice: Double,
    ): String? {
        return when (val result = safetyGate.evaluateEntry(currentPositionSizeAbs, requestedAddSize, markPrice)) {
            is EntrySafetyResult.Rejected -> {
                Log.w(TAG, "Pre-trade safety gate rejected entry ($side, requested=$requestedAddSize): ${result.reason}")
                null
            }
            is EntrySafetyResult.Approved -> {
                if (result.approvedAddSizeInBaseCoin < minOrderSizeInBaseCoin) {
                    Log.w(TAG, "Pre-trade safety gate approved only a dust-sized entry (${result.approvedAddSizeInBaseCoin}); skipping")
                    null
                } else {
                    placeOpen(side, result.approvedAddSizeInBaseCoin)
                }
            }
        }
    }

    /** Runs [safetyGate]'s exit check and, if approved, dispatches the close. Returns `null` if rejected. */
    private suspend fun placeApprovedClose(side: PositionSide, sizeInBaseCoin: Double): String? {
        return when (val result = safetyGate.evaluateExit()) {
            is ExitSafetyResult.Rejected -> {
                Log.w(TAG, "Pre-trade safety gate rejected exit ($side, size=$sizeInBaseCoin): ${result.reason}")
                null
            }
            ExitSafetyResult.Approved -> placeClose(side, sizeInBaseCoin)
        }
    }

    private suspend fun placeOpen(side: PositionSide, sizeInBaseCoin: Double): String {
        val ticket = OrderTicket(
            symbol = symbol,
            side = side,
            sizeInBaseCoin = formatSize(sizeInBaseCoin),
            leverage = positionLeverage,
            marginMode = marginMode,
        )
        val placed = tradingClient.openPosition(ticket)
        Log.i(TAG, "Decision loop opened $side ${ticket.sizeInBaseCoin} $symbol (order ${placed.orderId})")
        return placed.orderId
    }

    private suspend fun placeClose(side: PositionSide, sizeInBaseCoin: Double): String {
        val placed = tradingClient.closePosition(
            symbol = symbol,
            side = side,
            sizeInBaseCoin = formatSize(sizeInBaseCoin),
            marginMode = marginMode,
        )
        Log.i(TAG, "Decision loop closed $side ${formatSize(sizeInBaseCoin)} $symbol (order ${placed.orderId})")
        return placed.orderId
    }

    // NOTE: fixed three-decimal base-coin precision, same simplification
    // StopLossGuard makes - a production build should size this off
    // BTCUSDT's actual contract precision rather than hardcoding it.
    private fun formatSize(size: Double): String = String.format(Locale.US, "%.3f", size)
}
