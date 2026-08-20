package org.example.test.bitget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * A fully local, offline paper-trading engine.
 *
 * Unlike the old implementation, this never talks to Bitget (or any other
 * exchange) - there is no Demo API Key, no network request, and no server
 * holding the "real" state. The account, its cash balance, deposit history,
 * and every open position are simulated entirely on-device and persisted
 * through [store]. The only external input is [markPriceProvider], a
 * lightweight hook into the app's live market-data feed used purely to
 * value open positions (mark-to-market) and fill simulated market orders -
 * no funds, real or demo, ever move anywhere.
 *
 * A new account can deposit its starting balance for free at creation time,
 * but every deposit after that is capped at once per calendar month (see
 * [deposit]), to keep the practice balance from being trivially topped up
 * mid-session.
 */
class PaperTradingRepository(
    private val store: LocalPaperTradingStore,
    private val symbol: String = "BTCUSDT",
    private val markPriceProvider: () -> Double?,
) {
    companion object {
        const val MAX_LEVERAGE = 125

        private const val MARK_TO_MARKET_INTERVAL_MS = 2_000L

        // Mirrors LocalPaperTradingStore.MAX_CLOSED_TRADES - kept in sync by
        // hand since that constant is private to the store.
        private const val MAX_CLOSED_TRADES = 500
    }

    /** Selected leverage, clamped to what's selectable. */
    private fun effectiveLeverage(requestedLeverage: Int): Int =
        requestedLeverage.coerceIn(1, MAX_LEVERAGE)

    private val _account = MutableStateFlow<PaperAccount?>(null)
    val account: StateFlow<PaperAccount?> = _account.asStateFlow()

    private val _balance = MutableStateFlow<PaperAccountBalance?>(null)
    val balance: StateFlow<PaperAccountBalance?> = _balance.asStateFlow()

    private val _positions = MutableStateFlow<List<PaperPosition>>(emptyList())
    val positions: StateFlow<List<PaperPosition>> = _positions.asStateFlow()

    private val _pendingOrders = MutableStateFlow<List<PendingLimitOrder>>(emptyList())
    val pendingOrders: StateFlow<List<PendingLimitOrder>> = _pendingOrders.asStateFlow()

    // Most-recent-first. Populated the moment a position fully closes (see
    // [closePosition]) - this is the only durable trade history the app
    // keeps, backing both the "Account History" screen and the "Profitable
    // Ratio" stat on the Paper Trading account screen.
    private val _closedTrades = MutableStateFlow<List<ClosedPaperTrade>>(emptyList())
    val closedTrades: StateFlow<List<ClosedPaperTrade>> = _closedTrades.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var markToMarketJob: Job? = null

    // Source of truth for everything money-related; _balance/_positions are
    // re-derived from this pair on every change and every mark-price tick.
    private var walletBalance: Double = 0.0
    private val openPositions = linkedMapOf<PositionSide, PersistedPaperPosition>()
    private val pendingLimitOrders = linkedMapOf<String, PendingLimitOrder>()
    private val closedTradeLog = mutableListOf<ClosedPaperTrade>() // most-recent-first

    fun hasAccount(): Boolean = _account.value != null

    /** Loads the persisted account (if any) and starts the mark-to-market loop. Call from onStart(). */
    fun start() {
        stop()
        val snapshot = store.load()
        if (snapshot != null) {
            _account.value = snapshot.account
            walletBalance = snapshot.walletBalance
            openPositions.clear()
            snapshot.positions.forEach { openPositions[it.side] = it }
            pendingLimitOrders.clear()
            snapshot.pendingOrders.forEach { pendingLimitOrders[it.id] = it }
            closedTradeLog.clear()
            closedTradeLog.addAll(snapshot.closedTrades)
        } else {
            _account.value = null
            walletBalance = 0.0
            openPositions.clear()
            pendingLimitOrders.clear()
            closedTradeLog.clear()
        }
        _closedTrades.value = closedTradeLog.toList()
        recomputeAndPublish()
        markToMarketJob = scope.launch {
            while (true) {
                delay(MARK_TO_MARKET_INTERVAL_MS)
                recomputeAndPublish()
            }
        }
    }

    /** Call from onStop(). Just pauses the mark-to-market re-valuation loop; all state is already persisted. */
    fun stop() {
        markToMarketJob?.cancel()
        markToMarketJob = null
    }

    /**
     * Creates the (single) local paper trading account with [startingBalance]
     * as its initial funding. This first funding doesn't count against the
     * monthly deposit limit - it's account setup, not a deposit.
     */
    fun createAccount(startingBalance: Double): PaperTradingResult<PaperAccount> {
        if (_account.value != null) {
            return PaperTradingResult.Failure("A paper trading account already exists on this device")
        }
        if (startingBalance <= 0.0) {
            return PaperTradingResult.Failure("Starting balance must be greater than zero")
        }
        val now = System.currentTimeMillis()
        val newAccount = PaperAccount(
            id = UUID.randomUUID().toString().take(8).uppercase(),
            createdAt = now,
            lastDepositAt = null,
        )
        _account.value = newAccount
        walletBalance = startingBalance
        openPositions.clear()
        pendingLimitOrders.clear()
        closedTradeLog.clear()
        _closedTrades.value = emptyList()
        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(newAccount)
    }

    /**
     * Adds [amount] of virtual funds to the account. Limited to once per
     * calendar month: if a deposit already landed this month, this fails
     * with a message telling the user when they can try again.
     */
    fun deposit(amount: Double): PaperTradingResult<PaperAccount> {
        val current = _account.value
            ?: return PaperTradingResult.Failure("Create a paper trading account first")
        if (amount <= 0.0) {
            return PaperTradingResult.Failure("Enter a deposit amount greater than zero")
        }
        val last = current.lastDepositAt
        if (last != null && isSameCalendarMonth(last, System.currentTimeMillis())) {
            return PaperTradingResult.Failure("Only one deposit is allowed per month. You've already used this month's deposit.")
        }
        val updated = current.copy(lastDepositAt = System.currentTimeMillis())
        _account.value = updated
        walletBalance += amount
        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(updated)
    }

    /** Null if a deposit is available right now; otherwise the timestamp (start of next month) it reopens. */
    fun nextDepositAvailableAt(): Long? {
        val last = _account.value?.lastDepositAt ?: return null
        val now = System.currentTimeMillis()
        if (!isSameCalendarMonth(last, now)) return null
        return Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun isSameCalendarMonth(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH)
    }

    /**
     * Simulates a market order filled at the current mark price. Opening
     * against an existing position on the same side averages into it
     * (matching how Bitget's own aggregated hold-side positions behave),
     * exactly like the previous Bitget-backed implementation did.
     */
    fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        if (_account.value == null) {
            return PaperTradingResult.Failure("Create a paper trading account first")
        }
        val size = sizeInBaseCoin.toDoubleOrNull()
        if (size == null || size <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid size")
        }
        val price = markPriceProvider()
        if (price == null || price <= 0.0) {
            return PaperTradingResult.Failure("No live price yet - try again in a moment")
        }
        val safeLeverage = effectiveLeverage(leverage)
        val marginRequired = (size * price) / safeLeverage
        val usedMargin = openPositions.values.sumOf { it.marginSize } + pendingLimitOrders.values.sumOf { it.marginReserved }
        val available = walletBalance - usedMargin
        if (marginRequired > available) {
            return PaperTradingResult.Failure("Insufficient available balance for this order")
        }

        val existing = openPositions[side]
        openPositions[side] = if (existing == null) {
            PersistedPaperPosition(side = side, total = size, entryPrice = price, leverage = safeLeverage, marginSize = marginRequired)
        } else {
            val newTotal = existing.total + size
            val newEntryPrice = ((existing.entryPrice * existing.total) + (price * size)) / newTotal
            existing.copy(total = newTotal, entryPrice = newEntryPrice, leverage = safeLeverage, marginSize = existing.marginSize + marginRequired)
        }

        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(simulatedOrder())
    }

    /**
     * Same as [openPosition], but [sizeInUsdt] is a USDT notional value (e.g.
     * "how much position do I want, in dollars") rather than a base-coin
     * quantity. This is what the quick-trade drawer's "Position size ...
     * USDT" field should call - passing a small USDT amount like "2"
     * straight into [openPosition] instead would be silently interpreted as
     * 2 BTC, not $2, requiring a wildly oversized margin and failing with
     * "Insufficient available balance" even on a small account.
     */
    fun openPositionByNotional(side: PositionSide, sizeInUsdt: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        val notional = sizeInUsdt.toDoubleOrNull()
        if (notional == null || notional <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid size")
        }
        val price = markPriceProvider()
        if (price == null || price <= 0.0) {
            return PaperTradingResult.Failure("No live price yet - try again in a moment")
        }
        return openPosition(side, (notional / price).toString(), leverage)
    }

    /** Notional-USDT counterpart of [placeLimitOrder] - see [openPositionByNotional]. */
    fun placeLimitOrderByNotional(
        side: PositionSide,
        sizeInUsdt: String,
        leverage: Int,
        limitPriceInput: String,
    ): PaperTradingResult<PlacedOrder> {
        val notional = sizeInUsdt.toDoubleOrNull()
        if (notional == null || notional <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid size")
        }
        val limitPrice = limitPriceInput.toDoubleOrNull()
        if (limitPrice == null || limitPrice <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid limit price")
        }
        return placeLimitOrder(side, (notional / limitPrice).toString(), leverage, limitPriceInput)
    }

    /**
     * Places a resting limit order: it does not fill immediately (unlike
     * [openPosition]), but sits in [pendingLimitOrders] until a mark-price
     * tick reaches [limitPriceInput] (see [tryFillPendingOrders]), at which
     * point it opens/adds to a position exactly like a market order would.
     * Its margin is reserved up front so it counts against the account's
     * available balance while it's resting, the same as an open position's
     * margin does.
     */
    fun placeLimitOrder(
        side: PositionSide,
        sizeInBaseCoin: String,
        leverage: Int,
        limitPriceInput: String,
    ): PaperTradingResult<PlacedOrder> {
        if (_account.value == null) {
            return PaperTradingResult.Failure("Create a paper trading account first")
        }
        val size = sizeInBaseCoin.toDoubleOrNull()
        if (size == null || size <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid size")
        }
        val limitPrice = limitPriceInput.toDoubleOrNull()
        if (limitPrice == null || limitPrice <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid limit price")
        }
        val safeLeverage = effectiveLeverage(leverage)
        val marginRequired = (size * limitPrice) / safeLeverage
        val usedMargin = openPositions.values.sumOf { it.marginSize } + pendingLimitOrders.values.sumOf { it.marginReserved }
        val available = walletBalance - usedMargin
        if (marginRequired > available) {
            return PaperTradingResult.Failure("Insufficient available balance for this order")
        }

        val order = PendingLimitOrder(
            id = UUID.randomUUID().toString(),
            side = side,
            sizeInBaseCoin = size,
            leverage = safeLeverage,
            limitPrice = limitPrice,
            marginReserved = marginRequired,
            createdAt = System.currentTimeMillis(),
        )
        pendingLimitOrders[order.id] = order

        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(PlacedOrder(orderId = order.id, clientOrderId = "local-${order.id}"))
    }

    /** Cancels a still-resting limit order, releasing the margin it had reserved. No-op (returns Failure) if it already filled or never existed. */
    fun cancelLimitOrder(orderId: String): PaperTradingResult<Unit> {
        if (pendingLimitOrders.remove(orderId) == null) {
            return PaperTradingResult.Failure("That order is no longer pending")
        }
        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(Unit)
    }

    /**
     * Fills any resting limit order whose price has been reached by
     * [markPrice] - a buy/long limit fills once price drops to or below it,
     * a sell/short limit fills once price rises to or above it - opening or
     * adding to the position on that side at the order's limit price (never
     * worse, matching how a real limit order guarantees its price).
     */
    private fun tryFillPendingOrders(markPrice: Double) {
        if (pendingLimitOrders.isEmpty()) return
        val toFill = pendingLimitOrders.values.filter { order ->
            when (order.side) {
                PositionSide.LONG -> markPrice <= order.limitPrice
                PositionSide.SHORT -> markPrice >= order.limitPrice
            }
        }
        if (toFill.isEmpty()) return

        for (order in toFill) {
            pendingLimitOrders.remove(order.id)
            val fillPrice = order.limitPrice
            val existing = openPositions[order.side]
            openPositions[order.side] = if (existing == null) {
                PersistedPaperPosition(
                    side = order.side,
                    total = order.sizeInBaseCoin,
                    entryPrice = fillPrice,
                    leverage = order.leverage,
                    marginSize = order.marginReserved,
                )
            } else {
                val newTotal = existing.total + order.sizeInBaseCoin
                val newEntryPrice = ((existing.entryPrice * existing.total) + (fillPrice * order.sizeInBaseCoin)) / newTotal
                existing.copy(
                    total = newTotal,
                    entryPrice = newEntryPrice,
                    leverage = order.leverage,
                    marginSize = existing.marginSize + order.marginReserved,
                )
            }
        }
        persist()
    }

    /** Closes the full open position on [position]'s side at the current mark price, realizing its PnL into the wallet balance. */
    fun closePosition(position: PaperPosition): PaperTradingResult<PlacedOrder> {
        if (_account.value == null) {
            return PaperTradingResult.Failure("Create a paper trading account first")
        }
        val existing = openPositions[position.side]
            ?: return PaperTradingResult.Failure("That position is no longer open")
        val price = markPriceProvider()
        if (price == null || price <= 0.0) {
            return PaperTradingResult.Failure("No live price yet - try again in a moment")
        }
        val direction = if (position.side == PositionSide.LONG) 1.0 else -1.0
        val realizedPnl = (price - existing.entryPrice) * existing.total * direction

        walletBalance += realizedPnl
        openPositions.remove(position.side)

        closedTradeLog.add(
            0,
            ClosedPaperTrade(
                symbol = symbol,
                side = position.side,
                size = existing.total,
                entryPrice = existing.entryPrice,
                exitPrice = price,
                leverage = existing.leverage,
                realizedPnl = realizedPnl,
                closedAt = System.currentTimeMillis(),
            ),
        )
        if (closedTradeLog.size > MAX_CLOSED_TRADES) {
            closedTradeLog.subList(MAX_CLOSED_TRADES, closedTradeLog.size).clear()
        }
        _closedTrades.value = closedTradeLog.toList()

        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(simulatedOrder())
    }

    /** Wipes the local account, balance, and every open position so the user can start fresh. */
    fun resetAccount() {
        _account.value = null
        walletBalance = 0.0
        openPositions.clear()
        pendingLimitOrders.clear()
        closedTradeLog.clear()
        _closedTrades.value = emptyList()
        store.clear()
        recomputeAndPublish()
    }

    private fun simulatedOrder(): PlacedOrder {
        val id = UUID.randomUUID().toString()
        return PlacedOrder(orderId = id, clientOrderId = "local-$id")
    }

    private fun recomputeAndPublish() {
        val account = _account.value
        val price = markPriceProvider()
        if (price != null && price > 0.0) {
            tryFillPendingOrders(price)
        }
        val livePositions = openPositions.map { (side, pos) ->
            val markPrice = price ?: pos.entryPrice
            val direction = if (side == PositionSide.LONG) 1.0 else -1.0
            val unrealizedPnl = (markPrice - pos.entryPrice) * pos.total * direction
            PaperPosition(
                symbol = symbol,
                side = side,
                total = pos.total,
                available = pos.total,
                entryPrice = pos.entryPrice,
                markPrice = markPrice,
                leverage = pos.leverage,
                marginSize = pos.marginSize,
                unrealizedPnl = unrealizedPnl,
            )
        }
        _positions.value = livePositions
        _pendingOrders.value = pendingLimitOrders.values.toList()
        _lastError.value = null

        _balance.value = if (account == null) {
            null
        } else {
            val usedMargin = openPositions.values.sumOf { it.marginSize } + pendingLimitOrders.values.sumOf { it.marginReserved }
            val unrealizedTotal = livePositions.sumOf { it.unrealizedPnl }
            PaperAccountBalance(
                marginCoin = "USDT",
                available = walletBalance - usedMargin,
                equity = walletBalance + unrealizedTotal,
                unrealizedPnl = unrealizedTotal,
            )
        }
    }

    private fun persist() {
        val account = _account.value ?: return
        store.save(
            PaperTradingSnapshot(
                account = account,
                walletBalance = walletBalance,
                positions = openPositions.values.toList(),
                pendingOrders = pendingLimitOrders.values.toList(),
                closedTrades = closedTradeLog.toList(),
            ),
        )
    }
}
