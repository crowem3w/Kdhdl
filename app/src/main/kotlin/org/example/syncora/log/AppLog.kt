package org.example.syncora.log

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a [LogEntry] originated. Drives the source tag shown in the terminal.
 */
enum class LogSource {
    /** The RRL agent: warm-up, training steps, decisions, checkpoint save/restore. */
    AGENT,

    /** Order placement/closing and other trade execution outcomes. */
    TRADING,

    /** Account/session concerns: mode switches, Bitget live connectivity, credentials. */
    ACCOUNT,

    /** Everything else: app lifecycle, background services, general diagnostics. */
    SYSTEM,
}

/**
 * Severity of a [LogEntry]. This is what [LogPanelDialog][org.example.syncora.ui.LogPanelDialog]
 * uses to semantically color-code each line - there are no icons anywhere in the terminal, color
 * alone carries the meaning:
 *
 *  - [SUCCESS] - green - something completed/validated correctly (order filled, connected, checkpoint saved)
 *  - [ERROR]   - red - something failed (rejected order, auth failure, exception)
 *  - [WARNING] - amber - degraded or noteworthy but non-fatal (retrying, falling back, stale data)
 *  - [INFO]    - cool blue/cyan - neutral state changes and narration
 *  - [DEBUG]   - dim gray - low-level, high-frequency detail (per-bar agent steps)
 */
enum class LogLevel {
    DEBUG,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val source: LogSource,
    val level: LogLevel,
    val message: String,
)

/**
 * Single, process-wide sink for everything [LogPanelDialog][org.example.syncora.ui.LogPanelDialog]
 * displays as a live terminal: agent training/decisions, trade execution, and account/Bitget
 * connectivity. Any class anywhere in the app can call into this object directly - it has no
 * Android Context dependency and is safe to call from any thread/dispatcher.
 *
 * Entries are kept in a capped in-memory ring buffer (not persisted); this is a live activity
 * feed, not an audit log.
 */
object AppLog {

    private const val MAX_ENTRIES = 500

    private val idGenerator = AtomicLong(0)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Snapshot list of currently buffered entries, oldest first. Collect for live updates. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(source: LogSource, level: LogLevel, message: String) {
        val entry = LogEntry(
            id = idGenerator.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            source = source,
            level = level,
            message = message,
        )
        val snapshot = synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            buffer.toList()
        }
        _entries.value = snapshot

        // Mirror into logcat too, so `adb logcat` still shows everything during development.
        val androidPriority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO, LogLevel.SUCCESS -> Log.INFO
            LogLevel.WARNING -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(androidPriority, "AppLog/${source.name}", message)
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
    }

    // Convenience helpers - one per source, so call sites read naturally, e.g.
    // AppLog.agent(LogLevel.SUCCESS, "Checkpoint saved").
    fun agent(level: LogLevel, message: String) = log(LogSource.AGENT, level, message)
    fun trading(level: LogLevel, message: String) = log(LogSource.TRADING, level, message)
    fun account(level: LogLevel, message: String) = log(LogSource.ACCOUNT, level, message)
    fun system(level: LogLevel, message: String) = log(LogSource.SYSTEM, level, message)
}
