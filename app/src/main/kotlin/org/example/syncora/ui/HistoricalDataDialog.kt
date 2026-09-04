package org.example.syncora.ui

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.syncora.R
import org.example.syncora.bitget.BackfillProgress
import org.example.syncora.bitget.BackfillState
import org.example.syncora.bitget.DeepHistoryBackfillJob
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.TradingChartPipeline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full-screen modal launched from the bottom-bar "data" button.
 *
 * Mirrors [TradingModeDialog]'s glass-card shell (same blurred backdrop,
 * translucent bordered card, header row with back/close controls) but opens
 * straight onto a vertical list of data categories - Order Book, OHLCV, Open
 * Interest, Funding Rates - each row showing a small leading icon plus a
 * title/description text block. Each row drills into its own bare screen;
 * the content for those screens is intentionally left empty for now.
 */
class HistoricalDataDialog(
    context: Context,
    private val pipeline: TradingChartPipeline? = null,
    private val backfillJob: DeepHistoryBackfillJob? = null,
) : Dialog(context, R.style.TradingModalTheme) {

    private enum class Screen { OPTIONS, ORDER_BOOK, OHLCV, OPEN_INTEREST, FUNDING_RATES }

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
            "OHLCV Data",
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

    // Same teal used by the header's live/connecting dot (see MainActivity,
    // R.drawable.bg_live_dot) so the OHLCV info sheet's "updates live"
    // indicator reads as the same signal elsewhere in the app.
    private val accentColor = Color.parseColor("#22D3C5")
    private val subCardColor = Color.parseColor("#14FFFFFF")
    private val subCardStroke = Color.parseColor("#1FFFFFFF")

    private val thinFont by lazy { ResourcesCompat.getFont(context, R.font.inter_thin) }

    private lateinit var rootView: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: TextView
    private lateinit var contentContainer: FrameLayout
    private val optionsScreen by lazy { buildOptionsScreen() }
    private var currentScreen: Screen = Screen.OPTIONS

    // Lives only as long as the dialog is on screen: cancelled in the
    // dismiss listener below so the OHLCV screen's real-time collector
    // (and any in-flight CSV export) never outlives the modal.
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var liveStatsJob: Job? = null
    private var backfillProgressJob: Job? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        applyWindowBlur()
        showScreen(Screen.OPTIONS)
        setOnDismissListener { dialogScope.cancel() }
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
            setOnClickListener { showScreen(Screen.OPTIONS) }
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

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        liveStatsJob?.cancel()
        liveStatsJob = null
        backfillProgressJob?.cancel()
        backfillProgressJob = null
        contentContainer.removeAllViews()
        if (screen == Screen.OPTIONS) {
            titleText.text = "Historical Data"
            backButton.visibility = View.GONE
            contentContainer.addView(optionsScreen)
        } else {
            val category = categories.first { it.screen == screen }
            titleText.text = category.title
            backButton.visibility = View.VISIBLE
            val content = if (screen == Screen.OHLCV) buildOhlcvInfoScreen() else buildEmptyScreen()
            contentContainer.addView(scrollableCopy(content))
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
            list.addView(buildTile(category))
            if (index != categories.lastIndex) {
                list.addView(View(context).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(4); bottomMargin = dp(4)
                    }
                })
            }
        }
        return list
    }

    private fun buildTile(category: Category): View {
        val rippleValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(rippleValue.resourceId)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { showScreen(category.screen) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(ImageView(context).apply {
            setImageResource(category.iconRes)
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
            text = category.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        })

        textContainer.addView(TextView(context).apply {
            text = category.description
            textSize = 11.5f
            typeface = thinFont ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)

        return row
    }

    // ---- Screen 2: OHLCV info sheet - live stats on the locally stored 1m candles + CSV export ----

    private val csvTimestampFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'_'HHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
    private val statTimestampFormat by lazy {
        SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }

    private fun buildOhlcvInfoScreen(): View {
        val pipeline = this.pipeline
        if (pipeline == null) return buildEmptyScreen()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        container.addView(buildTimeframePill())
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        })

        val symbolValue = TextView(context)
        val countValue = TextView(context)
        val latestValue = TextView(context)
        val deepestValue = TextView(context)
        val spanValue = TextView(context)

        val statsCard = buildStatsCard(
            listOf(
                "Symbol" to symbolValue,
                "Candles stored locally" to countValue,
                "Latest candle (most recent)" to latestValue,
                "Deepest candle (oldest)" to deepestValue,
                "History span covered" to spanValue,
            ),
        )
        container.addView(statsCard)

        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        })

        val exportStatusText = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), 0)
        }

        val exportButton = buildExportButton {
            exportOhlcvCsv(pipeline.klines.value, exportStatusText)
        }
        container.addView(exportButton)
        container.addView(exportStatusText)

        if (backfillJob != null) {
            container.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
            })
            container.addView(buildDeepArchiveSection(backfillJob))
        }

        // Renders once immediately with whatever's in the buffer right now,
        // then keeps refreshing for as long as this screen stays visible -
        // klines is the same in-memory snapshot that gets periodically
        // flushed to the on-device ObjectBox cache, so this reflects the
        // locally stored data in real time as new 1m candles close.
        liveStatsJob = pipeline.klines
            .onEach { candles ->
                renderOhlcvStats(
                    candles = candles,
                    symbolValue = symbolValue,
                    countValue = countValue,
                    latestValue = latestValue,
                    deepestValue = deepestValue,
                    spanValue = spanValue,
                )
            }
            .launchIn(dialogScope)

        return container
    }

    /**
     * Deep archive section of the OHLCV screen: a "Download full history"
     * trigger, a progress row bound to [DeepHistoryBackfillJob.progress],
     * and a second CSV export path that reads from [KlineArchiveStore]
     * (via the job's own cacheKey) once a backfill has stored anything -
     * separate from [exportOhlcvCsv] above, which only ever sees the live
     * buffer's few-thousand-candle window.
     */
    private fun buildDeepArchiveSection(job: DeepHistoryBackfillJob): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        section.addView(TextView(context).apply {
            text = "Deep Archive"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedColor)
            setPadding(dp(2), 0, 0, dp(6))
        })

        val statusText = TextView(context).apply {
            textSize = 11.5f
            setTextColor(mutedColor)
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        section.addView(statusText)

        val actionButton = buildExportButton { }
        val actionLabel = (actionButton as LinearLayout).getChildAt(0) as TextView
        section.addView(actionButton)

        val archiveExportStatusText = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), 0)
        }

        fun renderForState(p: BackfillProgress) {
            val downloadedLabel = "%,d candles".format(Locale.US, p.candlesDownloaded)
            val oldestLabel = p.oldestTimestampSoFar?.let { statTimestampFormat.format(Date(it)) }

            when (p.state) {
                BackfillState.IDLE -> {
                    statusText.text = if (p.candlesDownloaded > 0) {
                        "$downloadedLabel stored so far - oldest reached: ${oldestLabel ?: "\u2014"}"
                    } else {
                        "Not started. This downloads the instrument's full available 1m " +
                            "history (potentially years, ~225 MB) independent of the live chart."
                    }
                    actionLabel.text = if (p.candlesDownloaded > 0) "Resume full history download" else "Download full history"
                    actionButton.isEnabled = true
                    actionButton.alpha = 1f
                }
                BackfillState.RUNNING, BackfillState.RATE_LIMITED_RETRYING -> {
                    statusText.text = "$downloadedLabel downloaded - oldest reached: ${oldestLabel ?: "\u2014"}" +
                        if (p.state == BackfillState.RATE_LIMITED_RETRYING) " (retrying...)" else ""
                    actionLabel.text = "Downloading\u2026"
                    actionButton.isEnabled = false
                    actionButton.alpha = 0.6f
                }
                BackfillState.COMPLETE -> {
                    statusText.text = "$downloadedLabel stored - oldest reached: ${oldestLabel ?: "\u2014"}"
                    actionLabel.text = "Continue further back"
                    actionButton.isEnabled = true
                    actionButton.alpha = 1f
                }
                BackfillState.FAILED -> {
                    statusText.text = "Paused (${p.errorMessage ?: "network error"}) - $downloadedLabel stored so far"
                    actionLabel.text = "Resume full history download"
                    actionButton.isEnabled = true
                    actionButton.alpha = 1f
                }
            }
        }

        actionButton.setOnClickListener { job.start() }

        section.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        })
        val archiveExportButton = buildExportButton {
            exportArchiveCsv(job, archiveExportStatusText)
        }
        ((archiveExportButton as LinearLayout).getChildAt(0) as TextView).text = "Export full archive as CSV"
        section.addView(archiveExportButton)
        section.addView(archiveExportStatusText)

        backfillProgressJob = job.progress
            .onEach { p -> renderForState(p) }
            .launchIn(dialogScope)

        return section
    }

    private fun buildTimeframePill(): View {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(subCardColor)
                setStroke(dp(1), accentColor)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        pill.addView(View(context).apply {
            background = buildAccentDotDrawable()
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) }
        })
        pill.addView(TextView(context).apply {
            text = "1m (minute)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        })
        return pill
    }

    private fun buildAccentDotDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(accentColor)
    }

    private fun buildStatsCard(rows: List<Pair<String, TextView>>): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(subCardColor)
                setStroke(dp(1), subCardStroke)
            }
            setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        rows.forEachIndexed { index, (label, valueView) ->
            card.addView(buildStatRow(label, valueView))
            if (index != rows.lastIndex) {
                card.addView(View(context).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                })
            }
        }
        return card
    }

    private fun buildStatRow(label: String, valueView: TextView): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(mutedColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        valueView.apply {
            text = "\u2014"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            gravity = Gravity.END
        }
        row.addView(valueView)
        return row
    }

    private fun buildExportButton(onClick: () -> Unit): View {
        val rippleValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val button = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#2962FF"))
            }
            isClickable = true
            isFocusable = true
            foreground = context.getDrawable(rippleValue.resourceId)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        button.addView(TextView(context).apply {
            text = "Export as CSV"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        return button
    }

    private fun renderOhlcvStats(
        candles: List<Kline>,
        symbolValue: TextView,
        countValue: TextView,
        latestValue: TextView,
        deepestValue: TextView,
        spanValue: TextView,
    ) {
        symbolValue.text = "BTCUSDT (USDT-FUTURES)"
        countValue.text = "%,d candles".format(Locale.US, candles.size)

        val deepest = candles.firstOrNull()
        val latest = candles.lastOrNull()

        latestValue.text = latest?.let { statTimestampFormat.format(Date(it.startTime)) } ?: "\u2014 (waiting for data)"
        deepestValue.text = deepest?.let { statTimestampFormat.format(Date(it.startTime)) } ?: "\u2014 (waiting for data)"

        spanValue.text = if (deepest != null && latest != null && latest.startTime > deepest.startTime) {
            formatDurationSpan(latest.startTime - deepest.startTime)
        } else {
            "\u2014"
        }
    }

    private fun formatDurationSpan(millis: Long): String {
        val totalMinutes = millis / 60_000L
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            append("${minutes}m")
        }
    }

    /**
     * Writes every currently buffered/locally-cached 1m OHLCV candle out as
     * a CSV file in the device's public Downloads folder via MediaStore
     * (no storage permission needed on minSdk 30+), so the data is left
     * sitting in normal on-device file storage rather than just shared
     * through an app-to-app intent.
     */
    private fun exportOhlcvCsv(candles: List<Kline>, statusText: TextView) {
        if (candles.isEmpty()) {
            Toast.makeText(context, "No OHLCV data cached locally yet", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "syncora_ohlcv_1m_BTCUSDT_${csvTimestampFormat.format(Date())}.csv"
        dialogScope.launch {
            val savedPath = withContext(Dispatchers.IO) { writeCsvToDownloads(fileName, candles) }
            if (savedPath != null) {
                statusText.text = "Saved to $savedPath"
                statusText.visibility = View.VISIBLE
                Toast.makeText(context, "Exported ${candles.size} candles to Downloads", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Export failed - couldn't write file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Same on-device CSV export as [exportOhlcvCsv], but reading from
     * [DeepHistoryBackfillJob]'s archive store instead of the live buffer -
     * the "actually-deep dataset" once a backfill has run (blueprint §3.5),
     * rather than whatever few thousand candles the chart currently holds.
     */
    private fun exportArchiveCsv(job: DeepHistoryBackfillJob, statusText: TextView) {
        dialogScope.launch {
            val candles = withContext(Dispatchers.IO) { job.loadArchivedCandles() }
            if (candles.isEmpty()) {
                Toast.makeText(context, "No archived history downloaded yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val fileName = "syncora_ohlcv_1m_BTCUSDT_archive_${csvTimestampFormat.format(Date())}.csv"
            val savedPath = withContext(Dispatchers.IO) { writeCsvToDownloads(fileName, candles) }
            if (savedPath != null) {
                statusText.text = "Saved to $savedPath"
                statusText.visibility = View.VISIBLE
                Toast.makeText(context, "Exported ${candles.size} candles to Downloads", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Export failed - couldn't write file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeCsvToDownloads(fileName: String, candles: List<Kline>): String? {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Syncora"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(buildCsv(candles).toByteArray(Charsets.UTF_8))
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/Syncora/$fileName"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun buildCsv(candles: List<Kline>): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            appendLine("timestamp_ms,datetime_utc,open,high,low,close,base_volume,quote_volume,usdt_volume")
            candles.forEach { k ->
                appendLine(
                    "${k.startTime},${isoFormat.format(Date(k.startTime))}," +
                        "${k.open},${k.high},${k.low},${k.close}," +
                        "${k.baseVolume},${k.quoteVolume},${k.usdtVolume}",
                )
            }
        }
    }

    // ---- Screens 3-5: bare placeholders, content intentionally left empty ----

    private fun buildEmptyScreen(): View = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(160),
        )
    }
}
