package org.example.syncora.agent

import org.example.syncora.bitget.FundingSchedule
import org.example.syncora.bitget.PaperTradingSnapshot
import org.example.syncora.bitget.PositionSide
import kotlin.math.abs

/**
 * Prompt 7g's "hand cross-check" step, made reusable rather than one-off:
 * independently recomputes expected paper P&L, position size, and captured
 * funding from a window of [AgentOrchestrator.DecisionLog] entries - the
 * *audit log* [AgentSoakHarness] collects - and compares them against what
 * [org.example.syncora.bitget.LocalPaperTradingStore] actually recorded for
 * the same window, via its own [PaperTradingSnapshot] shape. Two genuinely
 * separate code paths feed the comparison:
 *
 * 1. **Expected**, derived here from [AgentOrchestrator.DecisionLog] alone,
 *    using the exact reward-component formulas `docs/agent-design-contract.md`
 *    §1/§3 fixes and [RewardEngine] implements - scaled from the bounded
 *    `f_t ∈ [-1, 1]` position [RewardEngine] works in up to real base-coin
 *    size via the same linear `f_t · maxPositionSizeBaseCoin` convention
 *    [PositionOrderEmitter] uses to place orders in the first place (see
 *    that class's "Sizing convention" doc).
 * 2. **Recorded**, read directly off a [PaperTradingSnapshot] - the exact
 *    type [org.example.syncora.bitget.LocalPaperTradingStore.load]/`save`
 *    persists, so this is a cross-check against the real persisted shape
 *    named in Prompt 7g's exit criterion, not a stand-in.
 *
 * Funding is the most load-bearing of the three: [RewardEngine] charges it
 * as a *continuous per-bar accrual* (`κ_t · f_t` every bar), while the real
 * paper account only ever posts a funding entry at a discrete
 * [FundingSchedule] settlement instant (design doc §3,
 * `PaperTradingRepository.settleFunding`). [RewardEngine]'s own class doc
 * asserts these must sum to the same total for a position held constant
 * across a full interval ("no drift between the two") - this is precisely
 * the claim [expectedFundingCaptured] vs. a snapshot's
 * [PaperTradingSnapshot.fundingPayments] lets a sampled soak window verify
 * for real, not just by inspection of the formulas.
 */
object AgentSoakCrossCheck {

    /** One sampled bar's expected-vs-recorded comparison - Prompt 7g's per-bar exit criterion is [matches]. */
    data class CrossCheckResult(
        val barIndex: Int,
        val expectedPosition: Double,
        val recordedPosition: Double,
        val expectedNetPnlSinceWindowStart: Double,
        val recordedNetPnlSinceWindowStart: Double,
        val expectedFundingCapturedSinceWindowStart: Double,
        val recordedFundingCapturedSinceWindowStart: Double,
        val positionToleranceBaseCoin: Double,
        val pnlToleranceUsd: Double,
        val fundingToleranceUsd: Double,
    ) {
        val positionMatches: Boolean get() = abs(expectedPosition - recordedPosition) <= positionToleranceBaseCoin
        val pnlMatches: Boolean get() = abs(expectedNetPnlSinceWindowStart - recordedNetPnlSinceWindowStart) <= pnlToleranceUsd
        val fundingMatches: Boolean get() =
            abs(expectedFundingCapturedSinceWindowStart - recordedFundingCapturedSinceWindowStart) <= fundingToleranceUsd

        /** True iff every one of position/P&L/funding matched within tolerance - Prompt 7g's "matches ... exactly" (up to documented float tolerance; see [positionToleranceBaseCoin] etc.). */
        val matches: Boolean get() = positionMatches && pnlMatches && fundingMatches
    }

    /**
     * The base-coin position size [AgentOrchestrator.DecisionLog.position]
     * implies, under [PositionOrderEmitter]'s own linear sizing convention
     * (`f_t = ±1.0` <-> [maxPositionSizeBaseCoin]). This is what a sampled
     * bar's target position is *expected* to look like once translated
     * into the same units [PaperTradingSnapshot.positions] records.
     */
    fun expectedPosition(decision: AgentOrchestrator.DecisionLog, maxPositionSizeBaseCoin: Double): Double =
        decision.position.toDouble() * maxPositionSizeBaseCoin

