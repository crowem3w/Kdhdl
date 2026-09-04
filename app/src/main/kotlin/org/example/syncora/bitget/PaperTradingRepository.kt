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

/**
 * A fully local, offline paper-trading engine.
 *
 * Unlike the old implementation, this never talks to Bitget's (or any other
 * exchange's) *trading* engine - there is no Demo API Key, no order sent
 * anywhere, and no server holding the "real" state. The account, its cash
 * balance, deposit history, and every open position are simulated entirely
 * on-device and persisted through [store]. What it does read from the
 * outside is a few pieces of public, read-only market data: [markPriceProvider]
 * (a lightweight hook into the app's live market-data feed, used to value
 * open positions and fill simulated market orders); via
 * [feeRateClient]/[refreshFeeRates], Bitget's real current maker/taker fee
 * rates - so a simulated fill is charged what a live order actually costs
 * instead of a made-up flat percentage; and, via [fundingRateClient]/
 * [runFundingLoop], Bitget's real funding rate, accrued against open
 * positions on the exchange's actual 8-hour settlement grid (design doc
 * §7, "Funding Accrual") instead of ignored. No funds, real or demo, ever
 * move anywhere, and none of these require trading permissions.
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
    // Pulls real maker/taker fee rates from Bitget - see [refreshFeeRates].
    // A plain public-data client by default, same "no trading permissions"
    // posture as the rest of this local engine.
    private val feeRateClient: BitgetFeeRateClient = BitgetFeeRateClient(),
    private val feeProductType: String = "usdt-futures",
    // Optional: if the caller already has a Bitget API key configured
    // elsewhere (e.g. Live Trading), supplying it here lets fee refreshes
    // pull that specific account's actual negotiated rate instead of the
    // exchange's public standard tier. Never required - paper trading still
    // works, with the standard tier, if this returns null or isn't set.
    private val feeRateCredentialsProvider: (() -> BitgetCredentials?)? = null,
    // Pulls real funding rates from Bitget - see [runFundingLoop]/design
    // doc §7 ("Funding Accrual"). Same public-data, no-trading-permissions
    // posture as [feeRateClient].
    private val fundingRateClient: BitgetFundingRateClient = BitgetFundingRateClient(),
    // Live L2 order book, read off the same public depth pipeline the chart
    // uses (see OrderBookWalker) - lets market/marketable-limit fills be
    // priced by walking real depth instead of a flat markPrice fill. Purely
    // read-only public market data; returning null (book not primed yet /
    // no connectivity) just falls back to a flat mark-price fill so trading
    // never breaks because of it.
    private val depthSnapshotProvider: () -> DepthSnapshot? = { null },
    // Public trade prints, used only to estimate queue position for resting
    // limit orders (see QueuePositionTracker) - not account data, no
    // trading permissions.
    private val tradeFlow: Flow<PublicTrade> = emptyFlow(),
) {
    companion object {
        const val MAX_LEVERAGE = 125
        private const val TAG = "PaperTradingRepository"

        private const val MARK_TO_MARKET_INTERVAL_MS = 2_000L

        // VIP tiers and promotions don't change minute-to-minute, so there's
        // no need to hammer the endpoint - refresh occasionally instead of
        // caching indefinitely (fee schedules do drift over time).
        private const val FEE_RATE_REFRESH_INTERVAL_MS = 15 * 60_000L

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

    // Most-recent-first funding settlement history (design doc §7) -
    // populated every time [runFundingLoop] processes a settlement
    // timestamp against whatever positions were open at that moment.
    private val _fundingPayments = MutableStateFlow<List<FundingPayment>>(emptyList())
    val fundingPayments: StateFlow<List<FundingPayment>> = _fundingPayments.asStateFlow()

    // The most recently known funding-rate reading for [symbol] - the
    // rate that will apply at the *next* settlement, refreshed every time
    // the funding job wakes (whether or not any position is open to
    // charge it against). Exposed so the UI can show a trader what
    // they're about to be charged/paid and when - the funding-rate
    // counterpart of [feeRates].
    private val _currentFunding = MutableStateFlow<FundingRateInfo?>(null)
    val currentFunding: StateFlow<FundingRateInfo?> = _currentFunding.asStateFlow()

    // Current maker/taker fee rates applied to every simulated fill (see
    // [estimateFee]). Starts at Bitget's published standard tier and is
    // replaced the moment the first refresh succeeds - exposed as a flow so
    // the UI can show the trader what rate they're actually paying.
    private val _feeRates = MutableStateFlow(FeeRates.DEFAULT)
    val feeRates: StateFlow<FeeRates> = _feeRates.asStateFlow()

    // Latency-simulation settings (design doc §6) - loaded once from
    // [store] at construction (independent of whether an account exists
    // yet; see LocalPaperTradingStore.loadLatencyConfig) and updated via
    // [setLatencyConfig]. Exposed as a flow so the UI can show/edit what
    // delay is currently being simulated.
    private val _latencyConfig = MutableStateFlow(store.loadLatencyConfig())
    val latencyConfig: StateFlow<LatencyConfig> = _latencyConfig.asStateFlow()

    // Every simulated market/marketable-limit fill routes its price lookup
    // through this instead of calling markPriceProvider()/
    // depthSnapshotProvider() directly - see LatencySimulator.
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

    // Source of truth for everything money-related; _balance/_positions are
    // re-derived from this pair on every change and every mark-price tick.
    // Guarded by [stateLock] since it's now written from two independent
    // coroutines - the periodic mark-to-market loop and the public
    // trade-stream collector that drives queue-position fills - as well as
    // from whatever thread calls the public order-placement functions.
    private val stateLock = Any()
    private var walletBalance: Double = 0.0
    private val openPositions = linkedMapOf<PositionSide, PersistedPaperPosition>()
    private val pendingLimitOrders = linkedMapOf<String, PendingLimitOrder>()
    private val closedTradeLog = mutableListOf<ClosedPaperTrade>() // most-recent-first
    private val fundingPaymentLog = mutableListOf<FundingPayment>() // most-recent-first
    // The most recent funding timestamp this account has already settled
    // against - null if it's never lived through one yet (or predates
    // this feature). See [runFundingLoop].
    private var lastFundingSettledAt: Long? = null

    // Tracks each resting limit order's position in its price level's FIFO
    // queue against the live public trade stream (doc §5) - see
    // [placeLimitOrder] (where an order joins the queue) and
    // [onPublicTradePrinted] (where the queue is drained).
    private val queueTracker = QueuePositionTracker()

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

    /** Call from onStop(). Just pauses the mark-to-market re-valuation, fee-refresh, trade-stream, and funding loops; all state is already persisted. */
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

    /**
     * Design doc §7 ("Funding Accrual"): first catches up on any funding
     * settlements that were missed while the app wasn't running - using
     * whatever positions happen to be open right now as a stand-in for
     * what was open at each missed timestamp, since this on-device engine
     * doesn't keep a full position history (see [FundingSchedule.settlementsBetween]
     * for the catch-up cap) - then sleeps until the next settlement on
     * Bitget's real 8-hour grid and applies it the instant it occurs, for
     * as long as this repository keeps running. A single settlement's
     * network/parsing failure is logged and skipped rather than crashing
     * the loop, so it doesn't take down every future settlement with it.
     */
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

    /**
     * Applies one funding settlement at [timestampMs] to every position
     * currently open. Prefers the actual settled historical rate for that
     * exact timestamp (accurate for catching up on a past settlement);
     * falls back to the current live rate if history doesn't have it yet
     * (the only option for a settlement happening right now - see
     * [BitgetFundingRateClient.fetchCurrentFundingRate]).
     *
     * `funding_payment = position_notional x funding_rate` (doc §7 step
     * 2): a long pays when the rate is positive and receives when it's
     * negative; a short is the exact inverse. Applied straight to wallet
     * balance, same as a trading fee (doc §7 step 3), and also tracked
     * per-position via [PersistedPaperPosition.fundingPaidSoFar] plus a
     * durable [FundingPayment] history entry so the UI can show it.
     */
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
                    // Positive = this position pays (wallet decreases);
                    // negative = it receives (wallet increases).
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

    /**
     * Pulls Bitget's current maker/taker fee rates and updates [feeRates].
     * Prefers the caller's actual account-tier rate (if
     * [feeRateCredentialsProvider] is set and supplies credentials) and
     * falls back to the exchange's public standard tier; if both attempts
     * fail (e.g. offline), silently keeps whatever rate was already cached
     * rather than disrupting trading - a fee-rate refresh failing should
     * never block placing or filling an order.
     */
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

    /**
     * Updates the latency-simulation settings (see [LatencyConfig]) and
     * persists them so they survive app restarts. Takes effect on the very
     * next order - there's nothing to restart, since [latencySimulator]
     * reads [latencyConfig] fresh on every call.
     */
    fun setLatencyConfig(config: LatencyConfig) {
        val coerced = config.coerced()
        _latencyConfig.value = coerced
        store.saveLatencyConfig(coerced)
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
        synchronized(stateLock) { walletBalance += amount }
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
     * Simulates a market order. Instead of filling flat at the current mark
     * price, this walks the live order book (see [OrderBookWalker]) and
     * fills at the resulting VWAP - so a large order against a thin book
     * shows realistically worse slippage than a small order against a deep
     * one, exactly like a real exchange. Falls back to a flat mark-price
     * fill only if no book snapshot is available yet (e.g. right at
     * startup, before the depth feed has primed).
     *
     * Opening against an existing position on the same side averages into
     * it (matching how Bitget's own aggregated hold-side positions behave),
     * exactly like the previous Bitget-backed implementation did.
     *
     * Per the design doc's §6 ("Latency Simulation"), this doesn't price
     * against the book/mark price available the instant it's called - it
     * records that moment as `t_decision`, waits out the configured
     * artificial delay (see [LatencySimulator]/[latencyConfig]), and only
     * then reads the live price and walks the book, so the fill reflects
     * market state at (decision time + delay) the same way a real order
     * would after its own network/engine latency.
     */
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
        // A market order always takes liquidity off the book - it can never
        // be a maker fill - so it's classified (and fee'd) as a taker order.
        return fillImmediately(side, size, effectiveLeverage(leverage), fillPrice, FeeClassification.TAKER, bookWalk, execution.appliedDelayMs)
    }

    /**
     * Opens or adds to [side]'s position at [fillPrice], charging whichever
     * of the account's current maker/taker rates [classification] calls
     * for on the fill's full notional (size x price) - see [FeeClassification].
     * Shared by [openPosition] (always taker, [fillInfo] set whenever a book
     * walk priced it) and by [placeLimitOrder]'s immediate-cross branch
     * (taker, priced flat at the order's own limit price, so no [fillInfo]).
     */
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

    /** Notional value (size x price) x the appropriate fee rate for [classification], per [feeRates]. Doesn't touch any balance itself. */
    private fun estimateFee(sizeInBaseCoin: Double, price: Double, classification: FeeClassification): Double {
        val rates = _feeRates.value
        val rate = if (classification == FeeClassification.MAKER) rates.makerRate else rates.takerRate
        return sizeInBaseCoin * price * rate
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
    suspend fun openPositionByNotional(side: PositionSide, sizeInUsdt: String, leverage: Int): PaperTradingResult<PlacedOrder> {
        val notional = sizeInUsdt.toDoubleOrNull()
        if (notional == null || notional <= 0.0) {
            return PaperTradingResult.Failure("Enter a valid size")
        }
        // Only used to convert a USDT notional into a base-coin size before
        // handing off to openPosition() - not the price the order actually
        // fills at, which openPosition() re-derives itself after its own
        // latency-simulated book lookup.
        val price = markPriceProvider()
        if (price == null || price <= 0.0) {
            return PaperTradingResult.Failure("No live price yet - try again in a moment")
        }
        return openPosition(side, (notional / price).toString(), leverage)
    }

    /** Notional-USDT counterpart of [placeLimitOrder] - see [openPositionByNotional]. */
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

    /**
     * Places a resting limit order: it does not fill immediately (unlike
     * [openPosition]). It joins the back of its price level's FIFO queue -
     * whatever size the live order book shows already resting at that exact
     * price when it's placed (see [OrderBookWalker.queueAheadVolume]) - and
     * [QueuePositionTracker] drains that queue as public trade prints come
     * in, filling the order only once enough volume has actually traded
     * through the level (see [onPublicTradePrinted]). [tryFillPendingOrders]
     * remains a fallback for when the trade stream/book isn't available: an
     * order is guaranteed to fill (at its own price, never worse) once the
     * mark price reaches it either way. Its margin is reserved up front so
     * it counts against the account's available balance while it's resting,
     * the same as an open position's margin does.
     *
     * Both the taker (crosses immediately) and maker (rests, joins a
     * queue) branches below evaluate market state after the same
     * latency-simulated delay (design doc §6) - a real order doesn't reach
     * the exchange's book instantaneously either, so whether it crosses on
     * arrival, and how much volume it finds already resting ahead of it if
     * it doesn't, are both evaluated at (decision time + delay) rather
     * than at the instant the trader tapped submit.
     */
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

        // A limit order that already crosses the book the instant it
        // (simulated-latency-delayed) reaches the book - a buy at/above
        // the current price, or a sell at/below it - fills immediately,
        // exactly like a market order - Bitget (and every other exchange)
        // classifies that as a taker fill, not a maker one, since it takes
        // resting liquidity rather than adding it. Route it through the
        // same fill path [openPosition] uses, fixed at the order's
        // requested price (which market conditions would otherwise let
        // fill even better) rather than the live mark price, matching how
        // a marketable limit order guarantees its price.
        val currentPrice = execution.marketState.markPrice
        val crossesImmediately = currentPrice != null && when (side) {
            PositionSide.LONG -> limitPrice >= currentPrice
            PositionSide.SHORT -> limitPrice <= currentPrice
        }
        if (crossesImmediately) {
            return fillImmediately(side, size, safeLeverage, limitPrice, FeeClassification.TAKER, appliedLatencyMs = execution.appliedDelayMs)
        }

        // How much size is already resting at this exact price by the time
        // the order (after its own simulated latency) actually joins the
        // book - that's the queue it's now standing behind (see
        // QueuePositionTracker). 0.0 (fills on the very next print at/
        // through this price) if the book isn't available.
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

    /** Cancels a still-resting limit order, releasing the margin it had reserved. No-op (returns Failure) if it already filled or never existed. */
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

    /**
     * Fallback safety net for [placeLimitOrder]'s primary,
     * trade-stream-driven fill path ([onPublicTradePrinted]): fills any
     * resting limit order whose price has been reached by [markPrice] -
     * a buy/long limit fills once price drops to or below it, a sell/short
     * limit fills once price rises to or above it. Guarantees an order
     * fills once its price is reached even if the public trade stream or
     * order book feed is unavailable, at the cost of not modeling queue
     * position in that fallback case.
     */
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

    /**
     * Called for every print off the public trade stream while resting
     * limit orders exist. Feeds [queueTracker], which returns the IDs of
     * any orders whose FIFO queue has now been fully consumed - each is
     * matched right here, at its own limit price, as a maker fill (see
     * [placeLimitOrder]'s doc for the full mechanism).
     */
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

    /**
     * Opens/adds to [order]'s position at its own limit price and charges
     * the maker rate - shared by both fill paths ([tryFillPendingOrders]
     * and [onPublicTradePrinted]). Caller must already hold [stateLock] and
     * must already have removed [order] from [pendingLimitOrders].
     */
    private fun applyMakerFillLocked(order: PendingLimitOrder) {
        val fillPrice = order.limitPrice
        // This order rested on the book and only just got matched - the
        // textbook definition of a maker fill - so it's charged the
        // (usually lower, sometimes zero during promotions) maker rate
        // instead of the taker rate.
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

    /**
     * Closes the full open position on [position]'s side, realizing its PnL
     * into the wallet balance. Like [openPosition], the exit price is the
     * VWAP from walking live order book depth rather than a flat mark-price
     * fill - closing a long consumes bid-side liquidity (the same side a
     * fresh short would take), and closing a short consumes ask-side
     * liquidity (the same side a fresh long would take).
     *
     * Like [openPosition], the exit price isn't looked up the instant this
     * is called - it's looked up after the same latency-simulated delay
     * (design doc §6), so a close reflects market state at (decision time
     * + delay) exactly as a real close order would.
     */
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
        // Closing here is always a market order (see the class doc - no
        // resting reduce-only limit orders yet), so it's always a taker
        // fill, same as opening one.
        val exitFee = estimateFee(existing.total, exitPrice, FeeClassification.TAKER)
        val totalFees = existing.feesPaidSoFar + exitFee
        val realizedPnl = grossPnl - exitFee
        // Already reflected in walletBalance at the time each settlement
        // happened (see settleFunding) - not re-applied or netted into
        // realizedPnl here, just carried over for the history line item.
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

    /** Wipes the local account, balance, and every open position so the user can start fresh. */
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
