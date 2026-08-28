package org.example.syncora.agent

import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PositionSide
import java.util.Locale
import kotlin.math.abs

/**
 * The minimal surface Prompt 7c's [PositionOrderEmitter] needs from the
 * app's existing paper-trading order path - deliberately shaped to match
 * [org.example.syncora.bitget.PaperTradingRepository.openPosition] and
 * [org.example.syncora.bitget.PaperTradingRepository.closePosition]'s
 * signatures (the same two calls
 * [org.example.syncora.ui.PaperTradePanel.Callbacks]'s `onOpenPosition`/
 * `onClosePosition` ultimately forward to) exactly, so wiring a live
 * [AgentOrchestrator] into the real app is a matter of adapting that
 * existing repository/panel, not building a second order-entry pathway.
 * Kept as a small interface (rather than a direct dependency on
 * [org.example.syncora.bitget.PaperTradingRepository]) so this class stays
 * out of Android/coroutine territory, same as every other Phase 1-5 engine
 * in this package, and so tests can stub it without a real repository.
 */
interface PaperOrderSink {
    /** Opens a new position, or adds [sizeInBaseCoin] to an existing one on the same [side] - see `PaperTradingRepository.openPosition`. */
    fun openPosition(side: PositionSide, sizeInBaseCoin: String, leverage: Int)

    /** Closes [position] (identified by its `side`) in full - see `PaperTradingRepository.closePosition`. There is no partial-close call in the existing order path; a size *reduction* is realised as a full close followed by a smaller [openPosition]. */
    fun closePosition(position: PaperPosition)
}

/**
 * Prompt 7c: takes the target position `f_t ∈ [-1, 1]` that
 * [AgentOrchestrator.processLiveBar] produces on each live bar-close (see
 * [AgentOrchestrator.DecisionLog.position]) and emits it into the existing
 * paper-trading order path via [PaperOrderSink], translating the bounded
 * policy output into the `(side, sizeInBaseCoin, leverage)` representation
 * [PaperOrderSink.openPosition]/[PaperOrderSink.closePosition] expect.
 *
 * This class is deliberately *not* part of [AgentOrchestrator] - the
 * orchestrator's whole point through Phase 5 and Prompt 7b is that it
 * places zero live or paper orders by construction (no dependency on any
 * order-placing type anywhere in that file); [PositionOrderEmitter] is
 * where that boundary is crossed on purpose, and only here, by a caller
 * that explicitly wires `orchestrator.processLiveBar(...).position` into
 * [onTargetPosition].
 *
 * ### Sizing convention
 * `f_t` is a *fraction* of [maxPositionSizeBaseCoin] - `f_t = 1.0` means
 * the maximum long size this emitter is configured to place,
 * `f_t = -1.0` the maximum short, `f_t = 0.0` flat. [maxPositionSizeBaseCoin]
 * and [leverage] are this emitter's own configuration, not something the
 * policy or reward engine decides - Phase 7's hard position/notional caps
 * (`docs/agent-design-contract.md`, "a third, orchestrator-level layer")
 * are the durable enforcement of a size ceiling; this constructor
 * parameter is Phase 6's provisional stand-in so paper trading has *some*
 * concrete sizing before that hardening exists.
 *
 * ### No redundant no-op orders, no dropped or coalesced genuine changes
 * [onTargetPosition] compares the *target* implied by `f_t` against
 * whatever [currentPosition] reports right now (the account's actual
 * current state, e.g. backed by `LocalPaperTradingStore`/
 * `PaperTradingRepository.positions` in the real app) and emits exactly
 * the calls needed to bridge the two:
 * - **Unchanged** (same side, size within [sizeEpsilonBaseCoin]) - no
 *   calls at all, so a live bar-close that repeats an already-acted-on
 *   `f_t` (a very common case - the policy doesn't have to trade every
 *   bar) never spams a redundant order.
 * - **Flatten to zero** (`f_t` collapses to ~0 from an open position) -
 *   [PaperOrderSink.closePosition] only.
 * - **Opening from flat** - [PaperOrderSink.openPosition] only.
 * - **Flip direction** (long -> short or vice versa) -
 *   [PaperOrderSink.closePosition] on the old side, then
 *   [PaperOrderSink.openPosition] on the new one - never merged into a
 *   single call, since the order path has no such primitive.
 * - **Same side, size increases** - a single add-on
 *   [PaperOrderSink.openPosition] for just the *delta*, not the full new
 *   size (avoids double-counting the size already open).
 * - **Same side, size decreases** - since [PaperOrderSink] has no
 *   partial-close, this closes the existing position in full and reopens
 *   at the smaller target size (two calls) rather than silently dropping
 *   the change or leaving the old, too-large size in place.
 *
 * @param orderSink Where opens/closes are actually placed - see class doc.
 * @param currentPosition Supplies the account's current net position on this symbol right now, or null if flat. Called once per [onTargetPosition] invocation; the caller is responsible for this reflecting the effect of any order this class just emitted before the *next* call (true automatically when backed by a live repository/store, since [PaperOrderSink]'s calls mutate that same state).
 * @param maxPositionSizeBaseCoin The base-coin size `f_t = ±1.0` corresponds to - must be `> 0`. See "Sizing convention" above.
 * @param leverage Applied to every [PaperOrderSink.openPosition] call this emitter makes - constant, not something `f_t` varies. Must be `>= 1`.
 * @param sizeEpsilonBaseCoin Sizes within this of each other are treated as unchanged - guards against float noise around an unchanged `f_t` producing a spurious tiny add-on/reduce order. Defaults to a small fraction of a satoshi-scale unit, far below any real order size.
 */
