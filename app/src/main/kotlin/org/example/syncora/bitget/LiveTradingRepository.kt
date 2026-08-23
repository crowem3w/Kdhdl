package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.syncora.risk.EntrySafetyResult
import org.example.syncora.risk.ExitSafetyResult
import org.example.syncora.risk.PreTradeSafetyGate

/**
 * Owns the polling loop against [BitgetTradingRestClient] and exposes
 * the **real** account's balance/positions as [StateFlow]s the UI can
 * collect, plus the actions to open/close positions with real funds.
 *
 * This is a structural mirror of [PaperTradingRepository] - same polling
 * cadence, same state shape, same underlying client class - but pinned to
 * [BitgetEnvironment.LIVE]. There is no Testnet/Demo toggle on this path:
 * every request this repository issues goes to Bitget's real matching
 * engine against the balance backing [credentialsStore]'s key. Paper
 * trading is the only supported way to route orders at Bitget's sandbox
 * (see [PaperTradingRepository]).
 * Reuses [PaperTradingConnectionState]/[PaperTradingResult] since those are
 * just generic "connection status" / "result" wrappers, not paper-specific.
 *
 * **Pre-trade safety.** [openPosition] and [closePosition] are the manual
 * (human-initiated) live order-placing path, as distinct from
 * [org.example.syncora.agent.DecisionLoopScheduler]'s automated one - but
 * design doc §5's guardrails don't only apply to the policy loop. When
 * [safetyGate] is supplied, every [openPosition] call goes through the same
 * [org.example.syncora.risk.PreTradeSafetyGate.evaluateEntry] the decision
 * loop uses (volatility circuit breaker + live balance + hard leverage
 * cap, possibly clamping the dispatched size down from what was requested),
 * and every [closePosition] call goes through
 * [org.example.syncora.risk.PreTradeSafetyGate.evaluateExit]. `null` is
 * only accepted for callers (e.g. tests) that intentionally don't want the
 * gate wired up; production wiring (see `SyncoraApplication`) always
 * supplies one.
 */
