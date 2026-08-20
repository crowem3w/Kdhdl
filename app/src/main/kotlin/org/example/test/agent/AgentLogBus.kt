package org.example.test.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/** Severity/category of an [AgentLogLine] - drives color in [org.example.test.ui.AgentTerminalView]. */
enum class AgentLogLevel { INFO, DECIDE, LEARN, TRADE, GUARDRAIL, ERROR }

data class AgentLogLine(
    val timestampMs: Long,
    val level: AgentLogLevel,
    val text: String,
)

/**
 * Process-wide, UI-agnostic bus of what the RL agent is actually doing,
 * tick by tick - the source of truth behind the transparency terminal.
 *
 * [RlAgentController] is the only writer. Anything that wants to *observe*
 * the agent (currently [org.example.test.ui.AgentTerminalView], but this
 * could just as well be a debug export or a future "explain this trade"
 * screen) collects [lines] instead of being wired into the controller
 * directly, so instrumentation and display stay decoupled.
 *
 * [replay] on the [MutableSharedFlow] means a terminal view opened after
 * the agent has already been running still sees recent history instead of
 * an empty screen - this alone is most of what makes the panel feel like a
 * real log rather than a live-only toy.
 */
object AgentLogBus {
    private const val REPLAY_CAPACITY = 400
    private const val EXTRA_BUFFER = 200

    private val _lines = MutableSharedFlow<AgentLogLine>(
        replay = REPLAY_CAPACITY,
        extraBufferCapacity = EXTRA_BUFFER,
    )
    val lines = _lines.asSharedFlow()

    /** Non-suspending on purpose - callers are on the hot per-tick path and must never block on this. */
    fun log(level: AgentLogLevel, text: String) {
        _lines.tryEmit(AgentLogLine(System.currentTimeMillis(), level, text))
    }

    fun clear() {
        // MutableSharedFlow has no clear(); resetting the replay cache means
        // emitting a marker line so a fresh terminal collector at least
        // starts from a visible boundary rather than silently keeping old
        // history around forever in the replay cache.
        log(AgentLogLevel.INFO, "── reset ──")
    }

    fun formatQValues(qValues: DoubleArray, actions: Array<AgentAction>): String =
        actions.indices.joinToString(" ") { i -> "${actions[i].name}=${"%.4f".format(Locale.US, qValues[i])}" }
}
