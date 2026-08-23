package org.example.syncora.risk

import android.util.Log
import org.example.syncora.bitget.BitgetTradingRestClient
import kotlin.math.min

/** Outcome of [PreTradeSafetyGate.evaluateEntry]. */
sealed class EntrySafetyResult {
    /**
     * The order is safe to send. [approvedAddSizeInBaseCoin] may be
     * *smaller* than what was requested - the leverage-cap check clamps
     * rather than all-or-nothing rejects, so a caller should always dispatch
     * this value, never the originally requested one.
     */
    data class Approved(val approvedAddSizeInBaseCoin: Double) : EntrySafetyResult()

    /** The order must not be sent. [reason] is meant to be logged/surfaced, not parsed. */
    data class Rejected(val reason: String) : EntrySafetyResult()
}

/** Outcome of [PreTradeSafetyGate.evaluateExit]. */
sealed class ExitSafetyResult {
    data object Approved : ExitSafetyResult()
    data class Rejected(val reason: String) : ExitSafetyResult()
}

/**
 * The independent, policy-output-agnostic checks the design doc's §5 risk
 * guardrails describe, run **synchronously before every order is
 * transmitted** - not something the policy network can influence, opt out
 * of, or run instead of. Every live order-placing call site
 * ([org.example.syncora.agent.DecisionLoopScheduler] and
 * [org.example.syncora.bitget.LiveTradingRepository]'s manual path) is
 * required to call [evaluateEntry] before an order that *increases*
 * exposure, and [evaluateExit] before one that *decreases* it.
 *
 * Three checks, run in this order for [evaluateEntry]:
 *
 * 1. **Volatility circuit breaker** ([VolatilityCircuitBreaker], sourced
 *    from Deribit's DVOL - see [VolatilityIndexClient]): if tripped or the
 *    reading is stale/unavailable, the entry is rejected outright. This
 *    check alone is enough to reject; it doesn't need a network call here
 *    because [VolatilityCircuitBreaker] already polls independently on its
 *    own schedule.
 * 2. **Live, non-cached balance check**: fetches
 *    [BitgetTradingRestClient.fetchAccountBalance] fresh, right here, not
 *    a `StateFlow.value` some poll loop cached a few seconds ago. Rejects
 *    if the live endpoint can't be reached, returns no USDT row, or reports
 *    a non-positive available balance.
 * 3. **Hard leverage/margin cap** ([RiskLimitsStore.maxLeverage]): computed
 *    off the *same* fresh balance fetched in step 2 (equity, not available
 *    margin, since equity is the number a leverage ratio is conventionally
 *    expressed against) and the caller's reported current position size -
 *    clamps the requested add-size down to whatever headroom remains under
 *    the cap, regardless of what the policy's target leverage said it
 *    wanted. This is deliberately independent of
 *    [org.example.syncora.ml.PolicyInferenceEngine]'s own `actionLeverageCap`:
 *    that one shapes the *model's* output; this one is enforced here even
 *    if that one were misconfigured, bypassed, or the caller weren't the
 *    policy loop at all.
 *
 * [evaluateExit] deliberately skips all three: a reduce-only close/flatten
 * order never increases risk, and is precisely what the volatility breaker
 * itself needs to be able to do when tripped (see
 * [VolatilityCircuitBreaker.shouldFlattenToCash]) - gating exits behind the
 * same checks that gate entries would make the circuit breaker unable to
 * flatten to cash exactly when it most needs to. It still does a minimal
 * live-reachability check so a close isn't dispatched blind into a
 * completely unreachable exchange.
 */
