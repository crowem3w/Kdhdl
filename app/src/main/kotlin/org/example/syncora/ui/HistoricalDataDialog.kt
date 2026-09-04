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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.syncora.R
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.Ohlcv1mArchiveStore
import org.example.syncora.bitget.Ohlcv1mCsvExporter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Full-screen modal launched from the bottom-bar "data" button.
 *
 * Mirrors [TradingModeDialog]'s glass-card shell (same blurred backdrop,
 * translucent bordered card, header row with back/close controls) but opens
 * straight onto a vertical list of data categories - Order Book, OHLCV, Open
 * Interest, Funding Rates - each row showing a small leading icon plus a
 * title/description text block. Each top-level row drills into its own
 * screen; those screens are still bare placeholders for now, except OHCLV
 * Data, which drills one level further into a "1m (minute)" info sheet - see
 * [buildOhlcvSubScreen] and [buildOhlcv1mInfoScreen].
 */
class HistoricalDataDialog(
    context: Context,
    private val archiveStore: Ohlcv1mArchiveStore = Ohlcv1mArchiveStore(context.applicationContext),
    private val csvExporter: Ohlcv1mCsvExporter = Ohlcv1mCsvExporter(context.applicationContext),
) : Dialog(context, R.style.TradingModalTheme) {

    private enum class Screen { OPTIONS, ORDER_BOOK, OHLCV, OHLCV_1M, OPEN_INTEREST, FUNDING_RATES }

    private data class Category(
        val screen: Screen,
        val title: String,
        val description: String,
        val iconRes: Int,
    )

    private val categories = listOf(
        Category(
            Screen.ORDER_BOOK,
            "Order Book",
            "Live bid and ask depth by price level.",
            R.drawable.ic_data_orderbook,
        ),
        Category(
            Screen.OHLCV,
            "OHCLV Data",
            "Open, high, low, close, and volume candles.",
            R.drawable.ic_data_ohlcv,
        ),
        Category(
            Screen.OPEN_INTEREST,
            "Open Interest",
            "Total outstanding derivative contracts.",
            R.drawable.ic_data_open_interest,
        ),
        Category(
            Screen.FUNDING_RATES,
            "Funding Rates",
            "Perpetual futures funding rate history.",
            R.drawable.ic_data_funding_rates,
        ),
    )

    private companion object {
        // Same glass-panel math as TradingModeDialog, kept in sync so the
        // two modals read as one consistent design language.
        const val CARD_FILL_OPACITY_PERCENT = 0.75f
        val CARD_BASE_COLOR_RGB = Color.parseColor("#1C1C1E")

        const val BACKDROP_BLUR_PERCENT = 0.80f
        const val MAX_BACKDROP_BLUR_DP = 100

        const val CARD_CORNER_RADIUS_DP = 14

        // How often the OHCLV 1m info sheet re-reads stats from the local
        // archive while it's on screen - see buildOhlcv1mInfoScreen().
        const val LIVE_REFRESH_INTERVAL_MS = 1_000L

        // The archive only tracks the app's default trading pair for now,
        // matching TradingChartPipeline's own default instId.
        const val ARCHIVE_SYMBOL = "BTCUSDT"

        val ACCENT_COLOR = Color.parseColor("#26A69A")
        val NEGATIVE_COLOR = Color.parseColor("#F6465D")
    }

    private val cardColor = Color.argb(
        (255 * CARD_FILL_OPACITY_PERCENT).toInt(),
        Color.red(CARD_BASE_COLOR_RGB),
        Color.green(CARD_BASE_COLOR_RGB),
        Color.blue(CARD_BASE_COLOR_RGB),
    )
    private val cardHighlightTop = Color.parseColor("#4DFFFFFF")
    private val dividerColor = Color.parseColor("#26FFFFFF")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val scrimColor = Color.parseColor("#99000000")

    private val thinFont by lazy { ResourcesCompat.getFont(context, R.font.inter_thin) }

    private val timestampFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    private lateinit var rootView: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: TextView
    private lateinit var contentContainer: FrameLayout
    private val optionsScreen by lazy { buildOptionsScreen() }
    private var currentScreen: Screen = Screen.OPTIONS

    // Simple back-stack so OHLCV_1M -> OHLCV -> OPTIONS unwinds one level at
    // a time instead of always jumping straight back to OPTIONS.
    private val screenStack = ArrayDeque<Screen>()

    // Dialog-scoped coroutine scope: cancelled on dismiss so the live-update
    // loop (and any in-flight export) never outlives the dialog's view tree.
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var liveUpdateJob: Job? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { dialogScope.cancel() }
        applyWindowBlur()
        screenStack.clear()
        renderScreen(Screen.OPTIONS)
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)
        win.setDimAmount(0.35f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val blurRadiusDp = (MAX_BACKDROP_BLUR_DP * BACKDROP_BLUR_PERCENT).toInt()
                win.attributes = win.attributes.apply { blurBehindRadius = dp(blurRadiusDp) }
            }
        }
    }

    // ---- Root scaffold: full-screen scrim + centered glass card ----

    private fun buildRootView(): View {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(scrimColor)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dp(CARD_CORNER_RADIUS_DP).toFloat()

        val cardOuter = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = cornerRadiusPx
                setColor(cardColor)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dp(16).toFloat()
            isClickable = true
            setOnClickListener { /* consume: don't dismiss when tapping inside the card */ }
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
            setPadding(dp(18), dp(16), dp(18), dp(20))
        }
        cardContent.addView(buildHeaderRow())
        cardContent.addView(View(context).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(14); bottomMargin = dp(14)
            }
        })
        contentContainer = FrameLayout(context)
        cardContent.addView(contentContainer)

        val highlightStrip = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, cardHighlightTop, Color.TRANSPARENT),
            )
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(CARD_CORNER_RADIUS_DP)
                rightMargin = dp(CARD_CORNER_RADIUS_DP)
                gravity = Gravity.TOP
            }
        }

        cardOuter.addView(cardContent)
        cardOuter.addView(highlightStrip)

        rootView.addView(cardOuter)
        return rootView
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        backButton = TextView(context).apply {
            text = "‹"
            textSize = 20f
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(0, 0, dp(10), 0)
            visibility = View.GONE
            setOnClickListener { navigateBack() }
        }

        titleText = TextView(context).apply {
            text = "Historical Data"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dismiss() }
        }

        row.addView(backButton)
        row.addView(titleText)
        row.addView(closeButton)
        return row
    }

    // ---- Navigation: small back-stack over the flat Screen enum ----

    private fun navigateTo(screen: Screen) {
        screenStack.push(currentScreen)
        renderScreen(screen)
    }

    private fun navigateBack() {
        val previous = if (screenStack.isEmpty()) Screen.OPTIONS else screenStack.pop()
        renderScreen(previous)
    }

    private fun renderScreen(screen: Screen) {
        // Any screen-scoped live-update loop belongs to the screen that's
        // about to disappear, not the one being shown - stop it here so
        // navigating away from OHLCV_1M also stops polling the archive.
        liveUpdateJob?.cancel()
        liveUpdateJob = null

        currentScreen = screen
        contentContainer.removeAllViews()
        backButton.visibility = if (screen == Screen.OPTIONS) View.GONE else View.VISIBLE

        when (screen) {
            Screen.OPTIONS -> {
                titleText.text = "Historical Data"
                contentContainer.addView(optionsScreen)
            }
            Screen.OHLCV -> {
                titleText.text = categories.first { it.screen == Screen.OHLCV }.title
                contentContainer.addView(buildOhlcvSubScreen())
            }
            Screen.OHLCV_1M -> {
                titleText.text = "1m (minute)"
                contentContainer.addView(scrollableCopy(buildOhlcv1mInfoScreen()))
            }
            Screen.ORDER_BOOK, Screen.OPEN_INTEREST, Screen.FUNDING_RATES -> {
                titleText.text = categories.first { it.screen == screen }.title
                contentContainer.addView(scrollableCopy(buildEmptyScreen()))
            }
        }
    }

    private fun scrollableCopy(content: View): View {
        (content.parent as? ViewGroup)?.removeView(content)
        return ScrollView(context).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(content)
        }
    }

    // ---- Screen 1: vertical list of icon + title/description rows ----

    private fun buildOptionsScreen(): View {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        categories.forEachIndexed { index, category ->
            list.addView(buildTile(category.title, category.description, category.iconRes) { navigateTo(category.screen) })
            if (index != categories.lastIndex) {
                list.addView(rowDivider())
            }
        }
        return list
    }

    // ---- Screen 2 (OHLCV only): sub-list of stored resolutions, currently just 1m ----

    private fun buildOhlcvSubScreen(): View {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        list.addView(
            buildTile(
                title = "1m (minute)",
                description = "Locally archived 1-minute candles - live stats and CSV export.",
                iconRes = R.drawable.ic_data_ohlcv,
                onClick = { navigateTo(Screen.OHLCV_1M) },
            ),
        )
        return list
    }

    private fun buildTile(title: String, description: String, iconRes: Int, onClick: () -> Unit): View {
        val rippleValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(rippleValue.resourceId)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(ImageView(context).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(12)
            }
        })

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textContainer.addView(TextView(context).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        })

        textContainer.addView(TextView(context).apply {
            text = description
            textSize = 11.5f
            typeface = thinFont ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)

        return row
    }

    private fun rowDivider(): View = View(context).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        }
    }

    // ---- Screens 3-5 (Order Book / Open Interest / Funding Rates): bare placeholders ----

    private fun buildEmptyScreen(): View = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(160),
        )
    }

    // ---- OHLCV -> 1m (minute): information sheet on the locally stored 1m OHLCV archive ----

    private data class StatRow(val root: View, val value: TextView)

    private fun buildOhlcv1mInfoScreen(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        container.addView(
            TextView(context).apply {
                text = "Every 1-minute candle this device has recorded while the app has been " +
                    "running, kept locally and never uploaded."
                textSize = 12f
                typeface = thinFont ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setTextColor(mutedColor)
                setPadding(0, 0, 0, dp(14))
            },
        )

        val liveStatus = TextView(context).apply {
            text = "●  Live — updating in real time"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT_COLOR)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(liveStatus)

        val countRow = buildStatRow("Candles stored locally", "—")
        container.addView(countRow.root)
        container.addView(sectionDivider())

        container.addView(sectionLabel("Latest candle"))
        val latestTimeRow = buildStatRow("Time", "—")
        val latestOpenRow = buildStatRow("Open", "—")
        val latestHighRow = buildStatRow("High", "—")
        val latestLowRow = buildStatRow("Low", "—")
        val latestCloseRow = buildStatRow("Close", "—")
        val latestVolRow = buildStatRow("Volume (base)", "—")
        listOf(latestTimeRow, latestOpenRow, latestHighRow, latestLowRow, latestCloseRow, latestVolRow)
            .forEach { container.addView(it.root) }
        container.addView(sectionDivider())

        container.addView(sectionLabel("Deepest historical candle"))
        val deepestTimeRow = buildStatRow("Time", "—")
        val historySpanRow = buildStatRow("History span", "—")
        listOf(deepestTimeRow, historySpanRow).forEach { container.addView(it.root) }
        container.addView(sectionDivider())

        val exportStatus = TextView(context).apply {
            textSize = 11.5f
            typeface = thinFont ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(mutedColor)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }

        container.addView(buildActionButton("Export CSV") { runExport(exportStatus) })
        container.addView(exportStatus)

        liveUpdateJob = dialogScope.launch {
            while (isActive) {
                val stats = archiveStore.stats()

                countRow.value.text = stats.candleCount.toString()

                val latest = stats.latest
                latestTimeRow.value.text = latest?.let { formatTimestamp(it.startTime) } ?: "No data yet"
                latestOpenRow.value.text = latest?.let { formatPrice(it.open) } ?: "—"
                latestHighRow.value.text = latest?.let { formatPrice(it.high) } ?: "—"
                latestLowRow.value.text = latest?.let { formatPrice(it.low) } ?: "—"
                latestCloseRow.value.text = latest?.let { formatPrice(it.close) } ?: "—"
                latestVolRow.value.text = latest?.let { formatVolume(it.baseVolume) } ?: "—"

                val deepest = stats.deepest
                deepestTimeRow.value.text = deepest?.let { formatTimestamp(it.startTime) } ?: "No data yet"
                historySpanRow.value.text = if (latest != null && deepest != null) {
                    formatDuration(latest.startTime - deepest.startTime)
                } else {
                    "—"
                }

                delay(LIVE_REFRESH_INTERVAL_MS)
            }
        }

        return container
    }

    private fun runExport(statusText: TextView) {
        statusText.visibility = View.VISIBLE
        statusText.setTextColor(mutedColor)
        statusText.text = "Exporting…"
        dialogScope.launch {
            try {
                val candles = archiveStore.exportAllOrderedByTime()
                if (candles.isEmpty()) {
                    statusText.setTextColor(mutedColor)
                    statusText.text = "Nothing to export yet — no candles stored locally."
                    return@launch
                }
                val result = csvExporter.export(ARCHIVE_SYMBOL, candles)
                statusText.setTextColor(ACCENT_COLOR)
                statusText.text = "Saved ${result.rowCount} candles to Downloads/${result.displayName}"
                Toast.makeText(context, "Exported to Downloads/${result.displayName}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                statusText.setTextColor(NEGATIVE_COLOR)
                statusText.text = "Export failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    private fun buildStatRow(label: String, initialValue: String): StatRow {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 12.5f
                setTextColor(mutedColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val valueView = TextView(context).apply {
            text = initialValue
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            gravity = Gravity.END
        }
        row.addView(valueView)
        return StatRow(row, valueView)
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(mutedColor)
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun sectionDivider(): View = View(context).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }

    private fun buildActionButton(label: String, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(ACCENT_COLOR)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun formatTimestamp(epochMillis: Long): String = timestampFormat.format(Date(epochMillis))

    private fun formatPrice(value: Double): String = String.format(Locale.US, "%,.2f", value)

    private fun formatVolume(value: Double): String = String.format(Locale.US, "%,.4f", value)

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0m"
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            append("${minutes}m")
        }
    }
}
