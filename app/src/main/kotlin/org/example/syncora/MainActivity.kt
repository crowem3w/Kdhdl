package org.example.syncora

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.example.syncora.bitget.ClosedPaperTrade
import org.example.syncora.bitget.FeeRates
import org.example.syncora.bitget.Kline
import org.example.syncora.bitget.LatencyConfig
import org.example.syncora.bitget.PaperAccount
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PaperTradingResult
import org.example.syncora.bitget.PlacedOrder
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PipelineState
import org.example.syncora.bitget.SocketState
import org.example.syncora.bitget.Timeframe
import org.example.syncora.chart.CandlestickChartView
import org.example.syncora.chart.ChartLayoutMetrics
import org.example.syncora.chart.DepthHeatmapView
import org.example.syncora.chart.DrawingTool
import org.example.syncora.onboarding.OnboardingActivity
import org.example.syncora.onboarding.OnboardingPreferences
import org.example.syncora.perf.PerformanceMonitor
import org.example.syncora.ui.AgentStatusLogPanel
import org.example.syncora.ui.DrawingContextToolbar
import org.example.syncora.ui.DrawingToolsPanel
import org.example.syncora.ui.HistoricalDataDialog
import org.example.syncora.ui.LiveTradePanel
import org.example.syncora.ui.NeumorphicInsetFrameDrawable
import org.example.syncora.ui.NeumorphicPillDrawable
import org.example.syncora.ui.PaperTradePanel
import org.example.syncora.ui.PaperTradingAccountPanel
import org.example.syncora.ui.PaperTradingHistoryPanel
import org.example.syncora.ui.PerformanceHudView
import org.example.syncora.ui.QuickTradePanel
import org.example.syncora.ui.RoundedIconButton
import org.example.syncora.ui.ScrollRevealContainer
import org.example.syncora.service.MarketDataForegroundService
import org.example.syncora.ui.SkeletonLoadingView
import org.example.syncora.ui.TradingModeDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // Held at application scope so the pipeline survives activity recreation. The
    // pipeline/live-poll/stop-loss-guard lifecycle itself is owned by
    // MarketDataForegroundService now, not by this activity - see that class's kdoc
    // and SyncoraApplication.ensureMarketDataStarted().
    private val app by lazy { application as SyncoraApplication }
    private val pipeline by lazy { app.pipeline }
    private val depthPipeline by lazy { app.depthPipeline }
    private val paperTradingRepository by lazy { app.paperTradingRepository }
    private val liveCredentialsStore by lazy { app.liveCredentialsStore }
    private val liveTradingRepository by lazy { app.liveTradingRepository }

    // Android 13+ requires this permission for the foreground service's persistent
    // status notification to actually be visible - the service still runs and
    // still protects positions without it, the notification just won't show. Must
    // be registered before the activity reaches STARTED, hence the property here
    // rather than an inline call from onCreate().
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private lateinit var candleChart: CandlestickChartView
    private lateinit var depthHeatmap: DepthHeatmapView
    private lateinit var performanceHud: PerformanceHudView
    private val performanceMonitor by lazy {
        PerformanceMonitor(this) { snapshot -> performanceHud.render(snapshot) }
    }
    private lateinit var chartSectionContainer: ScrollRevealContainer
    private lateinit var chartAndQuickTradeContainer: LinearLayout
    private lateinit var chartCanvas: FrameLayout
    private lateinit var quickTradeHandleIndicator: View
    private lateinit var quickTradePanel: QuickTradePanel
    private lateinit var bottomControlsRow: LinearLayout
    private lateinit var timeframeRow: LinearLayout
    private lateinit var symbolText: TextView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var liveDot: View
    private lateinit var liveText: TextView
    private lateinit var symbolSkeleton: SkeletonLoadingView
    private lateinit var priceSkeleton: SkeletonLoadingView
    private lateinit var changeSkeleton: SkeletonLoadingView
    private lateinit var drawingToolsButton: ImageView
    private lateinit var timeframeExpandButton: ImageView
    private lateinit var drawingContextToolbar: DrawingContextToolbar
    private val paperTradePanel by lazy { PaperTradePanel(this) }
    private val paperTradingAccountPanel by lazy { PaperTradingAccountPanel(this) }
    private val paperTradingHistoryPanel by lazy { PaperTradingHistoryPanel(this) }
    private val liveTradePanel by lazy { LiveTradePanel(this) }
    private val agentStatusLogPanel by lazy { AgentStatusLogPanel(this) }
    private lateinit var paragraphButton: RoundedIconButton
    private lateinit var connectivityBanner: LinearLayout
    private lateinit var connectivityBannerText: TextView
    private lateinit var connectivityBannerRetry: TextView
    private lateinit var connectivityBannerDismiss: TextView
    private val timeframeButtons = mutableMapOf<Timeframe, Button>()

    private val drawingToolsPanel by lazy { DrawingToolsPanel(this) }
    private var activeDrawingTool: DrawingTool = DrawingTool.NONE

    private var latestPipelineState = PipelineState.IDLE
    private var latestSocketState = SocketState.IDLE
    private var connectivityBannerDismissed = false

    // Quick-trade drawer: revealed by an upward drag, hidden by a downward drag, made
    // anywhere ScrollRevealContainer reports as eligible (i.e. outside the chart's plot
    // area - so this covers the price axis, time axis, timeframe row, and toolbar icons,
    // while leaving the chart's own pan/zoom gestures untouched). The drawer follows the
    // finger live as it drags (quickTradeProgress), then settles fully open or fully
    // closed on release.
    private var isQuickTradeExpanded = false
    private var quickTradeProgress = 0f // 0 = fully collapsed, 1 = fully expanded
    private var quickTradeDragBaseProgress = 0f
    private var quickTradeSettleAnimator: ValueAnimator? = null
    private val quickTradeMaxDragPx by lazy { dp(220) }
    private val quickTradeExpandedChartWeight = 0.5f
    private val quickTradeExpandedPanelWeight = 0.5f
    private val quickTradeCollapsedChartWeight = 1f
    private val quickTradeCollapsedPanelWeight = 0f

    // The timeframe row / double-chevron / drawing-tools strip below the chart. It has no
    // weight of its own (wrap_content, fixed at the bottom), so as it collapses toward the
    // drawer drag's progress, the space it gives up is automatically reclaimed by
    // chartAndQuickTradeContainer's weight-1 sibling above it - which is exactly what lets
    // the chart and the fully-expanded drawer grow into that freed space. Captured lazily the
    // first time it's needed, since it's wrap_content and not known until after first layout.
    private var bottomControlsRowHeight = 0

    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")
    private val mutedColor = Color.parseColor("#8A96A3")
    private val inactivePillTextColor = Color.parseColor("#8A96A3")
    private val activePillBgColor = Color.parseColor("#102A2B")
    private val timeframeContainerColor = Color.parseColor("#0A1015")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First-ever open: hand off to the one-time onboarding screen instead of
        // inflating the main chart UI. OnboardingActivity marks itself complete
        // before returning here, so this only ever fires once per install.
        if (!OnboardingPreferences(this).hasCompletedOnboarding) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContentView(R.layout.activity_main)

        candleChart = findViewById(R.id.candleChart)
        depthHeatmap = findViewById(R.id.depthHeatmap)
        performanceHud = findViewById(R.id.performanceHud)
        candleChart.touchListener = { performanceMonitor.notifyTouchEvent() }
        candleChart.drawDurationListener = { durationNanos -> performanceMonitor.notifyDrawCompleted(durationNanos) }
        chartSectionContainer = findViewById(R.id.chartSectionContainer)
        chartAndQuickTradeContainer = findViewById(R.id.chartAndQuickTradeContainer)
        chartCanvas = findViewById(R.id.chartCanvas)
        quickTradeHandleIndicator = findViewById(R.id.quickTradeHandleIndicator)
        quickTradePanel = findViewById(R.id.quickTradePanel)
        bottomControlsRow = findViewById(R.id.bottomControlsRow)
        timeframeRow = findViewById(R.id.timeframeRow)
        symbolText = findViewById(R.id.symbolText)
        priceText = findViewById(R.id.priceText)
        changeText = findViewById(R.id.changeText)
        liveDot = findViewById(R.id.liveDot)
        liveText = findViewById(R.id.liveText)
        symbolSkeleton = findViewById(R.id.symbolSkeleton)
        priceSkeleton = findViewById(R.id.priceSkeleton)
        changeSkeleton = findViewById(R.id.changeSkeleton)
        drawingToolsButton = findViewById(R.id.drawingToolsButton)
        timeframeExpandButton = findViewById(R.id.timeframeExpandButton)
        timeframeExpandButton.setOnClickListener {
            HistoricalDataDialog(this).show()
        }
        drawingContextToolbar = findViewById(R.id.drawingContextToolbar)
        paragraphButton = findViewById(R.id.paragraphButton)
        paragraphButton.setOnClickListener {
            TradingModeDialog(
                context = this,
                paperAccountContent = paperTradingAccountPanel,
                paperHistoryContent = paperTradingHistoryPanel,
                liveTradingContent = liveTradePanel,
                agentContent = agentStatusLogPanel,
                onExportReport = { exportPaperTradingReport() },
            ).show()
        }
        connectivityBanner = findViewById(R.id.connectivityBanner)
        connectivityBannerText = findViewById(R.id.connectivityBannerText)
        connectivityBannerRetry = findViewById(R.id.connectivityBannerRetry)
        connectivityBannerDismiss = findViewById(R.id.connectivityBannerDismiss)
        connectivityBannerRetry.setOnClickListener {
            connectivityBannerDismissed = false
            hideConnectivityBanner()
            app.stopMarketData()
            app.ensureMarketDataStarted()
        }
        connectivityBannerDismiss.setOnClickListener {
            connectivityBannerDismissed = true
            hideConnectivityBanner()
        }
        setupPaperTrading()
        setupLiveTrading()
        setupQuickTradePanel()
        setupQuickTradeScrollGesture()

        drawingContextToolbar.bind(
            candleChart,
            DrawingContextToolbar.Callbacks(
                onColorChange = { color -> candleChart.setSelectedLineColor(color) },
                onOpacityChange = { percent -> candleChart.setSelectedLineOpacity(percent) },
                onWidthChange = { widthDp -> candleChart.setSelectedLineWidth(widthDp) },
                onPatternChange = { pattern -> candleChart.setSelectedLinePattern(pattern) },
                onDelete = { candleChart.deleteSelectedDrawing() },
            ),
        )
        candleChart.onSelectedDrawingChanged = { style ->
            if (style != null) {
                drawingContextToolbar.showForStyle(style)
            } else {
                drawingContextToolbar.hide()
            }
        }

        updateDrawingToolsButtonState()
        drawingToolsButton.setOnClickListener {
            drawingToolsPanel.show(drawingToolsButton, activeDrawingTool) { tool ->
                activeDrawingTool = tool
                candleChart.setActiveDrawingTool(tool)
                updateDrawingToolsButtonState()
            }
        }

        drawingToolsButton.setOnLongClickListener {
            candleChart.clearDrawings()
            true
        }
        candleChart.onDrawingPlaced = {
            activeDrawingTool = DrawingTool.NONE
            updateDrawingToolsButtonState()
        }

        updateTimeframeExpandButtonState()

        buildTimeframeButtons()
        renderConnectionState()

        candleChart.onViewportChange = { range -> depthHeatmap.setInteractiveOverride(range) }

        candleChart.onTimeWindowChange = { visible -> depthHeatmap.syncToCandles(visible, pipeline.barDurationMillis.value) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    pipeline.pipelineState.collect { state ->
                        latestPipelineState = state
                        renderConnectionState()
                    }
                }
                launch {
                    pipeline.socketState.collect { state ->
                        latestSocketState = state
                        renderConnectionState()
                    }
                }
                launch {
                    pipeline.currentTimeframe.collect { timeframe ->
                        highlightSelectedTimeframe(timeframe)

                    }
                }

                launch { pipeline.barDurationMillis.collect { candleChart.setBarDurationMillis(it) } }
                launch {
                    pipeline.klines.collect { candles ->
                        candleChart.submitCandles(candles)
                        val visible = candleChart.visibleCandles()
                        renderHeader(candles, visible)

                        depthHeatmap.syncToCandles(visible, pipeline.barDurationMillis.value)
                        quickTradePanel.renderMarkPrice(candles.lastOrNull()?.close)
                    }
                }

                launch {
                    depthPipeline.renderTicks.collect { tick ->
                        val delta = tick.delta
                        if (delta != null) {
                            depthHeatmap.submitDepthDelta(delta, tick.snapshot)
                        } else {
                            depthHeatmap.submitDepth(tick.snapshot)
                        }
                        // Quick-trade drawer's order book only ever shows the
                        // top 9 bid / top 9 ask price levels; it trims the
                        // full snapshot down itself.
                        quickTradePanel.renderOrderBook(tick.snapshot.bids, tick.snapshot.asks)
                    }
                }

                launch {
                    depthPipeline.liquidityZones.collect { zones ->
                        depthHeatmap.submitLiquidityZones(zones)
                    }
                }

                launch {
                    depthPipeline.liquidityShelves.collect { shelves ->
                        depthHeatmap.submitLiquidityShelves(shelves)
                    }
                }

                launch { watchConnectivity() }

                // Prompt 7f's status/log panel: pushed, not polled - see
                // AgentStatusLogPanel's own class doc. Scoped to STARTED
                // like every other collector here, so it stops updating
                // (and its Job is cancelled) while the activity isn't
                // visible, then resumes cleanly on the next onStart();
                // the underlying agent session itself keeps running at
                // application scope regardless (see SyncoraApplication).
                launch {
                    agentStatusLogPanel.collectFrom(app.agentSessionController.decisionLog, this)
                }
            }
        }
    }

    private fun setupPaperTrading() {
        paperTradingAccountPanel.bind(
            PaperTradingAccountPanel.Callbacks(
                onCreateAccount = { startingBalance ->
                    val result = paperTradingRepository.createAccount(startingBalance)
                    if (result is PaperTradingResult.Failure) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Paper trading account created", Toast.LENGTH_SHORT).show()
                    }
                },
                onResetAccount = {
                    paperTradingRepository.resetAccount()
                    Toast.makeText(this, "Paper trading account reset", Toast.LENGTH_SHORT).show()
                },
            ),
        )

        paperTradePanel.bind(
            PaperTradePanel.Callbacks(
                onCreateAccount = { startingBalance ->
                    val result = paperTradingRepository.createAccount(startingBalance)
                    if (result is PaperTradingResult.Failure) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Paper trading account created", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeposit = { amount ->
                    val result = paperTradingRepository.deposit(amount)
                    if (result is PaperTradingResult.Failure) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Deposited ${amount.toInt()} USDT", Toast.LENGTH_SHORT).show()
                    }
                },
                onResetAccount = {
                    paperTradingRepository.resetAccount()
                    Toast.makeText(this, "Paper trading account reset", Toast.LENGTH_SHORT).show()
                },
                onOpenPosition = { side, size, leverage ->
                    lifecycleScope.launch {
                        val result = paperTradingRepository.openPosition(side, size, leverage)
                        when (result) {
                            is PaperTradingResult.Failure ->
                                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                            is PaperTradingResult.Success -> {
                                val sideLabel = side.name.lowercase().replaceFirstChar { it.uppercase() }
                                Toast.makeText(this@MainActivity, "$sideLabel order filled${fillSummarySuffix(result.data)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onClosePosition = { position ->
                    lifecycleScope.launch {
                        val result = paperTradingRepository.closePosition(position)
                        when (result) {
                            is PaperTradingResult.Failure ->
                                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                            is PaperTradingResult.Success ->
                                Toast.makeText(this@MainActivity, "Position closed${fillSummarySuffix(result.data)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCancelPendingOrder = { order ->
                    val result = paperTradingRepository.cancelLimitOrder(order.id)
                    if (result is PaperTradingResult.Failure) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Limit order canceled", Toast.LENGTH_SHORT).show()
                    }
                },
                onSetLatencyConfig = { config -> paperTradingRepository.setLatencyConfig(config) },
            ),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        paperTradingRepository.account,
                        paperTradingRepository.balance,
                        paperTradingRepository.positions,
                        paperTradingRepository.pendingOrders,
                        paperTradingRepository.lastError,
                    ) { account, balance, positions, pendingOrders, error ->
                        PaperTradeRenderState(account, balance, positions, pendingOrders, error)
                    }.combine(paperTradingRepository.closedTrades) { renderState, closedTrades ->
                        renderState to closedTrades
                    }.combine(paperTradingRepository.feeRates) { (renderState, closedTrades), feeRates ->
                        Triple(renderState, closedTrades, feeRates)
                    }.combine(paperTradingRepository.latencyConfig) { (renderState, closedTrades, feeRates), latencyConfig ->
                        PaperTradeRenderBundle(renderState, closedTrades, feeRates, latencyConfig)
                    }.collect { (renderState, closedTrades, feeRates, latencyConfig) ->
                        paperTradePanel.render(
                            account = renderState.account,
                            balance = renderState.balance,
                            positions = renderState.positions,
                            pendingOrders = renderState.pendingOrders,
                            lastError = renderState.error,
                            nextDepositAvailableAt = paperTradingRepository.nextDepositAvailableAt(),
                            feeRates = feeRates,
                            latencyConfig = latencyConfig,
                        )
                        paperTradingAccountPanel.render(
                            account = renderState.account,
                            balance = renderState.balance,
                            closedTrades = closedTrades,
                        )
                        paperTradingHistoryPanel.render(closedTrades)
                        quickTradePanel.render(renderState.balance)
                        quickTradePanel.renderOpenPositions(renderState.positions)
                        quickTradePanel.renderPendingOrders(renderState.pendingOrders)
                    }
                }
            }
        }
    }

    private data class PaperTradeRenderState(
        val account: PaperAccount?,
        val balance: PaperAccountBalance?,
        val positions: List<PaperPosition>,
        val pendingOrders: List<PendingLimitOrder>,
        val error: String?,
    )

    private data class PaperTradeRenderBundle(
        val renderState: PaperTradeRenderState,
        val closedTrades: List<ClosedPaperTrade>,
        val feeRates: FeeRates,
        val latencyConfig: LatencyConfig,
    )

    /**
     * Short " (137ms latency, 0.042% slippage)"-style suffix for a fill
     * toast, built from whatever [PlacedOrder] a simulated order actually
     * came back with - see [org.example.syncora.bitget.LatencySimulator] and
     * [org.example.syncora.bitget.OrderBookWalker]. Empty when neither applies
     * (e.g. latency simulation is off and the fill was a flat mark-price
     * fallback), so it never leaves a dangling empty "()" in the toast.
     */
    private fun fillSummarySuffix(order: PlacedOrder): String {
        val parts = mutableListOf<String>()
        if (order.appliedLatencyMs > 0L) {
            parts.add("${order.appliedLatencyMs}ms latency")
        }
        order.fillInfo?.let { info ->
            parts.add(String.format(Locale.US, "%.3f%% slippage", info.slippagePercent))
        }
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    /**
     * Builds a plain-text performance summary of the local paper trading
     * account and hands it to the system share sheet, so the person can
     * save it to Drive/Files, email it, or otherwise "export" it - there's
     * no exchange or server to download a report from, so a share sheet is
     * the closest on-device equivalent.
     */
    private fun exportPaperTradingReport() {
        val account = paperTradingRepository.account.value
        if (account == null) {
            Toast.makeText(this, "Create a paper trading account first", Toast.LENGTH_SHORT).show()
            return
        }
        val balance = paperTradingRepository.balance.value
        val closedTrades = paperTradingRepository.closedTrades.value
        val cutoffMillis = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val recentTrades = closedTrades.filter { it.closedAt >= cutoffMillis }
        val wins = recentTrades.count { it.realizedPnl > 0 }
        val ratioText = if (recentTrades.isEmpty()) {
            "n/a (no closed trades in the last 30 days)"
        } else {
            String.format(Locale.US, "%.1f%% (%d of %d trades)", (wins.toDouble() / recentTrades.size) * 100.0, wins, recentTrades.size)
        }
        val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
        val totalRealizedPnl = closedTrades.sumOf { it.realizedPnl }

        val report = buildString {
            appendLine("Paper Trading Performance Report")
            appendLine("Generated ${dateFormat.format(Date())}")
            appendLine()
            appendLine("Account #${account.id}")
            appendLine("Opened ${dateFormat.format(Date(account.createdAt))}")
            appendLine()
            appendLine("Virtual balance: ${String.format(Locale.US, "%,.2f USDT", balance?.equity ?: 0.0)}")
            appendLine("Unrealized P&L: ${String.format(Locale.US, "%,.2f USDT", balance?.unrealizedPnl ?: 0.0)}")
            appendLine("Total realized P&L (all-time): ${String.format(Locale.US, "%,.2f USDT", totalRealizedPnl)}")
            appendLine("Profitable ratio (last 30 days): $ratioText")
            appendLine("Closed trades (all-time): ${closedTrades.size}")
            if (closedTrades.isNotEmpty()) {
                appendLine()
                appendLine("Recent trades:")
                closedTrades.take(20).forEach { trade ->
                    appendLine(
                        "  ${dateFormat.format(Date(trade.closedAt))}  ${trade.side.name} ${trade.leverage}x  " +
                            String.format(Locale.US, "%.4f @ %,.2f -> %,.2f  ", trade.size, trade.entryPrice, trade.exitPrice) +
                            String.format(Locale.US, "%s%,.2f USDT", if (trade.realizedPnl >= 0) "+" else "", trade.realizedPnl),
                    )
                }
            }
            appendLine()
            appendLine("This is a local, simulated paper trading account. No real funds are involved.")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Paper Trading Report - ${account.id}")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(shareIntent, "Export Paper Trading Report"))
    }

    private fun setupLiveTrading() {
        liveTradePanel.bind(
            LiveTradePanel.Callbacks(
                onCredentialsSubmitted = { credentials ->
                    liveCredentialsStore.save(credentials)
                    liveTradingRepository.onCredentialsChanged()
                    Toast.makeText(this, "Live API Key saved", Toast.LENGTH_SHORT).show()
                },
                onCredentialsCleared = {
                    liveCredentialsStore.clear()
                    liveTradingRepository.onCredentialsChanged()
                },
            ),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        liveTradingRepository.connectionState,
                        liveTradingRepository.lastError,
                        liveTradingRepository.userId,
                    ) { state, error, userId ->
                        Triple(state, error, userId)
                    }.collect { (state, error, userId) ->
                        liveTradePanel.render(
                            connectionState = state,
                            credentials = liveCredentialsStore.load(),
                            lastError = error,
                            userId = userId,
                        )
                    }
                }
            }
        }
    }

    /**
     * Wires the quick-trade drawer's Long/Short buttons to paper trading -
     * the same account the chart's own Long/Short quick-action buttons use.
     * MARKET fills immediately via [PaperTradingRepository.openPosition];
     * LIMIT places a resting order via [PaperTradingRepository.placeLimitOrder]
     * that fills on its own once a later mark-price tick reaches the limit
     * price (see that repository for the fill logic).
     */
    private fun setupQuickTradePanel() {
        quickTradePanel.bind(
            QuickTradePanel.Callbacks(
                onOpenPosition = { side, sizeUsdt, leverage, orderType, limitPrice, takeProfitPrice, stopLossPrice ->
                    val sideLabel = side.name.lowercase().replaceFirstChar { it.uppercase() }
                    if (orderType == QuickTradePanel.OrderType.LIMIT && limitPrice.isNullOrBlank()) {
                        Toast.makeText(this, "Enter a limit price", Toast.LENGTH_LONG).show()
                        return@Callbacks
                    }
                    lifecycleScope.launch {
                        val result = if (orderType == QuickTradePanel.OrderType.LIMIT) {
                            paperTradingRepository.placeLimitOrderByNotional(side, sizeUsdt, leverage, limitPrice!!)
                        } else {
                            paperTradingRepository.openPositionByNotional(side, sizeUsdt, leverage)
                        }
                        when (result) {
                            is PaperTradingResult.Failure ->
                                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                            is PaperTradingResult.Success -> {
                                val message = if (orderType == QuickTradePanel.OrderType.LIMIT) {
                                    "$sideLabel limit order placed - will fill when price is reached"
                                } else {
                                    "$sideLabel order placed${fillSummarySuffix(result.data)}"
                                }
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                // TP/SL aren't enforced by the paper-trading engine yet - only
                                // captured here so the UI doesn't silently drop what the user typed.
                                if (!takeProfitPrice.isNullOrBlank() || !stopLossPrice.isNullOrBlank()) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Note: TP/SL isn't auto-executed in paper trading yet - watch the position manually",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    }
                },
                onClosePosition = { position ->
                    lifecycleScope.launch {
                        val result = paperTradingRepository.closePosition(position)
                        when (result) {
                            is PaperTradingResult.Failure ->
                                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                            is PaperTradingResult.Success ->
                                Toast.makeText(this@MainActivity, "Position closed${fillSummarySuffix(result.data)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCancelPendingOrder = { order ->
                    val result = paperTradingRepository.cancelLimitOrder(order.id)
                    if (result is PaperTradingResult.Failure) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Order canceled", Toast.LENGTH_SHORT).show()
                    }
                },
            ),
        )
    }

    /**
     * Wires [ScrollRevealContainer]'s drag reporting to the quick-trade drawer.
     * The container reports drags made anywhere except the chart's plot area
     * (see [ScrollRevealContainer] for how that's determined), which lands us
     * the price axis, time axis, timeframe row, and toolbar icons in addition
     * to the header/banner - covering everywhere "outside the chart canvas"
     * without touching the chart's own pan/zoom handling. The drawer's own
     * grab handle additionally reports drags directly via
     * [QuickTradePanel.onHandleDrag], independent of that container-wide
     * detection, so dragging the handle itself is never at the mercy of the
     * broader screen-wide gesture heuristics.
     *
     * Direction: dragging the finger *up* (negative deltaY) reveals the
     * drawer; dragging *down* (positive deltaY) hides it. The drawer tracks
     * the finger 1:1 while dragging (an on-screen, live expand rather than a
     * snap after a hidden threshold) and settles fully open or fully closed
     * once the finger lifts, based on which side of the midpoint it landed.
     */
    private fun setupQuickTradeScrollGesture() {
        chartSectionContainer.excludedInteractiveView = candleChart
        chartSectionContainer.excludedRightInsetPx = ChartLayoutMetrics.priceAxisWidthPx(resources)
        chartSectionContainer.excludedBottomInsetPx = ChartLayoutMetrics.timeAxisHeightPx(resources)
        // Leaves the drawer's grab handle draggable for the reveal gesture while
        // letting drags that start on its body (balance, leverage, size, order
        // type, Long/Short) scroll the drawer instead of resizing it, once the
        // body actually has overflow content to scroll.
        chartSectionContainer.excludedScrollableView = quickTradePanel.scrollableContent
        chartSectionContainer.onVerticalDrag = ::handleQuickTradeDrag
        quickTradePanel.onHandleDrag = ::handleQuickTradeDrag
        setupQuickTradeHandleIndicator()
    }

    /**
     * Wires the always-visible, chart-docked handle (see [quickTradeHandleIndicator] and its
     * doc comment in activity_main.xml) to the same drag handler as the drawer's own internal
     * grab handle, using raw screen coordinates for the same reason [QuickTradePanel]'s handle
     * does - the indicator's surroundings can relayout mid-gesture as the drawer expands.
     */
    private fun setupQuickTradeHandleIndicator() {
        var indicatorDownY = 0f
        quickTradeHandleIndicator.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    indicatorDownY = event.rawY
                    quickTradeHandleIndicator.parent?.requestDisallowInterceptTouchEvent(true)
                    handleQuickTradeDrag(ScrollRevealContainer.DragPhase.START, 0f)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleQuickTradeDrag(ScrollRevealContainer.DragPhase.MOVE, event.rawY - indicatorDownY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handleQuickTradeDrag(ScrollRevealContainer.DragPhase.END, event.rawY - indicatorDownY)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handleQuickTradeDrag(ScrollRevealContainer.DragPhase.CANCEL, event.rawY - indicatorDownY)
                    true
                }
                else -> false
            }
        }
    }

    /** Shared handler for both the screen-wide reveal gesture and the drawer's own grab-handle drag. */
    private fun handleQuickTradeDrag(phase: ScrollRevealContainer.DragPhase, deltaY: Float) {
        when (phase) {
            ScrollRevealContainer.DragPhase.START -> {
                quickTradeSettleAnimator?.cancel()
                quickTradeDragBaseProgress = quickTradeProgress
            }
            ScrollRevealContainer.DragPhase.MOVE -> {
                val progress = (quickTradeDragBaseProgress - deltaY / quickTradeMaxDragPx).coerceIn(0f, 1f)
                applyQuickTradeProgress(progress)
            }
            ScrollRevealContainer.DragPhase.END, ScrollRevealContainer.DragPhase.CANCEL -> {
                settleQuickTrade()
            }
        }
    }

    /** Applies a 0..1 reveal progress directly to the chart/drawer weights - the live, finger-following part of the gesture. */
    private fun applyQuickTradeProgress(progress: Float) {
        quickTradeProgress = progress
        val chartParams = chartCanvas.layoutParams as LinearLayout.LayoutParams
        val panelParams = quickTradePanel.layoutParams as LinearLayout.LayoutParams
        chartParams.weight = quickTradeCollapsedChartWeight + (quickTradeExpandedChartWeight - quickTradeCollapsedChartWeight) * progress
        panelParams.weight = quickTradeCollapsedPanelWeight + (quickTradeExpandedPanelWeight - quickTradeCollapsedPanelWeight) * progress
        chartCanvas.layoutParams = chartParams
        quickTradePanel.layoutParams = panelParams
        quickTradePanel.visibility = if (progress > 0f) View.VISIBLE else View.GONE
        // Fades out quickly as the drawer starts opening so it hands off to the drawer's own
        // internal grab handle rather than the two ever being visible at once.
        quickTradeHandleIndicator.alpha = (1f - progress * 4f).coerceIn(0f, 1f)
        quickTradeHandleIndicator.visibility = if (progress >= 0.25f) View.GONE else View.VISIBLE
        applyBottomControlsProgress(progress)
        chartAndQuickTradeContainer.requestLayout()
    }

    /**
     * Collapses the timeframe row / double-chevron / drawing-tools strip in lockstep with the
     * drawer's drag progress: it slides toward the bottom edge and fades as it shrinks, rather
     * than just clipping in place, and its reserved height shrinks along with it so that space
     * is handed back to the chart/drawer above (see [bottomControlsRowHeight]). At progress 1
     * it's collapsed to zero height and set GONE, matching the drawer landing fully expanded;
     * at progress 0 it's restored to its full height, position, and opacity.
     */
    private fun applyBottomControlsProgress(progress: Float) {
        if (bottomControlsRowHeight <= 0) {
            val measured = bottomControlsRow.height
            if (measured > 0) bottomControlsRowHeight = measured
        }
        val fullHeight = bottomControlsRowHeight
        if (fullHeight <= 0) return

        val params = bottomControlsRow.layoutParams
        params.height = (fullHeight * (1f - progress)).toInt().coerceIn(0, fullHeight)
        bottomControlsRow.layoutParams = params
        bottomControlsRow.translationY = fullHeight * progress * 0.5f
        bottomControlsRow.alpha = (1f - progress).coerceIn(0f, 1f)
        bottomControlsRow.visibility = if (progress >= 1f) View.GONE else View.VISIBLE
    }

    /** Called on finger-up: snaps to fully expanded (0.5/0.5 weights) or fully collapsed, whichever the drag ended closer to. */
    private fun settleQuickTrade() {
        val target = if (quickTradeProgress >= 0.5f) 1f else 0f
        isQuickTradeExpanded = target == 1f
        val start = quickTradeProgress
        quickTradeSettleAnimator?.cancel()
        quickTradeSettleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyQuickTradeProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun buildTimeframeButtons() {
        for (timeframe in Timeframe.entries) {
            val isSelected = timeframe == pipeline.currentTimeframe.value
            val button = Button(this).apply {
                text = timeframe.label
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isAllCaps = false
                gravity = Gravity.CENTER
                setTextColor(pillTextColor(isSelected))
                NeumorphicPillDrawable.applyTo(this, NeumorphicPillDrawable(resources.displayMetrics.density, selected = isSelected))
                setOnClickListener { pipeline.switchTimeframe(timeframe) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            timeframeRow.addView(button, params)
            timeframeButtons[timeframe] = button
        }
    }

    private fun highlightSelectedTimeframe(selected: Timeframe) {
        for ((timeframe, button) in timeframeButtons) {
            val isSelected = timeframe == selected
            NeumorphicPillDrawable.applyTo(button, NeumorphicPillDrawable(resources.displayMetrics.density, selected = isSelected))
            button.setTextColor(pillTextColor(isSelected))
        }
    }

    private fun pillTextColor(selected: Boolean): Int =
        if (selected) bullColor else inactivePillTextColor

    private fun updateDrawingToolsButtonState() {
        val isActive = activeDrawingTool != DrawingTool.NONE
        NeumorphicInsetFrameDrawable.applyTo(
            drawingToolsButton,
            NeumorphicInsetFrameDrawable(resources.displayMetrics.density, selected = isActive),
        )
        drawingToolsButton.setColorFilter(pillTextColor(isActive))
    }

    private fun updateTimeframeExpandButtonState() {
        NeumorphicInsetFrameDrawable.applyTo(
            timeframeExpandButton,
            NeumorphicInsetFrameDrawable(resources.displayMetrics.density, selected = false),
        )
        timeframeExpandButton.clearColorFilter()
    }

    private companion object {
        const val CONNECTIVITY_TIMEOUT_MS = 15_000L
    }

    /**
     * Watches both market-data sockets and, if neither manages to connect within
     * [CONNECTIVITY_TIMEOUT_MS], surfaces a banner distinguishing "still can't reach
     * Bitget" from the ordinary brief "Connecting…" state shown by [renderConnectionState].
     * This matters in regions where ISPs block Bitget's domains (e.g. under Philippines
     * NTC directives) - in that case the socket will just keep retrying forever and the
     * user would otherwise see nothing but a spinner with no explanation.
     *
     * Uses collectLatest so each new state cancels any pending delay from the previous
     * one - the timer only fires if a state has been sustained for the full timeout.
     */
    private suspend fun watchConnectivity() {
        combine(pipeline.socketState, depthPipeline.socketState) { kline, depth -> kline to depth }
            .collectLatest { (klineState, depthState) ->
                val anyConnected = klineState == SocketState.CONNECTED || depthState == SocketState.CONNECTED
                if (anyConnected) {
                    connectivityBannerDismissed = false
                    hideConnectivityBanner()
                    return@collectLatest
                }
                delay(CONNECTIVITY_TIMEOUT_MS)
                if (!connectivityBannerDismissed) {
                    showConnectivityBanner(klineMissing = klineState != SocketState.CONNECTED)
                }
            }
    }

    private fun showConnectivityBanner(klineMissing: Boolean) {
        connectivityBannerText.text = getString(
            if (klineMissing) R.string.connectivity_banner_market_data else R.string.connectivity_banner_order_book,
        )
        connectivityBanner.visibility = View.VISIBLE
    }

    private fun hideConnectivityBanner() {
        connectivityBanner.visibility = View.GONE
    }

    private fun renderConnectionState() {
        val isLive = latestPipelineState == PipelineState.LIVE && latestSocketState == SocketState.CONNECTED
        val isLoading = !isLive

        liveDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isLive) bullColor else mutedColor)
        }
        liveText.text = if (isLive) getString(R.string.live_label) else getString(R.string.connecting_label)
        liveText.setTextColor(if (isLive) bullColor else mutedColor)

        renderHeaderSkeletons(isLoading)
        dimChart(isLoading)
    }

    private fun renderHeaderSkeletons(show: Boolean) {
        val textVisibility = if (show) View.INVISIBLE else View.VISIBLE
        symbolText.visibility = textVisibility
        priceText.visibility = textVisibility
        changeText.visibility = textVisibility

        if (show) {
            symbolSkeleton.show()
            priceSkeleton.show()
            changeSkeleton.show()
        } else {
            symbolSkeleton.hide()
            priceSkeleton.hide()
            changeSkeleton.hide()
        }
    }

    private fun dimChart(dimmed: Boolean) {
        candleChart.alpha = 1f
        candleChart.setSkeletonLoading(dimmed)
        depthHeatmap.alpha = if (dimmed) 0.42f else 1f
    }

    private fun renderHeader(liveCandles: List<Kline>, visibleWindow: List<Kline>) {
        val last = liveCandles.lastOrNull() ?: return
        val first = visibleWindow.firstOrNull() ?: last
        val changePct = if (first.open != 0.0) (last.close - first.open) / first.open * 100.0 else 0.0
        val isUp = changePct >= 0.0
        val color = if (isUp) bullColor else bearColor

        priceText.text = formatPrice(last.close)
        priceText.setTextColor(color)
        changeText.text = String.format(Locale.US, "%s%.2f%%", if (isUp) "+" else "", changePct)
        changeText.setTextColor(color)
    }

    private fun formatPrice(price: Double): String {
        val decimals = when {
            abs(price) >= 1000 -> 1
            abs(price) >= 1 -> 2
            else -> 5
        }
        return String.format(Locale.US, "%,.${decimals}f", price)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        // Idempotent: MarketDataForegroundService.start() is a no-op if the service
        // is already running. Market data, live-position polling, and the
        // stop-loss guard are now owned by that service (see its kdoc), not by
        // this activity - they keep running after onStop() instead of dying the
        // moment the app is backgrounded.
        MarketDataForegroundService.start(this)
        paperTradingRepository.start()
        performanceMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        performanceMonitor.stop()
        // Deliberately NOT stopping MarketDataForegroundService here - that's the
        // whole point of moving this to a foreground service. Only paper trading
        // (a pure on-device simulation with no real position to protect) and the
        // performance HUD are UI-only concerns that should stop with the activity.
        paperTradingRepository.stop()
    }
}
