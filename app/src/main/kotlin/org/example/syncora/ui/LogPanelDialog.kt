package org.example.syncora.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.example.syncora.R

/**
 * "Log Panel" - a dark-mode glass modal that surfaces recent chart / connection / trading
 * activity. Backed by [LogEntry] items; wire [entries] up to a real log source when one exists,
 * it currently ships with representative placeholder data so the panel can be reviewed as-is.
 *
 * Glass design rules this dialog follows:
 *  - The card is never see-through content alone: a semi-opaque tint layer always sits between
 *    the blurred backdrop and the text (see [cardColor]), and text also carries a subtle shadow,
 *    so contrast holds even if whatever is behind the dialog happens to be bright.
 *  - If cross-window blur isn't available (pre-Android 12, or the OS has it turned off) the card
 *    falls back to a much more opaque fill ([CARD_FILL_OPACITY_SOLID_FALLBACK]) plus a stronger
 *    scrim, so the dialog still reads as an intentional solid surface rather than a broken glass
 *    effect with nothing behind it to blur.
 *  - The backdrop blur radius is capped to a modest 10-20dp range - enough to read as "glass"
 *    without losing the see-through quality.
 *  - A 1px hairline border at ~20% white opacity outlines the card so it doesn't bleed into the
 *    chart behind it.
 */
class LogPanelDialog(context: Context) : Dialog(context, R.style.TradingModalTheme) {

    enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }

    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val message: String,
    )

    private companion object {
        // Glass fill: enough tint to guarantee contrast, translucent enough to still read as glass.
        const val CARD_FILL_OPACITY_GLASS = 0.72f
        // Solid fallback when blur isn't supported / enabled - reads as an intentional solid panel.
        const val CARD_FILL_OPACITY_SOLID_FALLBACK = 0.94f
        val CARD_BASE_COLOR_RGB = Color.parseColor("#1C1C1E")

        // Backdrop blur radius kept within the 10-20dp "glass, not mud" range.
        const val BACKDROP_BLUR_DP = 16

        const val CARD_CORNER_RADIUS_DP = 16
        const val BORDER_WIDTH_DP = 1

        const val DIM_WHEN_GLASS = 0.35f
        const val DIM_WHEN_SOLID_FALLBACK = 0.6f

        const val MAX_LIST_HEIGHT_DP = 360
    }

    // 1px hairline border at ~20% white opacity - sells the edge of the glass card.
    private val borderColor = Color.parseColor("#33FFFFFF")
    private val dividerColor = Color.parseColor("#26FFFFFF")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val scrimColor = Color.parseColor("#99000000")
    private val textShadowColor = Color.parseColor("#99000000")

    private val levelColors = mapOf(
        LogLevel.INFO to Color.parseColor("#5B9CF6"),
        LogLevel.SUCCESS to Color.parseColor("#22D3C5"),
        LogLevel.WARNING to Color.parseColor("#FFC94D"),
        LogLevel.ERROR to Color.parseColor("#F65B5B"),
    )

    /** Placeholder data. Replace with a real backing source (e.g. an event bus / log repository). */
    private var entries: List<LogEntry> = listOf(
        LogEntry("14:32:08", LogLevel.SUCCESS, "Connected to Bitget market data feed"),
        LogEntry("14:31:55", LogLevel.INFO, "Timeframe changed to 15m"),
        LogEntry("14:28:41", LogLevel.INFO, "Trend line drawn on BTCUSDT chart"),
        LogEntry("14:20:12", LogLevel.WARNING, "Reconnecting - request timed out"),
        LogEntry("14:19:03", LogLevel.SUCCESS, "Paper order filled: BUY 0.05 BTC @ 66,842.5"),
        LogEntry("14:11:47", LogLevel.ERROR, "Failed to load funding rate history"),
        LogEntry("14:02:30", LogLevel.INFO, "Switched to Live trading account"),
        LogEntry("13:58:16", LogLevel.INFO, "Drawing cleared from chart"),
    )

    private var blurApplied = false

    private lateinit var cardBackground: GradientDrawable
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyStateText: TextView

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowBlur()
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        renderEntries()
    }

    /** Sets the log entries shown by the panel and re-renders if the dialog is already showing. */
    fun setEntries(newEntries: List<LogEntry>) {
        entries = newEntries
        if (::listContainer.isInitialized) renderEntries()
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                win.attributes = win.attributes.apply { blurBehindRadius = dp(BACKDROP_BLUR_DP) }
                blurApplied = true
            }
        }
        // Solid fallback: no (or disabled) blur support means less light passes through from
        // behind the dialog, so darken the scrim more to keep the panel reading as intentional.
        win.setDimAmount(if (blurApplied) DIM_WHEN_GLASS else DIM_WHEN_SOLID_FALLBACK)
    }

    private fun buildRootView(): View {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(scrimColor)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dp(CARD_CORNER_RADIUS_DP).toFloat()
        val fillOpacity = if (blurApplied) CARD_FILL_OPACITY_GLASS else CARD_FILL_OPACITY_SOLID_FALLBACK
        val cardColor = Color.argb(
            (255 * fillOpacity).toInt(),
            Color.red(CARD_BASE_COLOR_RGB),
            Color.green(CARD_BASE_COLOR_RGB),
            Color.blue(CARD_BASE_COLOR_RGB),
        )

        cardBackground = GradientDrawable().apply {
            cornerRadius = cornerRadiusPx
            setColor(cardColor)
            setStroke(dp(BORDER_WIDTH_DP), borderColor)
        }

        val cardOuter = FrameLayout(context).apply {
            background = cardBackground
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dp(16).toFloat()
            isClickable = true
            setOnClickListener { /* swallow - don't dismiss when tapping the card itself */ }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        cardContent.addView(buildHeaderRow())
        cardContent.addView(View(context).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(14); bottomMargin = dp(6)
            }
        })
        cardContent.addView(buildListArea())

        cardOuter.addView(cardContent)
        root.addView(cardOuter)
        return root
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "Log Panel"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            // Text-shadow safety net: keeps contrast even if the blurred backdrop is bright.
            setShadowLayer(4f, 0f, 1f, textShadowColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(mutedColor)
            setShadowLayer(4f, 0f, 1f, textShadowColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dismiss() }
        }

        row.addView(titleText)
        row.addView(closeButton)
        return row
    }

    private fun buildListArea(): View {
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        emptyStateText = TextView(context).apply {
            text = "No log entries yet."
            textSize = 13f
            setTextColor(mutedColor)
            setShadowLayer(3f, 0f, 1f, textShadowColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            visibility = View.GONE
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Cap the height so a long log scrolls inside the card instead of the card
            // growing past the screen.
            maxHeight = dp(MAX_LIST_HEIGHT_DP)
            addView(listContainer)
        }

        val wrapper = FrameLayout(context)
        wrapper.addView(scroll)
        wrapper.addView(emptyStateText)
        return wrapper
    }

    private fun renderEntries() {
        listContainer.removeAllViews()
        emptyStateText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        entries.forEachIndexed { index, entry ->
            listContainer.addView(buildEntryRow(entry))
            if (index != entries.lastIndex) {
                listContainer.addView(View(context).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(8); bottomMargin = dp(8)
                    }
                })
            }
        }
    }

    private fun buildEntryRow(entry: LogEntry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(levelColors.getValue(entry.level))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                topMargin = dp(5)
                marginEnd = dp(10)
            }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textColumn.addView(TextView(context).apply {
            text = entry.message
            textSize = 13.5f
            setTextColor(labelColor)
            setShadowLayer(3f, 0f, 1f, textShadowColor)
        })
        textColumn.addView(TextView(context).apply {
            text = entry.timestamp
            textSize = 11.5f
            setTextColor(mutedColor)
            setShadowLayer(3f, 0f, 1f, textShadowColor)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(dot)
        row.addView(textColumn)
        return row
    }
}
