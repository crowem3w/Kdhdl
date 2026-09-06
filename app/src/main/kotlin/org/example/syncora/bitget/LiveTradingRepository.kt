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
import org.example.syncora.log.AppLog
import org.example.syncora.log.LogLevel

class LiveTradingRepository(
    private val credentialsStore: BitgetLiveCredentialsStore,
    private val symbol: String = "BTCUSDT",
) {
    private companion object {
        const val TAG = "LiveTradingRepo"
        const val POLL_INTERVAL_MS = 4_000L
    }

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

    @Volatile
    private var active = false

    /**
     * Controls whether this account is the one currently selected by [org.example.syncora.account.AccountManager].
     * When set to false, polling is paused (via [stop]) and order-placement methods are rejected. Stored
     * credentials are left untouched, so live trading resumes exactly where it left off when re-activated -
     * this is what prevents accidental live execution while Paper mode is selected.
     */
    fun setActive(enabled: Boolean) {
        active = enabled
        if (!enabled) stop()
    }

    fun isActive(): Boolean = active

    fun hasCredentials(): Boolean = credentialsStore.load() != null

    fun start() {
        stop()
        _userId.value = null
        if (!active) {
            _connectionState.value = PaperTradingConnectionState.NOT_CONFIGURED
            return
        }
        if (!hasCredentials()) {
            _connectionState.value = PaperTradingConnectionState.NOT_CONFIGURED
            AppLog.account(LogLevel.WARNING, "Live account selected but no Bitget API key is saved yet")
            return
        }
        _connectionState.value = PaperTradingConnectionState.LOADING
        AppLog.account(LogLevel.INFO, "Connecting to Bitget live account ($symbol)...")
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

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
        val previousState = _connectionState.value
        try {
            val latestBalance = client.fetchAccountBalance()
            val latestPositions = client.fetchAllPositions()
            _balance.value = latestBalance
            _positions.value = latestPositions
            _connectionState.value = PaperTradingConnectionState.LIVE
            _lastError.value = null
            if (previousState != PaperTradingConnectionState.LIVE) {
                AppLog.account(LogLevel.SUCCESS, "Bitget live account connected - balance and positions syncing")
            }
            if (_userId.value == null) {
                runCatching { client.fetchUserId() }.getOrNull()?.let { _userId.value = it }
            }
        } catch (e: BitgetNotAuthenticatedException) {
            _connectionState.value = PaperTradingConnectionState.NOT_CONFIGURED
            _userId.value = null
            if (previousState != PaperTradingConnectionState.NOT_CONFIGURED) {
                AppLog.account(LogLevel.WARNING, "Bitget live account not authenticated - check the saved API key")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            _connectionState.value = PaperTradingConnectionState.ERROR
            _lastError.value = friendlyErrorMessage(e)
            if (previousState != PaperTradingConnectionState.ERROR) {
                AppLog.account(LogLevel.ERROR, "Bitget live connectivity lost: ${friendlyErrorMessage(e)}")
            }
        }
    }

    suspend fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        if (!active) {
            return PaperTradingResult.Failure("Switch to Live trading mode to trade this account")
        }
        AppLog.trading(LogLevel.INFO, "Submitting LIVE $side order - $symbol size=$sizeInBaseCoin leverage=${leverage}x")
        return try {
            client.setLeverage(symbol, leverage)
            val order = client.openPosition(
                OrderTicket(symbol = symbol, side = side, sizeInBaseCoin = sizeInBaseCoin, leverage = leverage),
            )
            refreshOnce()
            AppLog.trading(LogLevel.SUCCESS, "LIVE $side order filled - $symbol size=$sizeInBaseCoin (order ${order.orderId})")
            PaperTradingResult.Success(order)
        } catch (e: Exception) {
            Log.w(TAG, "Open position failed: ${e.message}")
            AppLog.trading(LogLevel.ERROR, "LIVE $side order rejected - $symbol: ${friendlyErrorMessage(e)}")
            PaperTradingResult.Failure(friendlyErrorMessage(e), e)
        }
    }

    suspend fun closePosition(position: PaperPosition): PaperTradingResult<PlacedOrder> {
        if (!active) {
            return PaperTradingResult.Failure("Switch to Live trading mode to trade this account")
        }
        AppLog.trading(LogLevel.INFO, "Closing LIVE position - ${position.symbol} ${position.side} size=${position.total}")
        return try {
            val order = client.closePosition(
                symbol = position.symbol,
                side = position.side,
                sizeInBaseCoin = position.total.toString(),
            )
            refreshOnce()
            AppLog.trading(LogLevel.SUCCESS, "LIVE position closed - ${position.symbol} (order ${order.orderId})")
            PaperTradingResult.Success(order)
        } catch (e: Exception) {
            Log.w(TAG, "Close position failed: ${e.message}")
            AppLog.trading(LogLevel.ERROR, "LIVE close failed - ${position.symbol}: ${friendlyErrorMessage(e)}")
            PaperTradingResult.Failure(friendlyErrorMessage(e), e)
        }
    }

    private fun friendlyErrorMessage(e: Exception): String = when (e) {
        is BitgetApiException -> e.message ?: "Bitget error ${e.code}"
        is BitgetNotAuthenticatedException -> "Add a Bitget live API Key in settings first"
        else -> e.message ?: "Network error"
    }
}
