package org.example.syncora.bitget

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The execution consumer for [DirectRLPositionPipeline.positionTarget]
 * (audit fix plan §Fix 1) - the thing that would, once validated, turn
 * [DirectRLDecision.gatedPosition] into orders against
 * [LiveTradingRepository]/[PaperTradingRepository].
 *
 * **This class is shadow/logging-only.** It subscribes to
 * [positionTarget], records every decision, and exposes the latest one
 * via [lastDecision]/[decisionHistory] for inspection (e.g. a future debug
 * panel) - it never calls into [LiveTradingRepository] or
 * [PaperTradingRepository], and never places, amends, or cancels a single
 * order. Per the fix plan's Fix 1 item 4 and §4 ("Validation Before Any
 * Live Trading Exposure"), an "Agentic" trading mode must not be surfaced
 * in [org.example.syncora.ui.TradingModeDialog] and this engine must not
 * be pointed at a real execution repository until the corrected pipeline
 * has been run in shadow mode against live data across multiple funding
 * cycles, its r_t/utility/ir trajectory sanity-checked against a re-run of
 * the paper's own backtest methodology, and then further validated in
 * [PaperTradingRepository] before [LiveTradingRepository] is ever
 * considered. That wiring is intentionally *not* implemented here - adding
 * it is a distinct, later change that needs its own explicit sign-off, not
 * something this class should grow into unnoticed.
 */
class AgenticExecutionEngine(
    private val positionTarget: StateFlow<DirectRLDecision?>,
    // Cap on in-memory decision history, purely so a long-running shadow
    // session doesn't grow this list without bound.
    private val maxHistory: Int = 500,
) {
    private companion object {
        const val TAG = "AgenticExecutionEngine"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in AgenticExecutionEngine coroutine scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var job: Job? = null

    private val _lastDecision = MutableStateFlow<DirectRLDecision?>(null)
    /** The most recent decision this engine has observed - shadow-mode only, never acted on. */
    val lastDecision: StateFlow<DirectRLDecision?> = _lastDecision.asStateFlow()

    private val historyLock = Any()
    private val history = ArrayDeque<DirectRLDecision>()

    /** Snapshot of the most recent (up to [maxHistory]) decisions, oldest first - for future shadow-mode inspection UI, not consumed by execution. */
    fun decisionHistory(): List<DirectRLDecision> = synchronized(historyLock) { history.toList() }

    /** Starts observing [positionTarget]. Idempotent (calls [stop] first). Logs, but never places, orders. */
    fun start() {
        stop()
        job = positionTarget
            .filterNotNull()
            .onEach { decision -> onDecision(decision) }
            .catch { e -> Log.e(TAG, "Error observing direct-RL decision; dropping tick", e) }
            .launchIn(scope)
    }

    fun stop() {
        job?.cancel()
    }

    private fun onDecision(decision: DirectRLDecision) {
        _lastDecision.value = decision
        synchronized(historyLock) {
            history.addLast(decision)
            while (history.size > maxHistory) history.removeFirst()
        }
        // Shadow mode: log only. No order is ever placed from here - see
        // this class's kdoc for the validation gate that has to pass first.
        Log.i(
            TAG,
            "[shadow] t=${decision.timestampMs} target=${decision.targetPosition} " +
                "shouldTrade=${decision.shouldTrade} gated=${decision.gatedPosition} " +
                "r=${decision.netReturn} mu=${decision.mu} utility=${decision.utility} " +
                "ir=${decision.informationRatio} (not sent to any execution repository)",
        )
    }
}
