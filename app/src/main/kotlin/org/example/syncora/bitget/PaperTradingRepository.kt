package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class PaperTradingRepository(
    private val store: LocalPaperTradingStore,
    private val symbol: String = "BTCUSDT",
    private val markPriceProvider: () -> Double?,
    private val feeRateClient: BitgetFeeRateClient = BitgetFeeRateClient(),
    private val feeProductType: String = "usdt-futures",
    private val feeRateCredentialsProvider: (() -> BitgetCredentials?)? = null,
    private val fundingRateClient: BitgetFundingRateClient = BitgetFundingRateClient(),
    private val depthSnapshotProvider: () -> DepthSnapshot? = { null },
    private val tradeFlow: Flow<PublicTrade> = emptyFlow(),
) {
    companion object {
        const val MAX_LEVERAGE = 125
        private const val TAG = "PaperTradingRepository"

        private const val MARK_TO_MARKET_INTERVAL_MS = 2_000L

        private const val FEE_RATE_REFRESH_INTERVAL_MS = 15 * 60_000L

        private const val MAX_CLOSED_TRADES = 500
    }

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

    private val _closedTrades = MutableStateFlow<List<ClosedPaperTrade>>(emptyList())
    val closedTrades: StateFlow<List<ClosedPaperTrade>> = _closedTrades.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _fundingPayments = MutableStateFlow<List<FundingPayment>>(emptyList())
    val fundingPayments: StateFlow<List<FundingPayment>> = _fundingPayments.asStateFlow()

    private val _currentFunding = MutableStateFlow<FundingRateInfo?>(null)
    val currentFunding: StateFlow<FundingRateInfo?> = _currentFunding.asStateFlow()

    private val _feeRates = MutableStateFlow(FeeRates.DEFAULT)
    val feeRates: StateFlow<FeeRates> = _feeRates.asStateFlow()

    private val _latencyConfig = MutableStateFlow(store.loadLatencyConfig())
    val latencyConfig: StateFlow<LatencyConfig> = _latencyConfig.asStateFlow()

    private val latencySimulator = LatencySimulator(
        markPriceProvider = markPriceProvider,
        depthSnapshotProvider = depthSnapshotProvider,
        configProvider = { _latencyConfig.value },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var markToMarketJob: Job? = null
    private var feeRateJob: Job? = null
    private var tradeStreamJob: Job? = null
    private var fundingJob: Job? = null

    private val stateLock = Any()
    private var walletBalance: Double = 0.0
    private val openPositions = linkedMapOf<PositionSide, PersistedPaperPosition>()
    private val pendingLimitOrders = linkedMapOf<String, PendingLimitOrder>()
    private val closedTradeLog = mutableListOf<ClosedPaperTrade>()
    private val fundingPaymentLog = mutableListOf<FundingPayment>()
    private var lastFundingSettledAt: Long? = null

    private val queueTracker = QueuePositionTracker()

    fun hasAccount(): Boolean = _account.value != null

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
            fundingPaymentLog.clear()
            fundingPaymentLog.addAll(snapshot.fundingPayments)
            lastFundingSettledAt = snapshot.lastFundingSettledAt
        } else {
            _account.value = null
            walletBalance = 0.0
            openPositions.clear()
            pendingLimitOrders.clear()
            closedTradeLog.clear()
            fundingPaymentLog.clear()
            lastFundingSettledAt = null
        }
        _closedTrades.value = closedTradeLog.toList()
        _fundingPayments.value = fundingPaymentLog.toList()
        queueTracker.untrackAll()
        pendingLimitOrders.values.forEach { order ->
            queueTracker.track(order.id, order.side, order.limitPrice, order.queueAheadVolume)
        }
        recomputeAndPublish()
        markToMarketJob = scope.launch {
            while (true) {
                delay(MARK_TO_MARKET_INTERVAL_MS)
                recomputeAndPublish()
            }
        }
        feeRateJob = scope.launch {
            while (true) {
                refreshFeeRates()
                delay(FEE_RATE_REFRESH_INTERVAL_MS)
            }
        }
        tradeStreamJob = tradeFlow
            .onEach { trade -> onPublicTradePrinted(trade) }
            .catch { e -> Log.w(TAG, "Public trade stream error; queue-position tracking paused", e) }
            .launchIn(scope)
        fundingJob = scope.launch { runFundingLoop() }
    }

    fun stop() {
        markToMarketJob?.cancel()
        markToMarketJob = null
        feeRateJob?.cancel()
        feeRateJob = null
        tradeStreamJob?.cancel()
        tradeStreamJob = null
        fundingJob?.cancel()
        fundingJob = null
    }

    private suspend fun runFundingLoop() {
        val now = System.currentTimeMillis()
        val since = lastFundingSettledAt ?: (_account.value?.createdAt ?: now)
        val missed = FundingSchedule.settlementsBetween(since, now)
        for (timestampMs in missed) {
            runCatching { settleFunding(timestampMs) }
                .onFailure { e -> Log.w(TAG, "Failed to catch up funding settlement at $timestampMs", e) }
        }
        while (true) {
            val next = FundingSchedule.nextSettlement(System.currentTimeMillis())
            val waitMs = (next - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(waitMs)
            runCatching { settleFunding(next) }
                .onFailure { e -> Log.w(TAG, "Failed to apply funding settlement at $next", e) }
        }
    }

    private suspend fun settleFunding(timestampMs: Long) {
        val rateInfo = runCatching {
            fundingRateClient.fetchSettledFundingRate(timestampMs, symbol = symbol, productType = feeProductType)
        }.getOrNull() ?: runCatching {
            fundingRateClient.fetchCurrentFundingRate(symbol = symbol, productType = feeProductType)
        }.getOrNull()
        if (rateInfo != null) _currentFunding.value = rateInfo

        if (rateInfo != null) {
            val rate = rateInfo.fundingRate
            val markPrice = markPriceProvider()
            val newPayments = synchronized(stateLock) {
                if (openPositions.isEmpty()) return@synchronized emptyList()
                val payments = mutableListOf<FundingPayment>()
                for ((side, pos) in openPositions) {
                    val price = markPrice?.takeIf { it > 0.0 } ?: pos.entryPrice
                    val notional = pos.total * price
                    val direction = if (side == PositionSide.LONG) 1.0 else -1.0
                    val amount = notional * rate * direction
                    openPositions[side] = pos.copy(fundingPaidSoFar = pos.fundingPaidSoFar + amount)
                    walletBalance -= amount
                    payments.add(
                        FundingPayment(
                            symbol = symbol,
                            side = side,
                            fundingRate = rate,
                            positionNotional = notional,
                            amount = amount,
                            settledAt = timestampMs,
                        ),
                    )
                }
                fundingPaymentLog.addAll(0, payments.asReversed())
                if (fundingPaymentLog.size > MAX_CLOSED_TRADES) {
                    fundingPaymentLog.subList(MAX_CLOSED_TRADES, fundingPaymentLog.size).clear()
                }
                payments
            }
            if (newPayments.isNotEmpty()) {
                _fundingPayments.value = synchronized(stateLock) { fundingPaymentLog.toList() }
            }
        }

        lastFundingSettledAt = timestampMs
        persist()
        recomputeAndPublish()
    }

    suspend fun refreshFeeRates() {
        val credentials = feeRateCredentialsProvider?.invoke()
        if (credentials != null) {
            val accountRates = runCatching {
                feeRateClient.fetchAccountFeeRates(credentials, symbol = symbol)
            }.getOrNull()
            if (accountRates != null) {
                _feeRates.value = accountRates
                return
            }
        }
        runCatching {
            feeRateClient.fetchStandardFeeRates(symbol = symbol, productType = feeProductType)
        }.onSuccess { _feeRates.value = it }
    }

    fun setLatencyConfig(config: LatencyConfig) {
        val coerced = config.coerced()
        _latencyConfig.value = coerced
        store.saveLatencyConfig(coerced)
    }

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
        synchronized(stateLock) {
            walletBalance = startingBalance
            openPositions.clear()
            pendingLimitOrders.clear()
            closedTradeLog.clear()
        }
        queueTracker.untrackAll()
        _closedTrades.value = emptyList()
        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(newAccount)
    }

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
        synchronized(stateLock) { walletBalance += amount }
        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(updated)
    }

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

    suspend fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        if (_account.value == null) {
            return PaperTradingResult.Failure("Create a paper trading account first")
        }
        val size = sizeInBaseCoin.toDoubleOrNull()
        if (size == null || size <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid size")
        }
        val decisionTimeMs = System.currentTimeMillis()
        val execution = latencySimulator.captureExecutionState(decisionTimeMs)
        val price = execution.marketState.markPrice
        if (price == null || price <= 0.0) {
            return PaperTradingResult.Failure("No live price yet - try again in a moment")
        }
        val bookWalk = execution.marketState.depthSnapshot?.let { OrderBookWalker.walk(it, side, size) }
        val fillPrice = bookWalk?.vwapPrice ?: price
        return fillImmediately(side, size, effectiveLeverage(leverage), fillPrice, FeeClassification.TAKER, bookWalk, execution.appliedDelayMs)
    }

    private fun fillImmediately(
        side: PositionSide,
        size: Double,
        safeLeverage: Int,
        fillPrice: Double,
        classification: FeeClassification,
        fillInfo: BookWalkResult? = null,
        appliedLatencyMs: Long = 0L,
    ): PaperTradingResult<PlacedOrder> {
        val marginRequired = (size * fillPrice) / safeLeverage
        val fee = estimateFee(size, fillPrice, classification)
        synchronized(stateLock) {
            val usedMargin = openPositions.values.sumOf { it.marginSize } + pendingLimitOrders.values.sumOf { it.marginReserved }
            val available = walletBalance - usedMargin
            if (marginRequired + fee > available) {
                return PaperTradingResult.Failure("Insufficient available balance for this order")
            }

            val existing = openPositions[side]
            openPositions[side] = if (existing == null) {
                PersistedPaperPosition(side = side, total = size, entryPrice = fillPrice, leverage = safeLeverage, marginSize = marginRequired, feesPaidSoFar = fee)
            } else {
                val newTotal = existing.total + size
                val newEntryPrice = ((existing.entryPrice * existing.total) + (fillPrice * size)) / newTotal
                existing.copy(
                    total = newTotal,
                    entryPrice = newEntryPrice,
                    leverage = safeLeverage,
                    marginSize = existing.marginSize + marginRequired,
                    feesPaidSoFar = existing.feesPaidSoFar + fee,
                )
            }
            walletBalance -= fee
        }

        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(simulatedOrder(fillInfo, appliedLatencyMs))
    }

    private fun estimateFee(sizeInBaseCoin: Double, price: Double, classification: FeeClassification): Double {
        val rates = _feeRates.value
        val rate = if (classification == FeeClassification.MAKER) rates.makerRate else rates.takerRate
        return sizeInBaseCoin * price * rate
    }

    suspend fun openPositionByNotional(side: PositionSide, sizeInUsdt: String, leverage: Int): PaperTradingResult<PlacedOrder> {
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

    suspend fun placeLimitOrderByNotional(
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

    suspend fun placeLimitOrder(
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

        val decisionTimeMs = System.currentTimeMillis()
        val execution = latencySimulator.captureExecutionState(decisionTimeMs)

        val currentPrice = execution.marketState.markPrice
        val crossesImmediately = currentPrice != null && when (side) {
            PositionSide.LONG -> limitPrice >= currentPrice
            PositionSide.SHORT -> limitPrice <= currentPrice
        }
        if (crossesImmediately) {
            return fillImmediately(side, size, safeLeverage, limitPrice, FeeClassification.TAKER, appliedLatencyMs = execution.appliedDelayMs)
        }

        val queueAheadVolume = execution.marketState.depthSnapshot?.let { OrderBookWalker.queueAheadVolume(it, side, limitPrice) } ?: 0.0

        val marginRequired = (size * limitPrice) / safeLeverage
        val order: PendingLimitOrder
        synchronized(stateLock) {
            val usedMargin = openPositions.values.sumOf { it.marginSize } + pendingLimitOrders.values.sumOf { it.marginReserved }
            val available = walletBalance - usedMargin
            if (marginRequired > available) {
                return PaperTradingResult.Failure("Insufficient available balance for this order")
            }

            order = PendingLimitOrder(
                id = UUID.randomUUID().toString(),
                side = side,
                sizeInBaseCoin = size,
                leverage = safeLeverage,
                limitPrice = limitPrice,
                marginReserved = marginRequired,
                createdAt = System.currentTimeMillis(),
                queueAheadVolume = queueAheadVolume,
            )
            pendingLimitOrders[order.id] = order
        }
        queueTracker.track(order.id, order.side, order.limitPrice, order.queueAheadVolume)

        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(PlacedOrder(orderId = order.id, clientOrderId = "local-${order.id}"))
    }

    fun cancelLimitOrder(orderId: String): PaperTradingResult<Unit> {
        val removed = synchronized(stateLock) { pendingLimitOrders.remove(orderId) }
        if (removed == null) {
            return PaperTradingResult.Failure("That order is no longer pending")
        }
        queueTracker.untrack(orderId)
        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(Unit)
    }

    private fun tryFillPendingOrders(markPrice: Double) {
        if (pendingLimitOrders.isEmpty()) return
        val toFill = synchronized(stateLock) {
            val matched = pendingLimitOrders.values.filter { order ->
                when (order.side) {
                    PositionSide.LONG -> markPrice <= order.limitPrice
                    PositionSide.SHORT -> markPrice >= order.limitPrice
                }
            }
            for (order in matched) {
                pendingLimitOrders.remove(order.id)
                applyMakerFillLocked(order)
            }
            matched
        }
        if (toFill.isEmpty()) return
        toFill.forEach { queueTracker.untrack(it.id) }
        persist()
    }

    private fun onPublicTradePrinted(trade: PublicTrade) {
        if (pendingLimitOrders.isEmpty()) return
        val filledIds = queueTracker.onTrade(trade)
        if (filledIds.isEmpty()) return
        synchronized(stateLock) {
            for (id in filledIds) {
                val order = pendingLimitOrders.remove(id) ?: continue
                applyMakerFillLocked(order)
            }
        }
        persist()
        recomputeAndPublish()
    }

    private fun applyMakerFillLocked(order: PendingLimitOrder) {
        val fillPrice = order.limitPrice
        val fee = estimateFee(order.sizeInBaseCoin, fillPrice, FeeClassification.MAKER)
        val existing = openPositions[order.side]
        openPositions[order.side] = if (existing == null) {
            PersistedPaperPosition(
                side = order.side,
                total = order.sizeInBaseCoin,
                entryPrice = fillPrice,
                leverage = order.leverage,
                marginSize = order.marginReserved,
                feesPaidSoFar = fee,
            )
        } else {
            val newTotal = existing.total + order.sizeInBaseCoin
            val newEntryPrice = ((existing.entryPrice * existing.total) + (fillPrice * order.sizeInBaseCoin)) / newTotal
            existing.copy(
                total = newTotal,
                entryPrice = newEntryPrice,
                leverage = order.leverage,
                marginSize = existing.marginSize + order.marginReserved,
                feesPaidSoFar = existing.feesPaidSoFar + fee,
            )
        }
        walletBalance -= fee
    }

    suspend fun closePosition(position: PaperPosition): PaperTradingResult<PlacedOrder> {
        if (_account.value == null) {
            return PaperTradingResult.Failure("Create a paper trading account first")
        }
        val existing = openPositions[position.side]
            ?: return PaperTradingResult.Failure("That position is no longer open")
        val decisionTimeMs = System.currentTimeMillis()
        val execution = latencySimulator.captureExecutionState(decisionTimeMs)
        val price = execution.marketState.markPrice
        if (price == null || price <= 0.0) {
            return PaperTradingResult.Failure("No live price yet - try again in a moment")
        }
        val closeWalkSide = if (position.side == PositionSide.LONG) PositionSide.SHORT else PositionSide.LONG
        val bookWalk = execution.marketState.depthSnapshot?.let { OrderBookWalker.walk(it, closeWalkSide, existing.total) }
        val exitPrice = bookWalk?.vwapPrice ?: price

        val direction = if (position.side == PositionSide.LONG) 1.0 else -1.0
        val grossPnl = (exitPrice - existing.entryPrice) * existing.total * direction
        val exitFee = estimateFee(existing.total, exitPrice, FeeClassification.TAKER)
        val totalFees = existing.feesPaidSoFar + exitFee
        val realizedPnl = grossPnl - exitFee
        val totalFunding = existing.fundingPaidSoFar

        synchronized(stateLock) {
            walletBalance += realizedPnl
            openPositions.remove(position.side)

            closedTradeLog.add(
                0,
                ClosedPaperTrade(
                    symbol = symbol,
                    side = position.side,
                    size = existing.total,
                    entryPrice = existing.entryPrice,
                    exitPrice = exitPrice,
                    leverage = existing.leverage,
                    realizedPnl = realizedPnl,
                    closedAt = System.currentTimeMillis(),
                    totalFeesPaid = totalFees,
                    totalFundingPaid = totalFunding,
                ),
            )
            if (closedTradeLog.size > MAX_CLOSED_TRADES) {
                closedTradeLog.subList(MAX_CLOSED_TRADES, closedTradeLog.size).clear()
            }
        }
        _closedTrades.value = synchronized(stateLock) { closedTradeLog.toList() }

        persist()
        recomputeAndPublish()
        return PaperTradingResult.Success(simulatedOrder(bookWalk, execution.appliedDelayMs))
    }

    fun resetAccount() {
        _account.value = null
        queueTracker.untrackAll()
        synchronized(stateLock) {
            walletBalance = 0.0
            openPositions.clear()
            pendingLimitOrders.clear()
            closedTradeLog.clear()
            fundingPaymentLog.clear()
        }
        lastFundingSettledAt = null
        _closedTrades.value = emptyList()
        _fundingPayments.value = emptyList()
        store.clear()
        recomputeAndPublish()
    }

    private fun simulatedOrder(fillInfo: BookWalkResult? = null, appliedLatencyMs: Long = 0L): PlacedOrder {
        val id = UUID.randomUUID().toString()
        return PlacedOrder(orderId = id, clientOrderId = "local-$id", fillInfo = fillInfo, appliedLatencyMs = appliedLatencyMs)
    }

    private fun recomputeAndPublish() {
        val account = _account.value
        val price = markPriceProvider()
        if (price != null && price > 0.0) {
            tryFillPendingOrders(price)
        }
        val (livePositions, pendingSnapshot, balance) = synchronized(stateLock) {
            val positionsSnapshot = openPositions.map { (side, pos) ->
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
                    feesPaidSoFar = pos.feesPaidSoFar,
                    fundingPaidSoFar = pos.fundingPaidSoFar,
                )
            }
            val pendingOrdersSnapshot = pendingLimitOrders.values.toList()
            val balanceSnapshot = if (account == null) {
                null
            } else {
                val usedMargin = openPositions.values.sumOf { it.marginSize } + pendingLimitOrders.values.sumOf { it.marginReserved }
                val unrealizedTotal = positionsSnapshot.sumOf { it.unrealizedPnl }
                PaperAccountBalance(
                    marginCoin = "USDT",
                    available = walletBalance - usedMargin,
                    equity = walletBalance + unrealizedTotal,
                    unrealizedPnl = unrealizedTotal,
                )
            }
            Triple(positionsSnapshot, pendingOrdersSnapshot, balanceSnapshot)
        }
        _positions.value = livePositions
        _pendingOrders.value = pendingSnapshot
        _lastError.value = null
        _balance.value = balance
    }

    private fun persist() {
        val account = _account.value ?: return
        val snapshot = synchronized(stateLock) {
            PaperTradingSnapshot(
                account = account,
                walletBalance = walletBalance,
                positions = openPositions.values.toList(),
                pendingOrders = pendingLimitOrders.values.toList(),
                closedTrades = closedTradeLog.toList(),
                fundingPayments = fundingPaymentLog.toList(),
                lastFundingSettledAt = lastFundingSettledAt,
            )
        }
        store.save(snapshot)
    }
}