class LiveTradingRepository(
    private val credentialsStore: BitgetLiveCredentialsStore,
    private val symbol: String = "BTCUSDT",
    private val safetyGate: PreTradeSafetyGate? = null,
    /** Fallback mark price when there's no open position to read one off of (e.g. opening from flat) - see class kdoc. */
    private val markPriceProvider: () -> Double? = { null },
) {
    private companion object {
        const val TAG = "LiveTradingRepo"
        const val POLL_INTERVAL_MS = 4_000L
    }

    // Always reads the latest saved credentials, so a key entered after
    // construction (or cleared from settings) takes effect on the next poll.
    private val client = BitgetTradingRestClient(
        environment = { BitgetEnvironment.LIVE },
        credentialsProvider = { credentialsStore.load() },
    )

    private val _connectionState = MutableStateFlow(PaperTradingConnectionState.NOT_CONFIGURED)
    val connectionState: StateFlow<PaperTradingConnectionState> = _connectionState.asStateFlow()

    private val _balance = MutableStateFlow<PaperAccountBalance?>(null)
    val balance: StateFlow<PaperAccountBalance?> = _balance.asStateFlow()

    private val _positions = MutableStateFlow<List<PaperPosition>>(emptyList())
    val positions: StateFlow<List<PaperPosition>> = _positions.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun hasCredentials(): Boolean = credentialsStore.load() != null

    fun start() {
        stop()
        _userId.value = null
        if (!hasCredentials()) {
            _connectionState.value = PaperTradingConnectionState.NOT_CONFIGURED
            return
        }
        _connectionState.value = PaperTradingConnectionState.LOADING
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Call after credentials are saved/cleared from settings to re-evaluate connection state. */
    fun onCredentialsChanged() {
        start()
    }

    private suspend fun pollLoop() {
        while (true) {
            refreshOnce()
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun refreshOnce() {
        try {
            val latestBalance = client.fetchAccountBalance()
            val latestPositions = client.fetchAllPositions()
            _balance.value = latestBalance
            _positions.value = latestPositions
            _connectionState.value = PaperTradingConnectionState.LIVE
            _lastError.value = null
            if (_userId.value == null) {
                // UID doesn't change for a given key, so fetch it once per
                // connection rather than on every 4s poll.
                runCatching { client.fetchUserId() }.getOrNull()?.let { _userId.value = it }
            }
        } catch (e: BitgetNotAuthenticatedException) {
            _connectionState.value = PaperTradingConnectionState.NOT_CONFIGURED
            _userId.value = null
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            _connectionState.value = PaperTradingConnectionState.ERROR
            _lastError.value = friendlyErrorMessage(e)
        }
    }

    /**
     * Places a real, funded market order that *increases* exposure. Callers
     * are expected to have already confirmed with the user. Goes through
     * [safetyGate] first when one is configured (see class kdoc) - the
     * dispatched size may end up smaller than [sizeInBaseCoin] if the
     * hard leverage cap clamps it, and the call is refused outright (no
     * order sent) if the volatility circuit breaker is tripped/unknown or
     * the live Bitget balance can't be confirmed non-negative.
     */
    suspend fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        return try {
            val requestedSize = sizeInBaseCoin.toDoubleOrNull()
                ?: return PaperTradingResult.Failure("Invalid order size: $sizeInBaseCoin")

            val approvedSize = if (safetyGate != null) {
                val existing = _positions.value.firstOrNull { it.symbol == symbol }
                val markPrice = existing?.markPrice?.takeIf { it > 0.0 } ?: markPriceProvider()
                if (markPrice == null || markPrice <= 0.0) {
                    return PaperTradingResult.Failure("No live mark price available to evaluate pre-trade safety checks")
                }
                when (val result = safetyGate.evaluateEntry(existing?.total ?: 0.0, requestedSize, markPrice)) {
                    is EntrySafetyResult.Rejected -> {
                        Log.w(TAG, "Pre-trade safety gate rejected manual open: ${result.reason}")
                        return PaperTradingResult.Failure(result.reason)
                    }
                    is EntrySafetyResult.Approved -> result.approvedAddSizeInBaseCoin
                }
            } else {
                requestedSize
            }

            client.setLeverage(symbol, leverage)
            val order = client.openPosition(
                OrderTicket(symbol = symbol, side = side, sizeInBaseCoin = formatSize(approvedSize), leverage = leverage),
            )
            refreshOnce()
            PaperTradingResult.Success(order)
        } catch (e: Exception) {
            Log.w(TAG, "Open position failed: ${e.message}")
            PaperTradingResult.Failure(friendlyErrorMessage(e), e)
        }
    }

    /**
     * Places a real, funded market order that *reduces* exposure. Goes
     * through [safetyGate]'s exit check (a minimal reachability check, not
     * the volatility/leverage checks - see [PreTradeSafetyGate] kdoc for
     * why closes must never be blocked by those) when one is configured.
     */
    suspend fun closePosition(position: PaperPosition): PaperTradingResult<PlacedOrder> {
        return try {
            if (safetyGate != null) {
                when (val result = safetyGate.evaluateExit()) {
                    is ExitSafetyResult.Rejected -> {
                        Log.w(TAG, "Pre-trade safety gate rejected manual close: ${result.reason}")
                        return PaperTradingResult.Failure(result.reason)
                    }
                    ExitSafetyResult.Approved -> Unit
                }
            }
            val order = client.closePosition(
                symbol = position.symbol,
                side = position.side,
                sizeInBaseCoin = position.total.toString(),
            )
            refreshOnce()
            PaperTradingResult.Success(order)
        } catch (e: Exception) {
            Log.w(TAG, "Close position failed: ${e.message}")
            PaperTradingResult.Failure(friendlyErrorMessage(e), e)
        }
    }

    // NOTE: fixed three-decimal base-coin precision, same simplification
    // DecisionLoopScheduler/StopLossGuard make - a production build should
    // size this off BTCUSDT's actual contract precision rather than
    // hardcoding it.
    private fun formatSize(size: Double): String = String.format(java.util.Locale.US, "%.3f", size)

    private fun friendlyErrorMessage(e: Exception): String = when (e) {
        is BitgetApiException -> e.message ?: "Bitget error ${e.code}"
        is BitgetNotAuthenticatedException -> "Add a Bitget live API Key in settings first"
        else -> e.message ?: "Network error"
    }
}
