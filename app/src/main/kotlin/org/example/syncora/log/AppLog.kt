package org.example.syncora.log

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow




enum class LogSource {
    
    AGENT,

    
    TRADING,

    
    ACCOUNT,

    
    SYSTEM,
}












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










object AppLog {

    private const val MAX_ENTRIES = 500

    private val idGenerator = AtomicLong(0)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    
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

    
    
    fun agent(level: LogLevel, message: String) = log(LogSource.AGENT, level, message)
    fun trading(level: LogLevel, message: String) = log(LogSource.TRADING, level, message)
    fun account(level: LogLevel, message: String) = log(LogSource.ACCOUNT, level, message)
    fun system(level: LogLevel, message: String) = log(LogSource.SYSTEM, level, message)
}