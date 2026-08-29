package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.example.syncora.agent.AgentDecisionLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prompt 7f's visible on-screen agent status/log panel: a live,
 * reverse-chronological feed of every decision the agent makes - one row
 * per [AgentDecisionLogEntry] - following the same conventions
 * [PerformanceHudView] and [PaperTradingHistoryPanel] already establish for
 * this app's HUD-style panels (plain [LinearLayout] rows built and colored
 * in code, no RecyclerView/adapter machinery, `dp()`-scaled padding,
 * `Locale.US` fixed-point formatting).
 *
 * ### Pushed, not polled
 * This panel never reaches out to [org.example.syncora.agent.AgentLiveSession]
 * or [org.example.syncora.agent.AgentOrchestrator] to ask "what happened
 * last bar" - it only ever reacts to entries handed to [onDecision], either
 * one at a time (a caller with its own collection loop) or via [collectFrom]
 * (the usual case: hand it [org.example.syncora.agent.AgentLiveSession.decisionLog]
 * directly). This matches Prompt 7f's "subscribing to an
 * orchestrator-emitted decision-log stream rather than polling" requirement
 * exactly, and mirrors [PerformanceMonitor]'s own push-callback shape
 * ([PerformanceHudView.render] is called, never polls [PerformanceMonitor]).
 *
 * ### Bounded row count
 * Only the most recent [maxRows] entries are kept on screen (oldest rows
 * are dropped as new ones arrive) - a live session run for hours or days
 * (Prompt 7g's multi-week soak) must never grow this view's child count
 * without bound. The full, unbounded history already lives in
 * [org.example.syncora.agent.AgentOrchestrator.DecisionLog]-backed audit
 * storage the caller is responsible for (see that class's doc); this panel
 * is a live status readout, not the audit trail itself.
 *
 * ### Manual visual check (Prompt 7f's other exit criterion)
 * Automated coverage for this panel is necessarily the decision-log-stream
 * test in `AgentLiveSessionDecisionLogTest` (it asserts on
 * [org.example.syncora.agent.AgentLiveSession.decisionLog] - the exact
 * stream [collectFrom] wires into this panel - rather than on rendered
 * `View` output) - this repo has no Robolectric/View instrumentation for
 * plain JVM unit tests (see `build.gradle.kts`'s `testOptions` comment), so
 * the *rendering* half of Prompt 7f's exit
 * criterion ("a manual visual check confirms the panel renders legibly and
 * updates live") is necessarily a manual step once this panel is dropped
 * into a real activity/layout: drive a few live bars through a session
 * wired to this panel via [collectFrom] and confirm rows appear, newest on
 * top, without layout jank.
 */
class AgentStatusLogPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private companion object {
        val COLOR_BACKGROUND = Color.parseColor("#131722")
        val COLOR_BORDER = Color.parseColor("#2A2E39")
        val COLOR_LABEL = Color.parseColor("#EAECEF")
        val COLOR_MUTED = Color.parseColor("#B2B5BE")
        val COLOR_LONG = Color.parseColor("#26A69A")
        val COLOR_SHORT = Color.parseColor("#EF5350")
        val COLOR_FLAT = Color.parseColor("#B2B5BE")
        const val DEFAULT_MAX_ROWS = 200
        const val POSITION_FLAT_EPSILON = 1e-6f
    }

    /** Most rows this panel keeps on screen - see class doc's "Bounded row count". */
    var maxRows: Int = DEFAULT_MAX_ROWS

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val emptyStateText: TextView
    private val rowContainer: LinearLayout
    private val scrollView: ScrollView

    /** Count of rows ever appended, including ones since scrolled out by [maxRows] - exposed for tests/diagnostics. */
    var totalEntriesReceived: Int = 0
        private set

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        setPadding(dp(8), dp(6), dp(8), dp(6))

        addView(
            TextView(context).apply {
                text = "AGENT LOG"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_MUTED)
                setPadding(dp(4), 0, 0, dp(4))
            },
        )

        emptyStateText = TextView(context).apply {
            text = "Waiting for the next bar close…"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }

        rowContainer = LinearLayout(context).apply { orientation = VERTICAL }

        scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(rowContainer)
        }

        addView(emptyStateText)
        addView(scrollView)
    }

    /**
     * Appends one new row for [entry] at the top of the log (newest first)
     * and trims anything beyond [maxRows]. Safe to call from any thread a
     * caller's own collection loop happens to run on *only if* that
     * thread is the main/UI thread - same requirement every other Android
     * `View` mutation in this codebase already has; [collectFrom] satisfies
     * this automatically when [scope] is a main-dispatcher scope.
     */
    fun onDecision(entry: AgentDecisionLogEntry) {
        totalEntriesReceived++
        emptyStateText.visibility = View.GONE

        rowContainer.addView(buildRow(entry), 0)
        while (rowContainer.childCount > maxRows) {
            rowContainer.removeViewAt(rowContainer.childCount - 1)
        }
    }

    /** Clears every rendered row (not [totalEntriesReceived]) - e.g. when a new live session starts. */
    fun clear() {
        rowContainer.removeAllViews()
        emptyStateText.visibility = View.VISIBLE
    }

    /**
     * Convenience wiring for the common case: collect [decisionLog]
     * (typically [org.example.syncora.agent.AgentLiveSession.decisionLog])
     * on [scope] and push every emission into [onDecision] - "subscribing
     * to an orchestrator-emitted decision-log stream rather than polling"
     * (Prompt 7f), in one call. Returns the launched [Job] so a caller can
     * cancel it (e.g. in `onDetachedFromWindow`) to stop updating a panel
     * that is no longer visible.
     */
    fun collectFrom(decisionLog: Flow<AgentDecisionLogEntry>, scope: CoroutineScope): Job =
        decisionLog.onEach(::onDecision).launchIn(scope)

    private fun buildRow(entry: AgentDecisionLogEntry): View {
        val position = entry.position
        val positionColor = when {
            kotlin.math.abs(position) <= POSITION_FLAT_EPSILON -> COLOR_FLAT
            position > 0f -> COLOR_LONG
            else -> COLOR_SHORT
        }
        val positionLabel = if (kotlin.math.abs(position) <= POSITION_FLAT_EPSILON) {
            "FLAT"
        } else {
            String.format(Locale.US, "%s %.2f", if (position > 0f) "LONG" else "SHORT", kotlin.math.abs(position))
        }

        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            TextView(context).apply {
                text = timeFormat.format(Date(entry.timestampMs))
                textSize = 11f
                setTextColor(COLOR_MUTED)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        headerRow.addView(
            TextView(context).apply {
                text = positionLabel
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(positionColor)
            },
        )
        row.addView(headerRow)

        row.addView(
            TextView(context).apply {
                text = entry.featuresSummary
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_LABEL)
                setPadding(0, dp(2), 0, 0)
            },
        )

        row.addView(
            TextView(context).apply {
                text = String.format(
                    Locale.US,
                    "reward %+.6f   dsr %+.6f   bar #%d",
                    entry.reward,
                    entry.differentialSharpe,
                    entry.barIndex,
                )
                textSize = 10.5f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(2), 0, 0)
            },
        )

        row.addView(buildDivider())
        return row
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(COLOR_BORDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
        }
    }
}
