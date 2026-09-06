package org.example.syncora.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.syncora.R
import org.example.syncora.log.AppLog
import org.example.syncora.log.LogEntry
import org.example.syncora.log.LogLevel
import org.example.syncora.log.LogSource

/**
 * Centered "LogPanel" modal: a live, terminal-style feed of [AppLog] activity.
 *
 * This is the one place the whole app's activity is traced end to end - agent training and
 * decisions, trade execution outcomes, and account/Bitget live connectivity - so it can be
 * inspected and (informally) recorded during a session. Meaning is carried entirely by color
 * (semantic per [LogLevel]); there are no icons anywhere in the terminal.
 */
class LogPanelDialog(context: Context) : Dialog(context, R.style.TradingModalTheme) {

    private companion object {
        const val CARD_CORNER_RADIUS_DP = 12
        const val GLOW_EXTRA_DP = 22
        const val CARD_HEIGHT_DP = 420
        const val BORDER_WIDTH_DP = 0.5f

        const val BACKDROP_BLUR_PERCENT = 0.85f
        const val MAX_BACKDROP_BLUR_DP = 100

        // Charcoal-black glass base with the faintest cool undertones (frame around the terminal).
        val GLASS_TOP_TINT = Color.parseColor("#2A2E3E")      // barely-there blue undertone
        val GLASS_BASE_TINT = Color.parseColor("#141519")     // frosted charcoal-black
        val GLASS_BOTTOM_TINT = Color.parseColor("#231B30")   // barely-there purple undertone

        const val GLASS_TOP_ALPHA = 0.55f
        const val GLASS_BASE_ALPHA = 0.72f
        const val GLASS_BOTTOM_ALPHA = 0.60f

        val BORDER_COLOR = Color.parseColor("#40FFFFFF")
        val GLOW_COLOR = Color.parseColor("#331C1A3D")
        val SCRIM_COLOR = Color.parseColor("#8A000000")

        // Terminal surface sits slightly darker/flatter than the glass frame around it, like a
        // console cut into the glass.
        val TERMINAL_BG = Color.parseColor("#DE0A0B0F")
        val DIVIDER_COLOR = Color.parseColor("#26FFFFFF")
        val TITLE_COLOR = Color.parseColor("#EAECEF")
        val MUTED_COLOR = Color.parseColor("#8A8D98")
        val TIMESTAMP_COLOR = Color.parseColor("#5B5E68")

        // --- Semantic level colors: this IS the color coding, there are no icons. ---
        val LEVEL_DEBUG = Color.parseColor("#6B6F7B")     // dim gray
        val LEVEL_INFO = Color.parseColor("#5AC8FA")      // cool cyan/blue
        val LEVEL_SUCCESS = Color.parseColor("#3DD68C")   // green
        val LEVEL_WARNING = Color.parseColor("#F5A623")   // amber
        val LEVEL_ERROR = Color.parseColor("#FF5C5C")     // red

        // Source tags get a muted, desaturated variant of the same palette so the eye still
        // reads level color as the primary signal.
        val SOURCE_AGENT = Color.parseColor("#B98CFF")
        val SOURCE_TRADING = Color.parseColor("#5AC8FA")
        val SOURCE_ACCOUNT = Color.parseColor("#F5A623")
        val SOURCE_SYSTEM = Color.parseColor("#8A8D98")

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private val dialogScope = CoroutineScope(Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var emptyStateText: TextView
    private lateinit var statusDot: View
    private lateinit var countText: TextView

    private fun dp(value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density).toInt()

    private fun dpf(value: Number): Float = value.toFloat() * context.resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        applyWindowBlur()
    }

    override fun onStart() {
        super.onStart()
        collectJob?.cancel()
        collectJob = dialogScope.launch {
            AppLog.entries.collect { entries -> renderEntries(entries) }
        }
    }

    override fun onStop() {
        super.onStop()
        collectJob?.cancel()
        collectJob = null
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)
        win.setDimAmount(0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val blurRadiusDp = (MAX_BACKDROP_BLUR_DP * BACKDROP_BLUR_PERCENT).toInt()
                win.attributes = win.attributes.apply { blurBehindRadius = dp(blurRadiusDp) }
            }
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun buildRootView(): View {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dpf(CARD_CORNER_RADIUS_DP)
        val glowCornerRadiusPx = dpf(CARD_CORNER_RADIUS_DP + GLOW_EXTRA_DP / 2)

        // Ambient glow halo sitting behind the glass card for soft separation from the chart.
        val glow = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = glowCornerRadiusPx
                setColor(GLOW_COLOR)
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP + GLOW_EXTRA_DP),
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20) - dp(GLOW_EXTRA_DP / 2)
                marginEnd = dp(20) - dp(GLOW_EXTRA_DP / 2)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(dpf(18), dpf(18), Shader.TileMode.CLAMP))
            }
        }

        // The frosted glass card: header + divider + scrollable terminal body.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    applyAlpha(GLASS_TOP_TINT, GLASS_TOP_ALPHA),
                    applyAlpha(GLASS_BASE_TINT, GLASS_BASE_ALPHA),
                    applyAlpha(GLASS_BOTTOM_TINT, GLASS_BOTTOM_ALPHA),
                ),
            ).apply {
                cornerRadius = cornerRadiusPx
                setStroke(dp(BORDER_WIDTH_DP).coerceAtLeast(1), BORDER_COLOR)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dpf(20)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.parseColor("#1A1030")
                outlineSpotShadowColor = Color.parseColor("#1A1030")
            }
            isClickable = true
            setOnClickListener { /* absorb clicks, keep dialog open */ }
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP),
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

        card.addView(buildHeaderRow())
        card.addView(View(context).apply {
            setBackgroundColor(DIVIDER_COLOR)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(10); bottomMargin = dp(10)
            }
        })
        card.addView(buildTerminalBody())

        root.addView(glow)
        root.addView(card)
        return root
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(LEVEL_SUCCESS)
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(8)
            }
        }

        val titleText = TextView(context).apply {
            text = "ACTIVITY LOG"
            textSize = 13f
            letterSpacing = 0.06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TITLE_COLOR)
        }

        countText = TextView(context).apply {
            text = "0"
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(MUTED_COLOR)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clearButton = TextView(context).apply {
            text = "CLEAR"
            textSize = 11f
            letterSpacing = 0.04f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED_COLOR)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66090C11"))
                cornerRadius = dpf(8)
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener { AppLog.clear() }
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(MUTED_COLOR)
            setPadding(dp(10), dp(4), dp(0), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }

        row.addView(statusDot)
        row.addView(titleText)
        row.addView(countText)
        row.addView(clearButton)
        row.addView(closeButton)
        return row
    }

    private fun buildTerminalBody(): View {
        val cornerRadiusPx = dpf(8)

        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(TERMINAL_BG)
                cornerRadius = cornerRadiusPx
                setStroke(dp(BORDER_WIDTH_DP).coerceAtLeast(1), Color.parseColor("#22FFFFFF"))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        terminalOutput = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setLineSpacing(dpf(3), 1f)
            setTextColor(LEVEL_INFO)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        terminalScroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                terminalOutput,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        emptyStateText = TextView(context).apply {
            text = "No activity yet. Agent, trading, and account events will stream here."
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(MUTED_COLOR)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        container.addView(terminalScroll)
        container.addView(emptyStateText)
        return container
    }

    private fun renderEntries(entries: List<LogEntry>) {
        countText.text = entries.size.toString()

        if (entries.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            terminalScroll.visibility = View.GONE
            return
        }
        emptyStateText.visibility = View.GONE
        terminalScroll.visibility = View.VISIBLE

        val builder = SpannableStringBuilder()
        entries.forEachIndexed { index, entry ->
            appendLine(builder, entry)
            if (index != entries.lastIndex) builder.append("\n")
        }
        terminalOutput.text = builder

        terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLine(builder: SpannableStringBuilder, entry: LogEntry) {
        val start = builder.length

        // Timestamp - always muted, never carries semantic meaning.
        builder.append(TIME_FORMAT.format(entry.timestampMs))
        builder.append("  ")

        // Source tag - fixed width-ish, colored per source so the eye can track a lane.
        val tagStart = builder.length
        builder.append(sourceTag(entry.source))
        builder.setSpan(ForegroundColorSpan(sourceColor(entry.source)), tagStart, builder.length, 0)
        builder.setSpan(StyleSpan(Typeface.BOLD), tagStart, builder.length, 0)
        builder.append(" ")

        // Message - colored by level. This is the primary semantic color coding in the terminal.
        val msgStart = builder.length
        builder.append(entry.message)
        builder.setSpan(ForegroundColorSpan(levelColor(entry.level)), msgStart, builder.length, 0)

        // Timestamp span applied last so it isn't overridden by the wider spans above.
        builder.setSpan(ForegroundColorSpan(TIMESTAMP_COLOR), start, tagStart, 0)
    }

    private fun sourceTag(source: LogSource): String = when (source) {
        LogSource.AGENT -> "[AGENT]  "
        LogSource.TRADING -> "[TRADE]  "
        LogSource.ACCOUNT -> "[ACCOUNT]"
        LogSource.SYSTEM -> "[SYSTEM] "
    }

    private fun sourceColor(source: LogSource): Int = when (source) {
        LogSource.AGENT -> SOURCE_AGENT
        LogSource.TRADING -> SOURCE_TRADING
        LogSource.ACCOUNT -> SOURCE_ACCOUNT
        LogSource.SYSTEM -> SOURCE_SYSTEM
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> LEVEL_DEBUG
        LogLevel.INFO -> LEVEL_INFO
        LogLevel.SUCCESS -> LEVEL_SUCCESS
        LogLevel.WARNING -> LEVEL_WARNING
        LogLevel.ERROR -> LEVEL_ERROR
    }
}
