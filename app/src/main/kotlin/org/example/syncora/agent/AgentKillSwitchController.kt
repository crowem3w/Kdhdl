package org.example.syncora.agent

import android.util.Log
import org.example.syncora.bitget.LiveTradingRepository
import org.example.syncora.bitget.PaperTradingResult
import org.example.syncora.bitget.RiskSettingsStore

/** Outcome of one [AgentKillSwitchController.engage] call - reported back to the UI so it can say exactly what happened rather than a bare success/failure. */
data class KillSwitchResult(
    val loopHalted: Boolean,
    val positionsFlattenedCount: Int,
    val positionsFailedToFlatten: List<String>,
)

/**
 * The manual, human-triggered "stop everything now" control the Agent tab exposes: it
 * immediately halts [DecisionLoopScheduler] and closes any open position at market, on demand -
 * distinct from [org.example.syncora.bitget.StopLossGuard]'s always-on, exchange-side
 * dead-man's-switch (design doc §2.2), and distinct from [ModelRollbackController]'s "revert to
 * a previous model" (this doesn't touch which model is loaded at all).
 *
 * **Ordering matters.** [engage] stops the loop *before* touching any position, and flips
 * [RiskSettingsStore.autoTradingEnabled] off in the same call rather than leaving a caller to
 * remember it separately:
 *
 * - Stopping the loop first means a flatten-in-progress can never race a fresh dispatch on the
 *   next kline close - by the time the first `closePosition` call goes out, nothing else is
 *   capable of placing a new order.
 * - Flipping the master switch off in the same call (not just cancelling the running
 *   coroutine) means a later, unrelated call to [DecisionLoopScheduler.start] - e.g. the next
 *   process/foreground-service restart - can't silently resume live dispatch. A kill switch
 *   that only paused the loop's *coroutine* would be re-armed by the next restart; this one
 *   requires an explicit, separate re-enable (see [resumeLoopOnly]'s kdoc).
 *
 * **Not a replacement for the dead-man's switch.** [StopLossGuard]'s resting exchange-side stop
 * order is left alone by this class on purpose - a flatten here is a best-effort market close
 * that depends on this process being alive and the network call succeeding, exactly the
 * assumption design doc §2.2 says never to rely on alone. If a flatten call in [engage] fails
 * (network error, rejected order, etc.), the position stays open but still protected by that
 * independent stop order, and the caller finds out via [KillSwitchResult.positionsFailedToFlatten]
 * rather than the failure being silently swallowed.
 */
class AgentKillSwitchController(
    private val decisionLoopScheduler: DecisionLoopScheduler,
    private val riskSettingsStore: RiskSettingsStore,
    private val liveTradingRepository: LiveTradingRepository,
) {
    private companion object {
        const val TAG = "AgentKillSwitchController"
    }

    /**
     * Halts the loop, disables auto-trading, then attempts to close every currently open
     * position one at a time. One position's close failing doesn't stop the others from being
     * attempted - the caller gets back exactly which symbols (if any) are still open so the UI
     * can surface that instead of reporting a blanket success.
     */
    suspend fun engage(): KillSwitchResult {
        decisionLoopScheduler.stop()
        riskSettingsStore.autoTradingEnabled = false
        Log.w(TAG, "Kill switch engaged: decision loop halted, auto-trading disabled")

        val openPositions = liveTradingRepository.positions.value
        val failed = mutableListOf<String>()
        var flattened = 0
        for (position in openPositions) {
            when (val result = liveTradingRepository.closePosition(position)) {
                is PaperTradingResult.Success -> {
                    flattened++
                    Log.i(TAG, "Kill switch flattened ${position.symbol} ${position.side} ${position.total}")
                }
                is PaperTradingResult.Failure -> {
                    Log.e(TAG, "Kill switch failed to flatten ${position.symbol}: ${result.message}")
                    failed += position.symbol
                }
            }
        }
        return KillSwitchResult(loopHalted = true, positionsFlattenedCount = flattened, positionsFailedToFlatten = failed)
    }

    /**
     * Manually resumes the decision loop after [engage]. Deliberately does **not** re-enable
     * [RiskSettingsStore.autoTradingEnabled] on its own - that store's own kdoc is explicit that
     * "the policy can't turn live dispatch on for itself," and a resume button that silently
     * restored live order dispatch as a side effect would violate exactly that. Resuming here
     * only means "run inference and log decisions again"; a separate, explicit settings action
     * re-arms live dispatch.
     */
    fun resumeLoopOnly() {
        decisionLoopScheduler.start()
        Log.i(TAG, "Decision loop resumed (auto-trading dispatch remains off until explicitly re-enabled)")
    }
}