class PositionOrderEmitter(
    private val orderSink: PaperOrderSink,
    private val currentPosition: () -> PaperPosition?,
    private val maxPositionSizeBaseCoin: Double,
    private val leverage: Int = 1,
    private val sizeEpsilonBaseCoin: Double = 1e-8,
) {
    init {
        require(maxPositionSizeBaseCoin > 0.0) { "maxPositionSizeBaseCoin must be > 0, was $maxPositionSizeBaseCoin" }
        require(leverage >= 1) { "leverage must be >= 1, was $leverage" }
        require(sizeEpsilonBaseCoin >= 0.0) { "sizeEpsilonBaseCoin must be >= 0, was $sizeEpsilonBaseCoin" }
    }

    /**
     * Bridges the account from wherever [currentPosition] says it is now
     * to the target implied by [targetPosition] - see class doc for the
     * exact case-by-case behaviour. Safe to call with the same
     * [targetPosition] repeatedly (a no-op after the first bridging call)
     * and safe to call every live bar-close, whether or not `f_t` actually
     * moved.
     *
     * @param targetPosition `f_t`, [PolicyEngine]'s bounded output - must be within `[-1, 1]` (see `PolicyEngine.step`'s own `tanh` guarantee).
     */
    fun onTargetPosition(targetPosition: Float) {
        require(targetPosition in -1f..1f) { "targetPosition must be in [-1, 1], was $targetPosition" }

        val targetSize = abs(targetPosition.toDouble()) * maxPositionSizeBaseCoin
        val targetSide: PositionSide? = when {
            targetSize <= sizeEpsilonBaseCoin -> null
            targetPosition > 0f -> PositionSide.LONG
            else -> PositionSide.SHORT
        }

        val existing = currentPosition()
        val existingSide = existing?.side
        val existingSize = existing?.total ?: 0.0

        val unchanged = existingSide == targetSide &&
            (targetSide == null || abs(existingSize - targetSize) <= sizeEpsilonBaseCoin)
        if (unchanged) return

        if (targetSide == null) {
            // Flatten to zero: close only, never re-open.
            if (existing != null) orderSink.closePosition(existing)
            return
        }

        if (existing == null) {
            // Opening from flat.
            orderSink.openPosition(targetSide, formatSize(targetSize), leverage)
            return
        }

        if (existing.side != targetSide) {
            // Flip direction: close the old side, open the new one - two
            // calls, never coalesced, since the order path has no
            // single-call "flip" primitive.
            orderSink.closePosition(existing)
            orderSink.openPosition(targetSide, formatSize(targetSize), leverage)
            return
        }

        // Same side, size genuinely changed (unchanged case already returned above).
        val delta = targetSize - existingSize
        if (delta > sizeEpsilonBaseCoin) {
            // Growing in the same direction: add on just the delta.
            orderSink.openPosition(targetSide, formatSize(delta), leverage)
        } else {
            // Shrinking in the same direction: no partial-close primitive
            // exists, so close in full and reopen at the smaller size.
            orderSink.closePosition(existing)
            orderSink.openPosition(targetSide, formatSize(targetSize), leverage)
        }
    }

    /** Formats a base-coin size the way [PaperOrderSink.openPosition] expects - fixed-point, never scientific notation, locale-independent. */
    private fun formatSize(size: Double): String = String.format(Locale.US, "%.8f", size)
}