class PreTradeSafetyGate(
    private val tradingClient: BitgetTradingRestClient,
    private val volatilityCircuitBreaker: VolatilityCircuitBreaker,
    private val riskLimitsStore: RiskLimitsStore,
) {
    private companion object {
        const val TAG = "PreTradeSafetyGate"

        // Backstop on RiskLimitsStore.maxLeverage itself, in case a bad
        // value ever got persisted (e.g. a stray UI bug writing something
        // absurd) - this class's whole purpose is to not trust a single
        // upstream number blindly.
        const val ABSOLUTE_MAX_LEVERAGE = 20.0
    }

    /**
     * @param currentPositionSizeAbs unsigned current position size (base coin) on the side being added to, before this order
     * @param requestedAddSizeInBaseCoin unsigned size the caller wants to add - may be clamped down, never up
     * @param markPrice must be a valid (> 0) current mark/last price to convert notional <-> base-coin size
     */
    suspend fun evaluateEntry(
        currentPositionSizeAbs: Double,
        requestedAddSizeInBaseCoin: Double,
        markPrice: Double,
    ): EntrySafetyResult {
        if (markPrice <= 0.0) {
            return EntrySafetyResult.Rejected("no valid mark price to evaluate order against")
        }
        if (requestedAddSizeInBaseCoin <= 0.0) {
            return EntrySafetyResult.Rejected("requested add size is not positive")
        }

        // --- Check 1: volatility circuit breaker -----------------------
        // Independent poll loop; this read never itself makes a network
        // call, and can't be influenced by anything this method's caller
        // passes in.
        if (volatilityCircuitBreaker.shouldHaltEntries()) {
            val status = volatilityCircuitBreaker.status.value
            Log.w(TAG, "Entry rejected by volatility circuit breaker: $status")
            return EntrySafetyResult.Rejected("volatility circuit breaker active ($status); new entries halted")
        }

        // --- Check 2: live, non-cached balance ---------------------------
        val liveBalance = try {
            tradingClient.fetchAccountBalance()
        } catch (e: Exception) {
            Log.w(TAG, "Entry rejected: live balance fetch failed: ${e.message}")
            return EntrySafetyResult.Rejected("could not confirm live Bitget balance: ${e.message}")
        }
        if (liveBalance == null) {
            return EntrySafetyResult.Rejected("Bitget account-balance endpoint returned no USDT-margin row")
        }
        if (liveBalance.available <= 0.0) {
            Log.w(TAG, "Entry rejected: non-negative balance check failed (available=${liveBalance.available})")
            return EntrySafetyResult.Rejected("live Bitget available balance is non-positive (${liveBalance.available}); refusing to increase exposure")
        }

        // --- Check 3: hard leverage/margin cap --------------------------
        val hardCap = riskLimitsStore.maxLeverage.coerceIn(0.0, ABSOLUTE_MAX_LEVERAGE)
        val equity = liveBalance.equity.coerceAtLeast(0.0)
        val maxNotional = equity * hardCap
        val currentNotional = currentPositionSizeAbs * markPrice
        val headroomNotional = (maxNotional - currentNotional).coerceAtLeast(0.0)
        val requestedNotional = requestedAddSizeInBaseCoin * markPrice
        val approvedNotional = min(requestedNotional, headroomNotional)
        val approvedSize = approvedNotional / markPrice

        if (approvedSize <= 0.0) {
            Log.w(TAG, "Entry rejected: leverage cap reached (current notional=$currentNotional, cap=$maxNotional at ${hardCap}x equity=$equity)")
            return EntrySafetyResult.Rejected(
                "hard leverage cap reached: current notional ~$currentNotional already at/above cap ~$maxNotional (${hardCap}x live equity)",
            )
        }
        if (approvedSize < requestedAddSizeInBaseCoin) {
            Log.i(TAG, "Entry clamped by leverage cap: requested=$requestedAddSizeInBaseCoin approved=$approvedSize")
        }
        return EntrySafetyResult.Approved(approvedSize)
    }

    /**
     * Minimal check for orders that only reduce/flatten exposure - see
     * class kdoc for why exits deliberately bypass the volatility and
     * leverage checks above.
     */
    suspend fun evaluateExit(): ExitSafetyResult = try {
        tradingClient.fetchAccountBalance()
        ExitSafetyResult.Approved
    } catch (e: Exception) {
        Log.w(TAG, "Exit reachability check failed: ${e.message}")
        ExitSafetyResult.Rejected("could not reach Bitget account endpoint: ${e.message}")
    }
}
