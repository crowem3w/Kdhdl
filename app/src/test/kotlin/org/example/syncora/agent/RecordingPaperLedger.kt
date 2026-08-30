package org.example.syncora.agent

import org.example.syncora.bitget.ClosedPaperTrade
import org.example.syncora.bitget.FundingPayment
import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.PaperAccount
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PaperTradingSnapshot
import org.example.syncora.bitget.PersistedPaperPosition
import org.example.syncora.bitget.PositionSide
import kotlin.math.abs

/**
 * A [PaperOrderSink] test double standing in for the real
 * [org.example.syncora.bitget.PaperTradingRepository] /
 * [org.example.syncora.bitget.LocalPaperTradingStore] pair in
 * `AgentSoakTest` - not a mock, but an independent, from-scratch
 * implementation of the same core paper-account bookkeeping
 * ([org.example.syncora.bitget.PaperTradingRepository]'s own doc: entry
 * price on open/add-on, realized P&L on close, periodic funding
 * settlement), so that [AgentSoakCrossCheck.crossCheck] is comparing two
 * *independently written* computations of "what should the account show",
 * exactly as Prompt 7g's "hand cross-check" calls for, rather than one
 * value trivially re-deriving the other.
 *
 * Deliberately does **not** model order-book-walked fill slippage or
 * latency simulation (`PaperTradingRepository`'s own book-walker/latency
 * layers) - those are execution-quality details the reward model
 * ([RewardEngine]) never claimed to capture either (`docs/agent-design-
 * contract.md` §1 charges the *modeled* half-spread `δ_t`, not a walked
 * fill price), so leaving them out here keeps this ledger's arithmetic
 * anchored to the exact same reference price [RewardEngine] used, which is
 * what makes [AgentSoakCrossCheck]'s tolerances meaningfully tight rather
 * than needing to absorb an unrelated slippage model's noise.
 *
 * @param feeRate Flat maker/taker rate applied to every fill - see [org.example.syncora.bitget.PaperTradingRepository]'s own `estimateFee` (`size * price * rate`) shape, which this mirrors exactly.
 * @param symbol Recorded on every position/trade/payment - cosmetic only for this ledger's own bookkeeping.
 */