    /**
     * Sum of `reward_t · maxPositionSizeBaseCoin` across [window] - the
     * expected net paper P&L (mark-to-market gain, net of modeled spread
     * cost, exchange fee, and funding accrual - [RewardEngine]'s `r_t`
     * already nets all three, see design doc §1) accumulated over the
     * window, in the same USD-equivalent units a paper account's wallet
     * balance moves in.
     */
    fun expectedNetPnl(window: List<AgentOrchestrator.DecisionLog>, maxPositionSizeBaseCoin: Double): Double =
        window.sumOf { it.reward * maxPositionSizeBaseCoin }

    /**
     * Sum of `fundingCost_t · maxPositionSizeBaseCoin` across [window] -
     * [RewardEngine]'s continuous per-bar funding accrual, scaled to
     * base-coin size, positive meaning "paid" per design doc §3's sign
     * convention (matching [org.example.syncora.bitget.FundingPayment.amount]'s
     * own sign exactly).
     */
    fun expectedFundingCaptured(window: List<AgentOrchestrator.DecisionLog>, maxPositionSizeBaseCoin: Double): Double =
        window.sumOf { it.fundingCost * maxPositionSizeBaseCoin }

    /**
     * The signed base-coin position [snapshot] actually holds right now -
     * positive for a recorded [PositionSide.LONG], negative for
     * [PositionSide.SHORT], `0.0` if flat (or, in the pathological case of
     * a snapshot somehow recording both sides at once, their net) - the
     * "recorded" counterpart to [expectedPosition].
     */
    fun recordedPosition(snapshot: PaperTradingSnapshot): Double =
        snapshot.positions.sumOf { p -> if (p.side == PositionSide.LONG) p.total else -p.total }

    /**
     * Net funding actually captured by [snapshot] since [sinceMs]
     * (exclusive) up to and including [untilMs] - the sum of every
     * [org.example.syncora.bitget.FundingPayment.amount] in
     * [PaperTradingSnapshot.fundingPayments] whose `settledAt` falls in
     * that window, positive meaning paid (see [expectedFundingCaptured]'s
     * doc for the shared sign convention) - the "recorded" counterpart to
     * [expectedFundingCaptured].
     */
    fun recordedFundingCaptured(snapshot: PaperTradingSnapshot, sinceMs: Long, untilMs: Long): Double =
        snapshot.fundingPayments
            .asSequence()
            .filter { it.settledAt > sinceMs && it.settledAt <= untilMs }
            .sumOf { it.amount }

    /**
     * Total account equity a [PaperTradingSnapshot] implies *right now*,
     * given [markPrice] - wallet balance (every realized close, fee, and
     * funding settlement already flows through this - see
     * [PaperTradingSnapshot.walletBalance]) plus the unrealized P&L of
     * whatever is still open, marked at [markPrice]. [PersistedPaperPosition]
     * itself carries no mark price or unrealized P&L (unlike the live
     * [org.example.syncora.bitget.PaperPosition] - see that class's own
     * doc), since a persisted snapshot is a statement of realized cash
     * position, not a live valuation - [markPrice] is what a manual
     * cross-check supplies from the bar being sampled to complete that
     * valuation, the same arithmetic a person doing this by hand would do
     * with the account screen's current mark price.
     */
    fun accountEquity(snapshot: PaperTradingSnapshot, markPrice: Double): Double {
        val unrealized = snapshot.positions.sumOf { p ->
            val sign = if (p.side == PositionSide.LONG) 1.0 else -1.0
            (markPrice - p.entryPrice) * p.total * sign
        }
        return snapshot.walletBalance + unrealized
    }

    /**
     * Net paper P&L a [PaperTradingSnapshot] has actually realized/accrued
     * since [equityAtWindowStart] - [accountEquity] (walletBalance plus
     * any still-open position's unrealized P&L, marked at [markPriceAtWindowEnd])
     * minus the equity the account started the window with, minus any
     * [depositsSinceWindowStart] (the only other thing that moves equity
     * besides trading P&L). Marking both ends of the window at their own
     * bar's actual mark price is what makes this comparable to
     * [expectedNetPnl] even when the window ends (or starts) with a
     * position still open, rather than only being meaningful for windows
     * that happen to begin and end flat.
     */
    fun recordedNetPnl(
        equityAtWindowStart: Double,
        snapshot: PaperTradingSnapshot,
        markPriceAtWindowEnd: Double,
        depositsSinceWindowStart: Double = 0.0,
    ): Double = accountEquity(snapshot, markPriceAtWindowEnd) - equityAtWindowStart - depositsSinceWindowStart

