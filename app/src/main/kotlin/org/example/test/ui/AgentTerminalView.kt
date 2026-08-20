package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.test.agent.AgentLogBus
import org.example.test.agent.AgentLogLevel
import org.example.test.agent.AgentLogLine
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Low-level, terminal-style readout of [AgentLogBus] - the "how does the
 * agent actually learn" transparency view.
 *
 * Perf note (this shape is deliberate, not incidental): an earlier version
 * of this view created two new [TextView]s per incoming log line, so a
 * scalping-frequency agent (several ticks/sec) meant a main-thread layout
 * pass just as often, and that cost scaled with how long the agent had
 * been running - the actual cause of the "app gets laggier over a long
 * session" issue, not the RL math itself. This version instead:
 *  - buffers incoming lines in a plain list (no view work) as they arrive
 *  - flushes to a *single* [TextView] on a fixed ~200ms cadence, so UI work
 *    happens at a bounded rate regardless of tick frequency
 *  - trims old text by character offset instead of removing child views
 * A single TextView with a capped [SpannableStringBuilder] is dramatically
 * cheaper to update than N child views, and this way the *display* rate is
 * decoupled from the *agent's* tick rate entirely.
 */
class AgentTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    private val maxLines = 300
    private val flushIntervalMs = 200L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val textView: TextView
    private var scope: CoroutineScope? = null

    // Line-by-line buffer of not-yet-rendered lines waiting to be flushed
    // into [textView]. Access is confined to the main thread (both the
    // collector and the flush loop run on Dispatchers.Main), so no locking.
    private val pending = ArrayDeque<AgentLogLine>()
    private val lineLengths = ArrayDeque<Int>() // char length of each rendered line still in textView, oldest first

    init {
        setBackgroundColor(Color.parseColor("#0D1117"))
        isFillViewport = true
        textView = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8B93A7"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        addView(textView)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val newScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = newScope
        // Collector: cheap, just enqueues - no view work happens here.
        newScope.launch {
            AgentLogBus.lines.collect { line -> pending.addLast(line) }
        }
        // Flush loop: the only place that touches textView, at a fixed
        // cadence independent of how fast the agent is actually ticking.
        newScope.launch {
            while (true) {
                delay(flushIntervalMs)
                if (pending.isNotEmpty()) flushPending()
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        pending.clear()
        super.onDetachedFromWindow()
    }

    private fun flushPending() {
        val wasAtBottom = isScrolledToBottom()

        val builder = SpannableStringBuilder(textView.text)
        while (pending.isNotEmpty()) {
            val line = pending.removeFirst()
            val rendered = renderLine(line)
            val start = builder.length
            builder.append(rendered)
            builder.append('\n')
            builder.setSpan(
                ForegroundColorSpan(colorFor(line.level)),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            lineLengths.addLast(rendered.length + 1)
        }

        // Trim from the front by whole lines once over the cap, so this
        // stays a bounded live console instead of an ever-growing buffer.
        while (lineLengths.size > maxLines) {
            val drop = lineLengths.removeFirst()
            builder.delete(0, drop.coerceAtMost(builder.length))
        }

        textView.text = builder
        if (wasAtBottom) post { fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderLine(line: AgentLogLine): String =
        "${timeFormat.format(line.timestampMs)} ${levelTag(line.level)} ${line.text}"

    // Treat "close enough to the bottom" as "at the bottom" so a batch that
    // lands while the view is settling doesn't get misread as the person
    // having manually scrolled up.
    private fun isScrolledToBottom(): Boolean {
        val child = getChildAt(0) ?: return true
        val diff = child.bottom - (height + scrollY)
        return diff <= dp(24)
    }

    private fun levelTag(level: AgentLogLevel): String = when (level) {
        AgentLogLevel.INFO -> "[INFO]"
        AgentLogLevel.DECIDE -> "[DECIDE]"
        AgentLogLevel.LEARN -> "[LEARN]"
        AgentLogLevel.TRADE -> "[TRADE]"
        AgentLogLevel.GUARDRAIL -> "[GUARD]"
        AgentLogLevel.ERROR -> "[ERROR]"
    }

    private fun colorFor(level: AgentLogLevel): Int = when (level) {
        AgentLogLevel.INFO -> Color.parseColor("#8B93A7")
        AgentLogLevel.DECIDE -> Color.parseColor("#4FC3F7")
        AgentLogLevel.LEARN -> Color.parseColor("#B39DDB")
        AgentLogLevel.TRADE -> Color.parseColor("#26A69A")
        AgentLogLevel.GUARDRAIL -> Color.parseColor("#FFB74D")
        AgentLogLevel.ERROR -> Color.parseColor("#EF5350")
    }

    /** Removes every line currently shown, without touching [AgentLogBus] itself. */
    fun clearDisplay() {
        textView.text = ""
        lineLengths.clear()
        pending.clear()
    }
}
