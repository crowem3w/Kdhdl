package org.example.test.bitget

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

enum class PaperTradingConnectionState {
    NOT_CONFIGURED,
    LOADING,
    LIVE,
    ERROR,
}

/**
 * Owns the polling loop against [BitgetPaperTradingRestClient] and exposes
 * the demo account's balance/positions as [StateFlow]s the UI can collect.
 *
 * All state - balance, positions, order history - lives server-side on
 * Bitget under the configured Demo API Key, so this repository is just a
 * thin, disposable window onto it: closing and reopening the app simply
 * re-polls the same account and the same state comes back.
 */
class PaperTradingRepository(
    private val credentialsStore: BitgetCredentialsStore,
    private val symbol: String = "BTCUSDT",
) {
    private companion object {
        const val TAG = "PaperTradingRepo"
        const val POLL_INTERVAL_MS = 4_000L
    }

    // Always reads the latest saved credentials, so a key entered after
    // construction (or cleared from settings) takes effect on the next poll.
    private val client = BitgetPaperTradingRestClient(credentialsProvider = { credentialsStore.load() })

    private val _connectionState = MutableStateFlow(PaperTradingConnectionState.NOT_CONFIGURED)
    val connectionState: StateFlow<PaperTradingConnectionState> = _connectionState.asStateFlow()

    private val _balance = MutableStateFlow<PaperAccountBalance?>(null)
    val balance: StateFlow<PaperAccountBalance?> = _balance.asStateFlow()

    private val _positions = MutableStateFlow<List<PaperPosition>>(emptyList())
    val positions: StateFlow<List<PaperPosition>> = _positions.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun hasCredentials(): Boolean = credentialsStore.load() != null

    fun start() {
        stop()
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
        } catch (e: BitgetPaperTradingRestClient.NotAuthenticatedException) {
            _connectionState.value = PaperTradingConnectionState.NOT_CONFIGURED
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            _connectionState.value = PaperTradingConnectionState.ERROR
            _lastError.value = friendlyErrorMessage(e)
        }
    }

    suspend fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        return try {
            client.setLeverage(symbol, leverage)
            val order = client.openPosition(
                OrderTicket(symbol = symbol, side = side, sizeInBaseCoin = sizeInBaseCoin, leverage = leverage),
            )
            refreshOnce()
            PaperTradingResult.Success(order)
        } catch (e: Exception) {
            Log.w(TAG, "Open position failed: ${e.message}")
            PaperTradingResult.Failure(friendlyErrorMessage(e), e)
        }
    }

    suspend fun closePosition(position: PaperPosition): PaperTradingResult<PlacedOrder> {
        return try {
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

    private fun friendlyErrorMessage(e: Exception): String = when (e) {
        is BitgetPaperTradingRestClient.BitgetApiException -> e.message ?: "Bitget error ${e.code}"
        is BitgetPaperTradingRestClient.NotAuthenticatedException -> "Add a Bitget Demo API Key in settings first"
        else -> e.message ?: "Network error"
    }
}