    /**
     * Cross-checks one sampled bar against the window of decisions leading
     * up to it (inclusive), per Prompt 7g. [window] should be every
     * [AgentOrchestrator.DecisionLog] from the start of the comparison
     * window up to and including [sampledDecision].
     *
     * @param sampledDecision The bar being hand-checked - must be the last element of [window].
     * @param window Every decision since the comparison window's start, oldest first, ending with [sampledDecision].
     * @param equityAtWindowStart [accountEquity] at the comparison window's start - `0.0` for a window starting at a fresh, never-funded, never-traded account (the common case for a soak's very first window), or [accountEquity] computed from a snapshot/mark-price pair captured earlier in the soak for a later window.
     * @param snapshotAtWindowEnd The account's [PaperTradingSnapshot] captured right after [sampledDecision] was processed and its order (if any) emitted.
     * @param markPriceAtWindowEnd The reference (mid) price [RewardEngine.step] used for [sampledDecision]'s own bar - needed both to value any still-open position ([accountEquity]) and, implicitly, because it is the same price [expectedNetPnl]'s `markToMarketPnl` term already priced this bar's mark-to-market against.
     * @param fundingSinceMs Everything strictly after this timestamp counts toward [recordedFundingCaptured] - typically the comparison window's own start time (or one funding interval earlier, if the window starts exactly on a bar that itself settles funding) so a settlement from a *previous*, already-checked window isn't double-counted into this one.
     * @param maxPositionSizeBaseCoin Same sizing convention as [PositionOrderEmitter] was configured with for this run - required to translate `f_t` into base-coin/USD terms at all.
     * @param depositsSinceWindowStart Any deposits into the account during the window - defaults to 0 (the expected case for an unattended soak with no manual account activity); if nonzero, must be netted out for [recordedNetPnl] to mean what it says.
     * @param positionToleranceBaseCoin Float/rounding slack for the position-size comparison.
     * @param pnlToleranceUsd Float/rounding + discrete-vs-continuous-accrual slack for the P&L comparison - scaled by [window]'s length, since summation error compounds roughly with the number of terms summed.
     * @param fundingToleranceUsd Slack for the funding comparison specifically - see class doc's "continuous accrual vs. discrete settlement" note. Scaled the same way [pnlToleranceUsd] is.
     */
    fun crossCheck(
        sampledDecision: AgentOrchestrator.DecisionLog,
        window: List<AgentOrchestrator.DecisionLog>,
        equityAtWindowStart: Double,
        snapshotAtWindowEnd: PaperTradingSnapshot,
        markPriceAtWindowEnd: Double,
        fundingSinceMs: Long,
        maxPositionSizeBaseCoin: Double,
        depositsSinceWindowStart: Double = 0.0,
        // Format-and-reparse rounding (PositionOrderEmitter.formatSize's
        // 8-decimal-place String) applied at every emitted order, of which
        // there is at most one per bar - looser than that single-op
        // rounding floor to comfortably absorb it compounding across a
        // handful of trades over a soak window, while still catching a
        // real sizing-convention mismatch (which would be orders of
        // magnitude larger than rounding noise).
        positionToleranceBaseCoin: Double = 1e-6,
        pnlToleranceUsd: Double = 1e-6 * window.size.coerceAtLeast(1),
        fundingToleranceUsd: Double = 1e-6 * window.size.coerceAtLeast(1),
    ): CrossCheckResult {
        require(window.isNotEmpty() && window.last() === sampledDecision) {
            "window must be non-empty and end with sampledDecision"
        }

        return CrossCheckResult(
            barIndex = sampledDecision.barIndex,
            expectedPosition = expectedPosition(sampledDecision, maxPositionSizeBaseCoin),
            recordedPosition = recordedPosition(snapshotAtWindowEnd),
            expectedNetPnlSinceWindowStart = expectedNetPnl(window, maxPositionSizeBaseCoin),
            recordedNetPnlSinceWindowStart = recordedNetPnl(
                equityAtWindowStart = equityAtWindowStart,
                snapshot = snapshotAtWindowEnd,
                markPriceAtWindowEnd = markPriceAtWindowEnd,
                depositsSinceWindowStart = depositsSinceWindowStart,
            ),
            expectedFundingCapturedSinceWindowStart = expectedFundingCaptured(window, maxPositionSizeBaseCoin),
            recordedFundingCapturedSinceWindowStart = recordedFundingCaptured(snapshotAtWindowEnd, fundingSinceMs, sampledDecision.startTime),
            positionToleranceBaseCoin = positionToleranceBaseCoin,
            pnlToleranceUsd = pnlToleranceUsd,
            fundingToleranceUsd = fundingToleranceUsd,
        )
    }
}
