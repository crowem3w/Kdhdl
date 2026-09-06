package org.example.syncora.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PositionSide
import org.example.syncora.rrl.RrlPerformanceSummary
import org.example.syncora.rrl.RrlStepResult
import java.util.Locale
import kotlin.math.abs

/**
 * Bottom quick-trade sheet. Has two tabs:
 *  - Manual: a self-contained market/limit order ticket plus open positions / pending orders.
 *  - Agent: a live read-out of the RRL agent's current signal + running performance, with an
 *    optional auto-trade switch that lets the agent open/flip/close positions on its own by
 *    driving the exact same [Callbacks] the manual ticket uses.
 *
 * Visual language is intentionally monochrome (black / white / gray gradients only, no hues) to
 * match a minimalist, dark-mode aesthetic.
 */
class QuickTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class OrderType { MARKET, LIMIT }

    enum class MarginMode { CROSS, ISOLATED }

    enum class FuturesUnit(val label: String, val unitSuffix: String) {
        QUANTITY("Quantity", "BTC"),
        COST("Cost", "USDT"),
        VALUE("Value", "USDT"),
    }

    private enum class Tab { MANUAL, AGENT }

    class Callbacks(
        val onOpenPosition: (
            side: PositionSide,
            sizeUsdt: String,
            leverage: Int,
            orderType: OrderType,
            limitPrice: String?,
            takeProfitPrice: String?,
            stopLossPrice: String?,
        ) -> Unit,
        val onClosePosition: (PaperPosition) -> Unit = {},
        val onCancelPendingOrder: (PendingLimitOrder) -> Unit = {},
    )

    // ---- Monochrome dark-mode palette (gradients only, no hues) ----------------------------
    private val black = Color.parseColor("#000000")
    private val nearBlack = Color.parseColor("#0A0A0A")
    private val surfaceTop = Color.parseColor("#1C1C1E")
    private val surfaceBottom = Color.parseColor("#101012")
    private val fieldTop = Color.parseColor("#141416")
    private val fieldBottom = Color.parseColor("#0A0A0B")
    private val borderColor = Color.parseColor("#2A2A2C")
    private val borderStrong = Color.parseColor("#3D3D40")
    private val white = Color.parseColor("#FFFFFF")
    private val offWhite = Color.parseColor("#D8D8DA")
    private val textPrimary = Color.parseColor("#F5F5F5")
    private val textSecondary = Color.parseColor("#9A9A9E")
    private val textTertiary = Color.parseColor("#5C5C60")

    var onHandleDrag: ((phase: ScrollRevealContainer.DragPhase, deltaY: Float) -> Unit)? = null

    private var handleDownY = 0f

    private lateinit var scrollView: ScrollView

    val scrollableContent: View
        get() = scrollView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- State ------------------------------------------------------------------------------
    private var callbacks: Callbacks? = null
    private var currentTab: Tab = Tab.MANUAL
    private var currentSide: PositionSide = PositionSide.LONG
    private var currentOrderType: OrderType = OrderType.MARKET
    private var latestBalance: PaperAccountBalance? = null
    private var latestPositions: List<PaperPosition> = emptyList()
    private var latestPendingOrders: List<PendingLimitOrder> = emptyList()
    private var latestSignal: RrlStepResult? = null
    private var latestPerformance: RrlPerformanceSummary = RrlPerformanceSummary()
    private var autoTradeEnabled = false
    private var lastAutoTradedSign = Int.MIN_VALUE

    // ---- Shared header views ------------------------------------------------------------------
    private lateinit var manualTabButton: TextView
    private lateinit var agentTabButton: TextView
    private lateinit var manualTabContent: View
    private lateinit var agentTabContent: View

    // ---- Manual tab views -----------------------------------------------------------------
    private lateinit var markPriceText: TextView
    private lateinit var spreadText: TextView
    private lateinit var balanceText: TextView
    private lateinit var longSideButton: TextView
    private lateinit var shortSideButton: TextView
    private lateinit var marketTypeButton: TextView
    private lateinit var limitTypeButton: TextView
    private lateinit var limitPriceRow: View
    private lateinit var limitPriceInput: EditText
    private lateinit var sizeInput: EditText
    private lateinit var leverageInput: EditText
    private lateinit var tpInput: EditText
    private lateinit var slInput: EditText
    private lateinit var submitButton: TextView
    private lateinit var positionsEmptyText: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var pendingOrdersHeader: TextView
    private lateinit var pendingOrdersContainer: LinearLayout

    // ---- Agent tab views --------------------------------------------------------------------
    private lateinit var stanceLabel: TextView
    private lateinit var stanceSubtitle: TextView
    private lateinit var confidenceFillHolder: View
    private lateinit var confidenceSpacer: View
    private lateinit var statSteps: TextView
    private lateinit var statReward: TextView
    private lateinit var statInfoRatio: TextView
    private lateinit var statPriceReturn: TextView
    private lateinit var statExecCost: TextView
    private lateinit var statFundingCarry: TextView
    private lateinit var autoTradeSwitch: Switch
    private lateinit var autoTradeStatusText: TextView
    private lateinit var agentSizeInput: EditText
    private lateinit var agentLeverageInput: EditText
    private lateinit var applyNowButton: TextView

    init {
        orientation = VERTICAL
        background = gradientBg(nearBlack, black, 0)
        addView(buildGrabHandle())
        addView(buildTabRow())

        scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(24))
        }
        manualTabContent = buildManualTab()
        agentTabContent = buildAgentTab()
        content.addView(manualTabContent)
        content.addView(agentTabContent)
        scrollView.addView(content)
        addView(scrollView)

        applySideStyle()
        applyOrderTypeStyle()
        showTab(Tab.MANUAL)
        renderAgentSignalUi()
        renderAgentPerformanceUi()
        renderPositionsUi()
        renderPendingOrdersUi()
    }

    // ================================================================================
    // Public API
    // ================================================================================

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun render(balance: PaperAccountBalance?) {
        latestBalance = balance
        balanceText.text = if (balance != null) {
            String.format(Locale.US, "Equity  %,.2f USDT", balance.equity)
        } else {
            "Equity  \u2014"
        }
    }

    fun renderMarkPrice(price: Double?) {
        markPriceText.text = if (price != null) formatPrice(price) else "\u2014"
    }

    fun renderOrderBook(bids: List<DepthLevel>, asks: List<DepthLevel>) {
        val bestBid = bids.maxByOrNull { it.price }?.price
        val bestAsk = asks.minByOrNull { it.price }?.price
        spreadText.text = if (bestBid != null && bestAsk != null && bestBid > 0.0) {
            String.format(Locale.US, "Spread %.3f%%", ((bestAsk - bestBid) / bestBid) * 100.0)
        } else {
            ""
        }
    }

    fun renderOpenPositions(positions: List<PaperPosition>) {
        latestPositions = positions
        renderPositionsUi()
    }

    fun renderPendingOrders(orders: List<PendingLimitOrder>) {
        latestPendingOrders = orders
        renderPendingOrdersUi()
    }

    /** Latest per-bar output from the RRL agent (application layer feeds this from RrlDataPipeline.signal). */
    fun renderAgentSignal(signal: RrlStepResult?) {
        latestSignal = signal
        renderAgentSignalUi()
        if (autoTradeEnabled) maybeAutoExecute(signal)
    }

    /** Running performance summary for the RRL agent (fed from RrlDataPipeline.performance). */
    fun renderAgentPerformance(performance: RrlPerformanceSummary) {
        latestPerformance = performance
        renderAgentPerformanceUi()
    }

    // ================================================================================
    // Header: grab handle + Manual/Agent tabs
    // ================================================================================

    private fun buildGrabHandle(): View =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
            isClickable = true
            isFocusable = true
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(borderStrong)
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(36), dp(4)).apply {
                        gravity = Gravity.CENTER
                    }
                },
            )
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleDownY = event.rawY
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.START, 0f)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.MOVE, event.rawY - handleDownY)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.END, event.rawY - handleDownY)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.CANCEL, event.rawY - handleDownY)
                        true
                    }
                    else -> false
                }
            }
        }

    private fun buildTabRow(): View {
        val wrapper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        val toggle = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = gradientBg(fieldTop, fieldBottom, 12, borderColor)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        manualTabButton = segmentButton("Manual").apply {
            setOnClickListener { showTab(Tab.MANUAL) }
        }
        agentTabButton = segmentButton("Agent").apply {
            setOnClickListener { showTab(Tab.AGENT) }
        }
        toggle.addView(manualTabButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toggle.addView(agentTabButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrapper.addView(toggle)
        return wrapper
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        manualTabContent.visibility = if (tab == Tab.MANUAL) View.VISIBLE else View.GONE
        agentTabContent.visibility = if (tab == Tab.AGENT) View.VISIBLE else View.GONE
        styleSegment(manualTabButton, tab == Tab.MANUAL)
        styleSegment(agentTabButton, tab == Tab.AGENT)
    }

    // ================================================================================
    // Manual tab
    // ================================================================================

    private fun buildManualTab(): View {
        val root = LinearLayout(context).apply { orientation = VERTICAL }

        // Mark price + equity summary
        val priceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
        }
        val priceColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        markPriceText = TextView(context).apply {
            text = "\u2014"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        spreadText = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(textTertiary)
        }
        priceColumn.addView(markPriceText)
        priceColumn.addView(spreadText)
        balanceText = TextView(context).apply {
            text = "Equity  \u2014"
            textSize = 12f
            setTextColor(textSecondary)
        }
        priceRow.addView(priceColumn)
        priceRow.addView(balanceText)
        root.addView(priceRow)

        // Side toggle: Long / Short
        val sideToggle = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = gradientBg(fieldTop, fieldBottom, 12, borderColor)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        longSideButton = segmentButton("Long").apply { setOnClickListener { setSide(PositionSide.LONG) } }
        shortSideButton = segmentButton("Short").apply { setOnClickListener { setSide(PositionSide.SHORT) } }
        sideToggle.addView(longSideButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sideToggle.addView(shortSideButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(sideToggle)
        root.addView(spacer(10))

        // Order type toggle: Market / Limit
        val typeToggle = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = gradientBg(fieldTop, fieldBottom, 10, borderColor)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        marketTypeButton = segmentButton("Market", 12f).apply { setOnClickListener { setOrderType(OrderType.MARKET) } }
        limitTypeButton = segmentButton("Limit", 12f).apply { setOnClickListener { setOrderType(OrderType.LIMIT) } }
        typeToggle.addView(marketTypeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        typeToggle.addView(limitTypeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(typeToggle)

        // Limit price (only visible in Limit mode)
        limitPriceInput = fieldEditText("Limit price (USDT)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        limitPriceRow = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(spacer(10))
            addView(limitPriceInput)
        }
        root.addView(limitPriceRow)

        root.addView(spacer(10))

        // Size + leverage
        val sizeRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        sizeInput = fieldEditText("Size (USDT)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        leverageInput = fieldEditText("Leverage", InputType.TYPE_CLASS_NUMBER).apply {
            setText("5")
            layoutParams = LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        sizeRow.addView(sizeInput)
        sizeRow.addView(leverageInput)
        root.addView(sizeRow)
        root.addView(spacer(8))
        root.addView(buildLeveragePresetRow())
        root.addView(spacer(10))

        // TP / SL
        val tpSlRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        tpInput = fieldEditText("Take profit (opt.)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        slInput = fieldEditText("Stop loss (opt.)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tpSlRow.addView(tpInput)
        tpSlRow.addView(slInput)
        root.addView(tpSlRow)
        root.addView(spacer(14))

        submitButton = TextView(context).apply {
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { handleSubmit() }
        }
        root.addView(submitButton)
        root.addView(spacer(18))

        root.addView(sectionLabel("Open Positions"))
        positionsEmptyText = TextView(context).apply {
            text = "No open positions"
            textSize = 12f
            setTextColor(textTertiary)
            setPadding(0, dp(6), 0, dp(4))
        }
        positionsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        root.addView(positionsEmptyText)
        root.addView(positionsContainer)
        root.addView(spacer(14))

        pendingOrdersHeader = sectionLabel("Pending Orders")
        pendingOrdersContainer = LinearLayout(context).apply { orientation = VERTICAL }
        root.addView(pendingOrdersHeader)
        root.addView(pendingOrdersContainer)

        return root
    }

    private fun buildLeveragePresetRow(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        listOf(2, 5, 10, 20, 50).forEachIndexed { index, value ->
            val chip = TextView(context).apply {
                text = "${value}x"
                textSize = 11.5f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
                background = gradientBg(fieldTop, fieldBottom, 8, borderColor)
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { leverageInput.setText(value.toString()) }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index != 0) params.marginStart = dp(6)
            row.addView(chip, params)
        }
        return row
    }

    private fun setSide(side: PositionSide) {
        currentSide = side
        applySideStyle()
    }

    private fun applySideStyle() {
        val isLong = currentSide == PositionSide.LONG
        styleSegment(longSideButton, isLong)
        styleSegment(shortSideButton, !isLong)
        submitButton.text = if (isLong) "Open Long" else "Open Short"
        submitButton.background = if (isLong) {
            gradientBg(white, offWhite, 12)
        } else {
            gradientBg(surfaceTop, black, 12, borderStrong)
        }
        submitButton.setTextColor(if (isLong) black else white)
    }

    private fun setOrderType(orderType: OrderType) {
        currentOrderType = orderType
        applyOrderTypeStyle()
    }

    private fun applyOrderTypeStyle() {
        val isMarket = currentOrderType == OrderType.MARKET
        styleSegment(marketTypeButton, isMarket, 12f)
        styleSegment(limitTypeButton, !isMarket, 12f)
        limitPriceRow.visibility = if (isMarket) View.GONE else View.VISIBLE
    }

    private fun handleSubmit() {
        val cb = callbacks ?: return
        val sizeText = sizeInput.text?.toString()?.trim().orEmpty()
        if (sizeText.isEmpty() || (sizeText.toDoubleOrNull() ?: 0.0) <= 0.0) {
            sizeInput.error = "Enter an amount"
            return
        }
        if (currentOrderType == OrderType.LIMIT) {
            val limitPrice = limitPriceInput.text?.toString()?.trim()
            if (limitPrice.isNullOrEmpty()) {
                limitPriceInput.error = "Enter a limit price"
                return
            }
        }
        val leverage = leverageInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 125) ?: 1
        val limitPrice = if (currentOrderType == OrderType.LIMIT) limitPriceInput.text?.toString()?.trim() else null
        val takeProfit = tpInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val stopLoss = slInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        cb.onOpenPosition(currentSide, sizeText, leverage, currentOrderType, limitPrice, takeProfit, stopLoss)
    }

    private fun renderPositionsUi() {
        positionsContainer.removeAllViews()
        positionsEmptyText.visibility = if (latestPositions.isEmpty()) View.VISIBLE else View.GONE
        for (position in latestPositions) {
            positionsContainer.addView(buildPositionRow(position))
        }
    }

    private fun buildPositionRow(position: PaperPosition): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradientBg(surfaceTop, surfaceBottom, 10, borderColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            text = "${position.side.name} \u00B7 ${position.leverage}x"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        textColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%.4f @ %,.2f", position.total, position.entryPrice)
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        })
        val pnlColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        pnlColumn.addView(TextView(context).apply {
            val pnl = position.unrealizedPnl
            text = String.format(Locale.US, "%s%,.2f", if (pnl >= 0) "+" else "", pnl)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (pnl >= 0) textPrimary else textSecondary)
        })
        pnlColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%.2f%%", position.pnlPercentOfMargin)
            textSize = 11f
            setTextColor(textTertiary)
            setPadding(0, dp(2), 0, 0)
        })
        val closeButton = TextView(context).apply {
            text = "Close"
            textSize = 11.5f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = gradientBg(fieldTop, fieldBottom, 8, borderStrong)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
            setOnClickListener { callbacks?.onClosePosition?.invoke(position) }
        }
        row.addView(textColumn)
        row.addView(pnlColumn)
        row.addView(closeButton)
        return row
    }

    private fun renderPendingOrdersUi() {
        pendingOrdersContainer.removeAllViews()
        pendingOrdersHeader.visibility = if (latestPendingOrders.isEmpty()) View.GONE else View.VISIBLE
        for (order in latestPendingOrders) {
            pendingOrdersContainer.addView(buildPendingOrderRow(order))
        }
    }

    private fun buildPendingOrderRow(order: PendingLimitOrder): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradientBg(surfaceTop, surfaceBottom, 10, borderColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            text = "${order.side.name} \u00B7 ${order.leverage}x limit"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        textColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%.4f @ %,.2f", order.sizeInBaseCoin, order.limitPrice)
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        })
        val cancelButton = TextView(context).apply {
            text = "Cancel"
            textSize = 11.5f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = gradientBg(fieldTop, fieldBottom, 8, borderStrong)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks?.onCancelPendingOrder?.invoke(order) }
        }
        row.addView(textColumn)
        row.addView(cancelButton)
        return row
    }

    // ================================================================================
    // Agent tab
    // ================================================================================

    private fun buildAgentTab(): View {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }

        // Stance card
        val stanceCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = gradientBg(surfaceTop, surfaceBottom, 14, borderColor)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val stanceHeaderRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stanceHeaderRow.addView(TextView(context).apply {
            text = "AGENT SIGNAL"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(textTertiary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        stanceCard.addView(stanceHeaderRow)
        stanceCard.addView(spacer(6))
        stanceLabel = TextView(context).apply {
            text = "WARMING UP"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textSecondary)
        }
        stanceCard.addView(stanceLabel)
        stanceSubtitle = TextView(context).apply {
            text = "Collecting market data before the agent starts deciding"
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        }
        stanceCard.addView(stanceSubtitle)

        val confidenceTrack = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 1f
            background = gradientBg(fieldTop, fieldBottom, 4, borderColor)
        }
        confidenceFillHolder = View(context).apply {
            background = gradientBg(white, offWhite, 4)
            layoutParams = LinearLayout.LayoutParams(0, dp(6), 0f)
        }
        confidenceSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f)
        }
        confidenceTrack.addView(confidenceFillHolder)
        confidenceTrack.addView(confidenceSpacer)
        stanceCard.addView(confidenceTrack)
        stanceCard.addView(TextView(context).apply {
            text = "Position confidence"
            textSize = 10.5f
            setTextColor(textTertiary)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(stanceCard)
        root.addView(spacer(14))

        // Performance grid
        root.addView(sectionLabel("Performance"))
        val statsCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = gradientBg(surfaceTop, surfaceBottom, 14, borderColor)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        statSteps = TextView(context)
        statReward = TextView(context)
        statInfoRatio = TextView(context)
        statPriceReturn = TextView(context)
        statExecCost = TextView(context)
        statFundingCarry = TextView(context)
        statsCard.addView(buildStatRow("Steps", statSteps))
        statsCard.addView(statDivider())
        statsCard.addView(buildStatRow("Cumulative reward", statReward))
        statsCard.addView(statDivider())
        statsCard.addView(buildStatRow("Information ratio", statInfoRatio))
        statsCard.addView(statDivider())
        statsCard.addView(buildStatRow("Price return", statPriceReturn))
        statsCard.addView(statDivider())
        statsCard.addView(buildStatRow("Execution cost", statExecCost))
        statsCard.addView(statDivider())
        statsCard.addView(buildStatRow("Funding carry", statFundingCarry))
        root.addView(statsCard)
        root.addView(spacer(16))

        // Auto-trade controls
        root.addView(sectionLabel("Auto-Trade"))
        val autoCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = gradientBg(surfaceTop, surfaceBottom, 14, borderColor)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val switchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        switchRow.addView(TextView(context).apply {
            text = "Let the agent trade for you"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        autoTradeSwitch = Switch(context).apply {
            showText = false
            trackTintList = switchTrackColors()
            thumbTintList = switchThumbColors()
            setOnCheckedChangeListener { _, isChecked -> onAutoTradeToggled(isChecked) }
        }
        switchRow.addView(autoTradeSwitch)
        autoCard.addView(switchRow)
        autoTradeStatusText = TextView(context).apply {
            text = "Off \u2014 signal is for reference only"
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(6), 0, dp(12))
        }
        autoCard.addView(autoTradeStatusText)

        val sizingRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        agentSizeInput = fieldEditText("Size (USDT)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            setText("50")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        agentLeverageInput = fieldEditText("Leverage", InputType.TYPE_CLASS_NUMBER).apply {
            setText("5")
            layoutParams = LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        sizingRow.addView(agentSizeInput)
        sizingRow.addView(agentLeverageInput)
        autoCard.addView(sizingRow)
        autoCard.addView(spacer(12))

        applyNowButton = TextView(context).apply {
            text = "Apply Signal Now"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(black)
            background = gradientBg(white, offWhite, 10)
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val signal = latestSignal ?: return@setOnClickListener
                val sign = stanceSign(signal.position)
                lastAutoTradedSign = sign
                executeSign(sign)
            }
        }
        autoCard.addView(applyNowButton)
        autoCard.addView(TextView(context).apply {
            text = "The agent opens, flips, or closes a single position based on its live " +
                "long/flat/short output. It never manages take-profit or stop-loss orders."
            textSize = 10.5f
            setTextColor(textTertiary)
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(autoCard)

        return root
    }

    private fun buildStatRow(label: String, valueView: TextView): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 12.5f
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        valueView.apply {
            text = "\u2014"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        row.addView(valueView)
        return row
    }

    private fun statDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun onAutoTradeToggled(isChecked: Boolean) {
        autoTradeEnabled = isChecked
        autoTradeStatusText.text = if (isChecked) {
            "On \u2014 the agent will open, flip, and close positions automatically"
        } else {
            "Off \u2014 signal is for reference only"
        }
        autoTradeStatusText.setTextColor(if (isChecked) textPrimary else textSecondary)
        if (isChecked) {
            // Force a re-sync between the currently held position and the current signal.
            lastAutoTradedSign = Int.MIN_VALUE
            maybeAutoExecute(latestSignal)
        }
    }

    private fun renderAgentSignalUi() {
        val signal = latestSignal
        if (signal == null) {
            stanceLabel.text = "WARMING UP"
            stanceLabel.setTextColor(textSecondary)
            stanceSubtitle.text = "Collecting market data before the agent starts deciding"
            setConfidence(0.0)
            applyNowButton.isEnabled = false
            applyNowButton.alpha = 0.4f
            return
        }
        applyNowButton.isEnabled = true
        applyNowButton.alpha = 1f
        val sign = stanceSign(signal.position)
        stanceLabel.text = when (sign) {
            1 -> "LONG"
            -1 -> "SHORT"
            else -> "FLAT"
        }
        stanceLabel.setTextColor(if (sign == 0) textSecondary else white)
        stanceSubtitle.text = String.format(
            Locale.US,
            "position %.2f \u00B7 expected return %.4f%%",
            signal.position,
            signal.expectedReturn * 100.0,
        )
        setConfidence(abs(signal.position))
    }

    private fun setConfidence(rawMagnitude: Double) {
        val magnitude = rawMagnitude.toFloat().coerceIn(0f, 1f)
        (confidenceFillHolder.layoutParams as LinearLayout.LayoutParams).weight = magnitude
        confidenceFillHolder.layoutParams = confidenceFillHolder.layoutParams
        (confidenceSpacer.layoutParams as LinearLayout.LayoutParams).weight = 1f - magnitude
        confidenceSpacer.layoutParams = confidenceSpacer.layoutParams
    }

    private fun renderAgentPerformanceUi() {
        val perf = latestPerformance
        statSteps.text = perf.steps.toString()
        statReward.text = String.format(Locale.US, "%.5f", perf.cumulativeReward)
        statInfoRatio.text = String.format(Locale.US, "%.3f", perf.informationRatio)
        statPriceReturn.text = String.format(Locale.US, "%.4f%%", perf.cumulativePriceReturn * 100.0)
        statExecCost.text = String.format(Locale.US, "%.4f%%", perf.cumulativeExecutionCost * 100.0)
        statFundingCarry.text = String.format(Locale.US, "%.4f%%", perf.cumulativeFundingCarry * 100.0)
    }

    private fun stanceSign(position: Double): Int = when {
        position > FLAT_THRESHOLD -> 1
        position < -FLAT_THRESHOLD -> -1
        else -> 0
    }

    private fun maybeAutoExecute(signal: RrlStepResult?) {
        val result = signal ?: return
        val sign = stanceSign(result.position)
        if (sign == lastAutoTradedSign) return
        lastAutoTradedSign = sign
        executeSign(sign)
    }

    /** Drives the exact same callbacks the manual ticket uses, so paper/live accounting stays unified. */
    private fun executeSign(sign: Int) {
        val cb = callbacks ?: return
        val openPosition = latestPositions.firstOrNull()
        val desiredSide = when (sign) {
            1 -> PositionSide.LONG
            -1 -> PositionSide.SHORT
            else -> null
        }
        if (desiredSide == null) {
            if (openPosition != null) cb.onClosePosition(openPosition)
            return
        }
        if (openPosition != null && openPosition.side != desiredSide) {
            cb.onClosePosition(openPosition)
        }
        if (openPosition == null || openPosition.side != desiredSide) {
            val sizeText = agentSizeInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AGENT_SIZE
            val leverage = agentLeverageInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 125) ?: DEFAULT_AGENT_LEVERAGE
            cb.onOpenPosition(desiredSide, sizeText, leverage, OrderType.MARKET, null, null, null)
        }
    }

    private fun switchTrackColors(): ColorStateList {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
        val colors = intArrayOf(Color.parseColor("#5A5A5C"), Color.parseColor("#232325"))
        return ColorStateList(states, colors)
    }

    private fun switchThumbColors(): ColorStateList {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
        val colors = intArrayOf(white, Color.parseColor("#8A8A8E"))
        return ColorStateList(states, colors)
    }

    // ================================================================================
    // Shared building blocks
    // ================================================================================

    private fun segmentButton(text: String, size: Float = 13f): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setPadding(0, dp(9), 0, dp(9))
        isClickable = true
        isFocusable = true
    }

    private fun styleSegment(button: TextView, selected: Boolean, size: Float = 13f) {
        button.background = if (selected) gradientBg(white, offWhite, 9) else null
        button.setTextColor(if (selected) black else textSecondary)
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.textSize = size
    }

    private fun fieldEditText(hint: String, inputType: Int): EditText = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType
        textSize = 13f
        setTextColor(textPrimary)
        setHintTextColor(textTertiary)
        gravity = Gravity.CENTER
        background = gradientBg(fieldTop, fieldBottom, 8, borderColor)
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.06f
        setTextColor(textTertiary)
        setPadding(dp(2), 0, 0, dp(6))
    }

    private fun gradientBg(
        topColor: Int,
        bottomColor: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(topColor, bottomColor),
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun formatPrice(price: Double): String {
        val decimals = when {
            abs(price) >= 1000 -> 1
            abs(price) >= 1 -> 2
            else -> 5
        }
        return String.format(Locale.US, "%,.${decimals}f", price)
    }

    private companion object {
        const val FLAT_THRESHOLD = 0.15
        const val DEFAULT_AGENT_SIZE = "50"
        const val DEFAULT_AGENT_LEVERAGE = 5
    }
}