class RecordingPaperLedger(
    private val feeRate: Double = 0.0,
    private val symbol: String = "BTCUSDT",
) : PaperOrderSink {

    /** This ledger's own notion of the current reference (mid) price - the caller (the test harness) must set this, together with [currentBid]/[currentAsk], to the exact same values [RewardEngine.step] used for the bar about to be processed, *before* [AgentOrchestrator.processLiveBar]/[PositionOrderEmitter.onTargetPosition] runs for that bar. Used for marking open positions (unrealized P&L) and for [FundingPayment] notional - never for pricing a fill itself, see [currentBid]/[currentAsk]. */
    var currentMidPrice: Double = 0.0

    /** This bar's best bid - a SHORT-direction fill (opening/adding SHORT, or closing LONG) prices at this, modeling exactly the `δ_t = 0.5·(ask-bid)` spread-crossing cost [RewardEngine] charges relative to [currentMidPrice] (design doc §1) - never at the mid price itself. */
    var currentBid: Double = 0.0

    /** This bar's best ask - the LONG-direction counterpart to [currentBid]. */
    var currentAsk: Double = 0.0

    private var walletBalance: Double = 0.0
    private var position: PersistedPaperPosition? = null
    private val closedTrades = ArrayList<ClosedPaperTrade>()
    private val fundingPayments = ArrayList<FundingPayment>()
    private var lastFundingSettledAt: Long? = null

    /** [PositionOrderEmitter]'s `currentPosition` supplier - reads this ledger's own state, exactly as a real [PositionOrderEmitter] reads [org.example.syncora.bitget.PaperTradingRepository.positions] live. */
    fun currentPosition(): PaperPosition? {
        val p = position ?: return null
        val sign = if (p.side == PositionSide.LONG) 1.0 else -1.0
        val unrealizedPnl = (currentMidPrice - p.entryPrice) * p.total * sign
        return PaperPosition(
            symbol = symbol,
            side = p.side,
            total = p.total,
            available = p.total,
            entryPrice = p.entryPrice,
            markPrice = currentMidPrice,
            leverage = p.leverage,
            marginSize = p.marginSize,
            unrealizedPnl = unrealizedPnl,
            feesPaidSoFar = p.feesPaidSoFar,
            fundingPaidSoFar = p.fundingPaidSoFar,
        )
    }

    override fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int) {
        val size = sizeInBaseCoin.toDouble()
        if (size <= 0.0) return
        // Opening/adding LONG buys (crosses the ask); SHORT sells (crosses
        // the bid) - see currentBid/currentAsk's doc.
        val price = if (side == PositionSide.LONG) currentAsk else currentBid
        val fee = size * price * feeRate
        walletBalance -= fee

        val existing = position
        position = if (existing != null && existing.side == side) {
            val newTotal = existing.total + size
            val vwapEntry = (existing.entryPrice * existing.total + price * size) / newTotal
            existing.copy(
                total = newTotal,
                entryPrice = vwapEntry,
                marginSize = existing.marginSize + (size * price / leverage),
                feesPaidSoFar = existing.feesPaidSoFar + fee,
            )
        } else {
            // existing == null (opening from flat) - a same-call flip
            // (existing.side != side) never reaches this sink directly:
            // PositionOrderEmitter always closes the old side first.
            PersistedPaperPosition(
                side = side,
                total = size,
                entryPrice = price,
                leverage = leverage,
                marginSize = size * price / leverage,
                feesPaidSoFar = fee,
                fundingPaidSoFar = 0.0,
            )
        }
    }

    override fun closePosition(position: PaperPosition) {
        val existing = this.position ?: return
        // Closing LONG sells (crosses the bid); closing SHORT buys
        // (crosses the ask) - the opposite mapping from openPosition.
        val exitPrice = if (existing.side == PositionSide.LONG) currentBid else currentAsk
        val sign = if (existing.side == PositionSide.LONG) 1.0 else -1.0
        val fee = existing.total * exitPrice * feeRate
        val realizedPnl = (exitPrice - existing.entryPrice) * existing.total * sign - fee

        walletBalance += realizedPnl
        closedTrades.add(
            ClosedPaperTrade(
                symbol = symbol,
                side = existing.side,
                size = existing.total,
                entryPrice = existing.entryPrice,
                exitPrice = exitPrice,
                leverage = existing.leverage,
                realizedPnl = realizedPnl,
                closedAt = 0L,
                totalFeesPaid = existing.feesPaidSoFar + fee,
                totalFundingPaid = existing.fundingPaidSoFar,
            ),
        )
        this.position = null
    }

    /**
     * Settles every [FundingSchedule] instant strictly after
     * [lastFundingSettledAt] (or [firstSettlementSearchFromMs] if nothing
     * has ever settled yet) up to and including [nowMs], against whatever
     * position is open *at each settlement instant* - the discrete-event
     * counterpart to [RewardEngine]'s continuous per-bar accrual, per
     * design doc §3's `amount = notional * rate * sign(f_t)`. A no-op
     * (beyond advancing the settlement cursor) whenever the ledger is flat
     * at a given settlement instant, matching real funding: nothing is
     * owed on a position that doesn't exist.
     */
    fun settleFundingUpTo(nowMs: Long, fundingRateAt: (Long) -> Double, firstSettlementSearchFromMs: Long) {
        val since = lastFundingSettledAt ?: firstSettlementSearchFromMs
        if (nowMs <= since) return
        val instants = FundingSchedule.settlementsBetween(since, nowMs)
        for (settledAt in instants) {
            val p = position
            if (p != null) {
                val sign = if (p.side == PositionSide.LONG) 1.0 else -1.0
                val notional = p.total * currentMidPrice
                val rate = fundingRateAt(settledAt)
                val amount = notional * rate * sign
                walletBalance -= amount
                position = p.copy(fundingPaidSoFar = p.fundingPaidSoFar + amount)
                fundingPayments.add(
                    FundingPayment(
                        symbol = symbol,
                        side = p.side,
                        fundingRate = rate,
                        positionNotional = notional,
                        amount = amount,
                        settledAt = settledAt,
                    ),
                )
            }
            lastFundingSettledAt = settledAt
        }
    }

    /** A snapshot of this ledger's state right now - the same shape [org.example.syncora.bitget.LocalPaperTradingStore.save] would persist. */
    fun snapshot(): PaperTradingSnapshot = PaperTradingSnapshot(
        account = PaperAccount(id = "soak-test-ledger", createdAt = 0L, lastDepositAt = null),
        walletBalance = walletBalance,
        positions = position?.let { listOf(it) } ?: emptyList(),
        pendingOrders = emptyList(),
        closedTrades = closedTrades.toList(),
        fundingPayments = fundingPayments.toList(),
        lastFundingSettledAt = lastFundingSettledAt,
    )

    /** Sanity helper for test assertions: absolute net funding paid (positive) or received (negative) recorded so far, open position plus closed history. */
    fun totalFundingCapturedSoFar(): Double =
        fundingPayments.sumOf { it.amount }.let { if (abs(it) < 1e-12) 0.0 else it }
}
