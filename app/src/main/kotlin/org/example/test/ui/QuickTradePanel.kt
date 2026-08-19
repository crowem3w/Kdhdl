package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import org.example.test.bitget.DepthLevel
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingRepository
import org.example.test.bitget.PendingLimitOrder
import org.example.test.bitget.PositionSide
import java.util.Locale

/**
 * Full order-ticket-style "quick trade" drawer that lives directly under the
 * chart, modeled on a standard exchange order ticket (margin/leverage bar,
 * order type, cost-based sizing, TP/SL, Long/Short buttons) on the right,
 * plus a live order book on the left showing the top 5 bid and top 5 ask
 * price levels, with an open-positions list underneath it.
 *
 * It holds no trading state of its own beyond the form inputs themselves -
 * [MainActivity] drives visibility (via the scroll gesture) and feeds it
 * balance, mark price, order-book, and open-position updates through
 * [render], [renderMarkPrice], [renderOrderBook], and [renderOpenPositions].
 *
 * All of the "Cost", "Max open", and "Est. liq. price" math below mirrors
 * [PaperTradingRepository]'s own leverage clamp
 * ([PaperTradingRepository.MAX_LEVERAGE]) so the numbers shown here before
 * the user taps Open match exactly what the repository will actually
 * charge/allow.
 *
 * The drawer's own height is a fraction of the chart container (set by
 * [MainActivity]'s drag-to-reveal animation), so its content can end up
 * taller than the space it's given. Everything below the grab handle
 * therefore lives inside [scrollableContent], a [NestedScrollView]. Most of
 * the time its content fits and dragging anywhere on the drawer still
 * resizes it exactly as before; only once the content actually overflows
 * does [MainActivity] hand drags starting on the body to [scrollableContent]
 * instead, so it scrolls in place - the grab handle keeps working as the
 * resize target either way.
 *
 * The grab handle also gets its own direct touch handling via [onHandleDrag]
 * rather than relying solely on [ScrollRevealContainer]'s screen-wide
 * interception to notice a drag starting there - a drag on the handle
 * itself should never depend on ancestor-level gesture detection to work.
 */
class QuickTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class OrderType { MARKET, LIMIT }

    /** Cross margin (shared across all positions) vs isolated margin (capped to this position only). */
    enum class MarginMode { CROSS, ISOLATED }

    /**
     * What the "Cost" box's number is denominated in. Defaults to [COST] -
     * the notional-sizing behavior this panel already had before the unit
     * picker existed - so switching units is purely a display/basis choice
     * layered on top of the same typed-in amount.
     */
    enum class FuturesUnit(val label: String, val unitSuffix: String) {
        QUANTITY("Quantity", "BTC"),
        COST("Cost", "USDT"),
        VALUE("Value", "USDT"),
    }

    private companion object {
        /**
         * Tier-1 BTCUSDT perpetual maintenance margin rate. Real exchanges scale
         * this up at higher notional tiers; this panel only ever sizes small
         * quick-trade positions so the lowest tier is a reasonable estimate.
         */
        const val MAINTENANCE_MARGIN_RATE = 0.004 // 0.40%

        /** Bitget USDT-M futures taker fee rate. */
        const val TAKER_FEE_RATE = 0.0006 // 0.06%
    }

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
        /** Agent tab: Start/Stop toggle flipped by the person. */
        val onToggleAgent: (Boolean) -> Unit = {},
        /** Agent tab: any learning/risk field edited - always the full current config. */
        val onAgentConfigChanged: (AgentConfig) -> Unit = {},
        /** Agent tab: kill switch tapped - implementation should stop the agent AND flatten any open position. */
        val onAgentKillSwitch: () -> Unit = {},
        /** Agent tab: "Reset" tapped - clear learned state / start a fresh episode. */
        val onResetAgent: () -> Unit = {},
    )

    /** Coarse lifecycle state for the RL agent, surfaced as a colored badge next to the Start/Stop toggle. */
    enum class AgentState(val label: String) {
        IDLE("Idle"),
        OBSERVING("Observing"),
        LEARNING("Learning"),
        TRADING("Trading"),
    }

    enum class RewardFunction(val label: String) {
        PNL("PnL"),
        SHARPE("Sharpe-based"),
        RISK_ADJUSTED("Risk-adjusted"),
    }

    enum class UpdateFrequency(val label: String) {
        PER_TICK("Every tick"),
        PER_CANDLE("Per candle close"),
        PER_N_STEPS("Every N steps"),
    }

    /** Full snapshot of the agent's learning + risk configuration, handed back on every edit via [Callbacks.onAgentConfigChanged]. */
    data class AgentConfig(
        val learningRate: Double,
        val explorationRate: Double,
        val rewardFunction: RewardFunction,
        val updateFrequency: UpdateFrequency,
        val learningFrozen: Boolean,
        val maxPositionSizeUsdt: Double,
        val maxLeverage: Int,
        val maxDailyLossUsdt: Double,
        // % of account equity the agent is willing to put at risk on a
        // single fully-confident trade. Actual notional is this, scaled
        // down further by the continuous position target's own magnitude -
        // so sizing answers "is $2 risky?" in terms of % of equity, not a
        // flat dollar amount, which is what actually determines risk.
        val riskPerTradePct: Double = 2.0,
        // CVaR(5%) threshold (see PolicyDecision.cvar5) below which the
        // agent won't commit capital at all - a wide/negative worst-case
        // quantile band means the distributional critic isn't confident
        // enough in the downside to trade on.
        val minConfidenceToTrade: Double = 0.40,
    )

    private val surfaceColor = Color.parseColor("#0A1015")
    private val borderColor = Color.parseColor("#1B2530")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#8A96A3")
    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")
    private val warnColor = Color.parseColor("#F5B84C")
    private val fieldBackground = Color.parseColor("#131A21")
    private val pillBackground = Color.parseColor("#161F27")

    private var callbacks: Callbacks? = null

    // ---- form state ----
    private var currentLeverage = 10
    private var currentOrderType = OrderType.MARKET
    private var currentMarginMode = MarginMode.CROSS
    private var currentFuturesUnit = FuturesUnit.COST
    private var isAgentTabSelected = false
    private var isUpdatingLeverageText = false
    private var tpSlEnabled = true

    private val marginModeOptionLabels = listOf("Cross", "Isolated")
    private val futuresUnitOptionLabels = listOf("Quantity — BTC", "Cost — USDT", "Value — USDT")
    private var optionsPopup: PopupWindow? = null

    // ---- agent tab form state ----
    private var isAgentRunning = false
    private var agentState = AgentState.IDLE
    private var currentRewardFunction = RewardFunction.PNL
    private var currentUpdateFrequency = UpdateFrequency.PER_TICK
    private var learningFrozen = false
    private val rewardFunctionOptionLabels = RewardFunction.values().map { it.label }
    private val updateFrequencyOptionLabels = UpdateFrequency.values().map { it.label }

    /** Rolling window of recent inference latencies (ms), newest last. Bounded so the drawer's stats stay "recent" rather than all-time. */
    private val inferenceLatencySamplesMs = ArrayDeque<Long>()
    private val maxLatencySamples = 200

    // ---- latest data fed in from MainActivity ----
    private var lastAvailableUsdt = 0.0
    private var lastMarkPrice: Double? = null
    private var lastBestBid: Double? = null
    private var lastBestAsk: Double? = null

    // ---- views referenced after building ----
    private lateinit var leverageInput: EditText
    private lateinit var effectiveLeverageCaption: TextView
    private lateinit var marginModePillText: TextView
    private lateinit var manualTabText: TextView
    private lateinit var agentTabText: TextView
    private lateinit var modeContentContainer: FrameLayout
    private lateinit var orderTypeText: TextView
    private lateinit var priceInput: EditText
    private lateinit var costLabelText: TextView
    private lateinit var futuresUnitButton: TextView
    private lateinit var costAmountInput: EditText
    private lateinit var estimateText: TextView
    private lateinit var tpSlCheckbox: CheckBox
    private lateinit var tpRow: View
    private lateinit var slRow: View
    private lateinit var tpInput: EditText
    private lateinit var slInput: EditText
    private lateinit var availableValueText: TextView
    private lateinit var maxOpenLongText: TextView
    private lateinit var estLiqLongText: TextView
    private lateinit var maxOpenShortText: TextView
    private lateinit var estLiqShortText: TextView
    private lateinit var longButton: TextView
    private lateinit var longButtonSubtext: TextView
    private lateinit var shortButton: TextView
    private lateinit var shortButtonSubtext: TextView

    private lateinit var accountBalanceValueText: TextView
    private lateinit var unrealizedPnlValueText: TextView
    private lateinit var lastPriceText: TextView
    private lateinit var lastPriceSubtext: TextView
    private lateinit var askRowsContainer: LinearLayout
    private lateinit var bidRowsContainer: LinearLayout
    private lateinit var buyPressureBar: View
    private lateinit var sellPressureBar: View
    private lateinit var buyPressureText: TextView
    private lateinit var sellPressureText: TextView
    private lateinit var openPositionsContainer: LinearLayout
    private lateinit var openPositionsEmptyText: TextView
    private lateinit var pendingOrdersContainer: LinearLayout
    private lateinit var pendingOrdersEmptyText: TextView

    // ---- agent tab views ----
    // Separate view instances mirroring the ones above - a View can only
    // have one parent, and the Agent tab's left column shows the same
    // open-positions/pending-orders feed as the Manual tab's, just laid
    // out in its own column. Both sets are kept in sync from the same
    // renderOpenPositions/renderPendingOrders calls below.
    private lateinit var agentOpenPositionsContainer: LinearLayout
    private lateinit var agentOpenPositionsEmptyText: TextView
    private lateinit var agentPendingOrdersContainer: LinearLayout
    private lateinit var agentPendingOrdersEmptyText: TextView
    private lateinit var agentStateBadge: TextView
    private lateinit var agentToggleButton: TextView
    private lateinit var agentPolicyVersionText: TextView
    private lateinit var agentLearningRateInput: EditText
    private lateinit var agentExplorationRateInput: EditText
    private lateinit var agentRewardFunctionButton: TextView
    private lateinit var agentUpdateFrequencyButton: TextView
    private lateinit var agentFreezeLearningCheckbox: CheckBox
    private lateinit var agentMaxPositionInput: EditText
    private lateinit var agentMaxLeverageInput: EditText
    private lateinit var agentMaxDailyLossInput: EditText
    private lateinit var agentRiskPerTradeInput: EditText
    private lateinit var agentMinConfidenceInput: EditText
    private lateinit var agentLastLatencyText: TextView
    private lateinit var agentAvgLatencyText: TextView
    private lateinit var agentP95LatencyText: TextView
    private lateinit var agentMaxLatencyText: TextView
    private lateinit var agentSampleCountText: TextView
    private lateinit var agentCumulativeRewardText: TextView
    private lateinit var agentWinRateText: TextView
    private lateinit var agentSharpeText: TextView
    private lateinit var agentTradeCountText: TextView
    private lateinit var agentTerminalToggle: TextView
    private lateinit var agentTerminalContainer: FrameLayout
    private lateinit var agentTerminalView: AgentTerminalView
    private var isAgentTerminalExpanded = false

    private lateinit var scrollView: NestedScrollView

    /**
     * The scrollable body of the drawer (everything except the grab handle).
     * [MainActivity] hands this to [ScrollRevealContainer.excludedScrollableView],
     * which only excludes it from the outer drag-to-reveal gesture while it
     * actually has overflow content to scroll - see the class doc.
     */
    val scrollableContent: View
        get() = scrollView

    /**
     * Fires for a drag starting directly on the grab handle: reports the
     * same [ScrollRevealContainer.DragPhase] sequence as the container-wide
     * gesture so [MainActivity] can drive the exact same expand/collapse
     * logic from either source.
     */
    var onHandleDrag: ((phase: ScrollRevealContainer.DragPhase, deltaY: Float) -> Unit)? = null

    private var handleDownY = 0f

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), borderColor)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))

        addView(buildGrabHandle())
        addView(buildScrollableBody())
        applyOrderTypeStyle()
        applyManualAgentTabStyle()
        refreshDerivedFields()
    }

    private fun buildScrollableBody(): View {
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        body.addView(buildModeBar())
        body.addView(spacer(10))
        body.addView(buildManualAgentTabs())
        body.addView(spacer(12))
        body.addView(buildModeContent())

        scrollView = NestedScrollView(context).apply {
            isFillViewport = false
            clipToPadding = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            addView(
                body,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            // Fills whatever height the panel is given by MainActivity's
            // expand/collapse weight animation; scrolls internally once the
            // controls below no longer fit in that height.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        return scrollView
    }

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /** Balance/equity feed - same entry point as before. */
    fun render(balance: PaperAccountBalance?) {
        lastAvailableUsdt = balance?.available ?: 0.0
        availableValueText.text = if (balance != null) {
            String.format(Locale.US, "%,.2f USDT", balance.available)
        } else {
            "0.00 USDT"
        }

        accountBalanceValueText.text = if (balance != null) {
            String.format(Locale.US, "%,.2f USDT", balance.equity)
        } else {
            "0.00 USDT"
        }

        val unrealizedPnl = balance?.unrealizedPnl ?: 0.0
        unrealizedPnlValueText.text = String.format(Locale.US, "%s%,.2f USDT", if (unrealizedPnl > 0.0) "+" else "", unrealizedPnl)
        unrealizedPnlValueText.setTextColor(
            when {
                unrealizedPnl > 0.0 -> bullColor
                unrealizedPnl < 0.0 -> bearColor
                else -> mutedColor
            },
        )

        refreshDerivedFields()
    }

    /** Live mark price feed (from the kline pipeline's latest close). */
    fun renderMarkPrice(price: Double?) {
        lastMarkPrice = price
        if (price != null) {
            val text = String.format(Locale.US, "%,.1f", price)
            lastPriceText.text = text
            lastPriceSubtext.text = text
        } else {
            lastPriceText.text = "—"
            lastPriceSubtext.text = "—"
        }
        refreshDerivedFields()
    }

    /**
     * Live order-book feed. Only the top 5 bid levels and top 5 ask levels
     * are ever shown - [bids] and [asks] can be the full depth snapshot;
     * this trims them down itself. [bids] must be sorted best (highest)
     * first and [asks] best (lowest) first, which is how
     * [org.example.test.bitget.DepthPipeline] already publishes them.
     */
    fun renderOrderBook(bids: List<DepthLevel>, asks: List<DepthLevel>) {
        val topBids = bids.take(5)
        val topAsks = asks.take(5)
        lastBestBid = topBids.firstOrNull()?.price
        lastBestAsk = topAsks.firstOrNull()?.price

        val maxSize = (topBids.asSequence() + topAsks.asSequence()).maxOfOrNull { it.size } ?: 0.0

        askRowsContainer.removeAllViews()
        // Farthest ask at the top, best (lowest) ask directly above the spread.
        topAsks.asReversed().forEach { level ->
            askRowsContainer.addView(
                depthRow(
                    priceText = String.format(Locale.US, "%,.1f", level.price),
                    qtyText = formatAbbreviated(level.price * level.size),
                    priceColor = bearColor,
                    fillFraction = if (maxSize > 0.0) (level.size / maxSize).toFloat() else 0f,
                    fillColor = translucent(bearColor),
                ),
            )
        }
        // 5 blank placeholder rows if the book hasn't loaded yet, so the
        // layout doesn't jump around while the socket connects.
        repeat((5 - topAsks.size).coerceAtLeast(0)) {
            askRowsContainer.addView(depthRow("--", "--", mutedColor, 0f, Color.TRANSPARENT))
        }

        bidRowsContainer.removeAllViews()
        // Best (highest) bid directly below the spread, descending from there.
        topBids.forEach { level ->
            bidRowsContainer.addView(
                depthRow(
                    priceText = String.format(Locale.US, "%,.1f", level.price),
                    qtyText = formatAbbreviated(level.price * level.size),
                    priceColor = bullColor,
                    fillFraction = if (maxSize > 0.0) (level.size / maxSize).toFloat() else 0f,
                    fillColor = translucent(bullColor),
                ),
            )
        }
        repeat((5 - topBids.size).coerceAtLeast(0)) {
            bidRowsContainer.addView(depthRow("--", "--", mutedColor, 0f, Color.TRANSPARENT))
        }

        val buySize = topBids.sumOf { it.size }
        val sellSize = topAsks.sumOf { it.size }
        val total = buySize + sellSize
        val buyPct = if (total > 0.0) (buySize / total * 100.0) else 50.0
        val sellPct = 100.0 - buyPct
        (buyPressureBar.layoutParams as LinearLayout.LayoutParams).weight = buyPct.toFloat().coerceAtLeast(0.5f)
        (sellPressureBar.layoutParams as LinearLayout.LayoutParams).weight = sellPct.toFloat().coerceAtLeast(0.5f)
        buyPressureBar.requestLayout()
        sellPressureBar.requestLayout()
        buyPressureText.text = String.format(Locale.US, "B %.0f%%", buyPct)
        sellPressureText.text = String.format(Locale.US, "%.0f%% S", sellPct)

        refreshDerivedFields()
    }

    /**
     * Live open-positions feed, shown in the space freed up by trimming the
     * order book from top-9+9 to top-5+5. Tapping a row prompts a confirm/
     * cancel dialog ([showClosePositionConfirmation]) and, if confirmed,
     * closes that position via [Callbacks.onClosePosition] - mirroring the
     * close flow on the full account panel below the chart, just reachable
     * without leaving the drawer.
     */
    fun renderOpenPositions(positions: List<PaperPosition>) {
        openPositionsContainer.removeAllViews()
        openPositionsEmptyText.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        agentOpenPositionsContainer.removeAllViews()
        agentOpenPositionsEmptyText.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        positions.forEach { position ->
            openPositionsContainer.addView(buildOpenPositionRow(position))
            agentOpenPositionsContainer.addView(buildOpenPositionRow(position))
        }
    }

    private fun buildOpenPositionRow(position: PaperPosition): View {
        val isLong = position.side == PositionSide.LONG
        val sideColor = if (isLong) bullColor else bearColor

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            isClickable = true
            isFocusable = true
            val rippleValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)
            setBackgroundResource(rippleValue.resourceId)
            setOnClickListener { showClosePositionConfirmation(position) }
        }

        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoColumn.addView(
            TextView(context).apply {
                text = if (isLong) "LONG ${position.leverage}x" else "SHORT ${position.leverage}x"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(sideColor)
            },
        )
        infoColumn.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "%.4f @ %,.1f", position.total, position.entryPrice)
                textSize = 9.5f
                setTextColor(mutedColor)
            },
        )
        row.addView(infoColumn)

        val pnl = position.unrealizedPnl
        row.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "%s%,.2f", if (pnl >= 0) "+" else "", pnl)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (pnl >= 0) bullColor else bearColor)
                gravity = Gravity.END
            },
        )
        return row
    }

    /**
     * Live pending-limit-orders feed, shown directly under Open positions.
     * Tapping a row's Cancel button pulls the resting order via
     * [Callbacks.onCancelPendingOrder] - mirroring the cancel flow on the
     * full account panel below the chart, just reachable without leaving
     * the drawer.
     */
    fun renderPendingOrders(orders: List<PendingLimitOrder>) {
        pendingOrdersContainer.removeAllViews()
        pendingOrdersEmptyText.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        agentPendingOrdersContainer.removeAllViews()
        agentPendingOrdersEmptyText.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        orders.forEach { order ->
            pendingOrdersContainer.addView(buildPendingOrderRow(order))
            agentPendingOrdersContainer.addView(buildPendingOrderRow(order))
        }
    }

    private fun buildPendingOrderRow(order: PendingLimitOrder): View {
        val isLong = order.side == PositionSide.LONG
        val sideColor = if (isLong) bullColor else bearColor

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }

        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoColumn.addView(
            TextView(context).apply {
                text = if (isLong) "LONG limit ${order.leverage}x" else "SHORT limit ${order.leverage}x"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(sideColor)
            },
        )
        infoColumn.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "%.4f @ %,.1f", order.sizeInBaseCoin, order.limitPrice)
                textSize = 9.5f
                setTextColor(mutedColor)
            },
        )
        row.addView(infoColumn)

        row.addView(
            TextView(context).apply {
                text = "Cancel"
                textSize = 10f
                setTextColor(labelColor)
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), borderColor)
                }
                setOnClickListener { callbacks?.onCancelPendingOrder?.invoke(order) }
            },
        )
        return row
    }

    /**
     * Confirm/cancel prompt shown when a position row is tapped. Mirrors the
     * wording style of [PaperTradePanel]'s reset-account confirmation - a
     * plain title/message AlertDialog with a destructive-sounding positive
     * action and a no-op Cancel - so closing a position from the drawer
     * never happens on a single accidental tap.
     */
    private fun showClosePositionConfirmation(position: PaperPosition) {
        val sideLabel = if (position.side == PositionSide.LONG) "Long" else "Short"
        AlertDialog.Builder(context)
            .setTitle("Close position?")
            .setMessage(
                String.format(
                    Locale.US,
                    "Close %s %.4f @ %,.1f (%dx)? This closes the position at the current market price.",
                    sideLabel,
                    position.total,
                    position.entryPrice,
                    position.leverage,
                ),
            )
            .setPositiveButton("Close position") { _, _ -> callbacks?.onClosePosition?.invoke(position) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Grab handle (unchanged behavior)
    // ---------------------------------------------------------------------

    private fun buildGrabHandle(): View =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
            isClickable = true
            isFocusable = true
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(borderColor)
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(36), dp(4)).apply {
                        gravity = Gravity.CENTER
                    }
                },
            )
            setOnTouchListener { _, event ->
                // Use raw (screen) coordinates rather than event.y here: the
                // handle's own bounds move live during the drag (the panel
                // resizes on every MOVE via applyQuickTradeProgress), so
                // measuring against this view's local y would have the
                // handle chasing the finger and the delta collapsing toward
                // zero. Screen coordinates stay stable regardless of how the
                // handle itself gets relaid-out mid-gesture.
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

    // ---------------------------------------------------------------------
    // Mode bar: Cross / leverage / position-mode pills
    // ---------------------------------------------------------------------

    private fun buildModeBar(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(buildMarginModePill())
        row.addView(spacerHorizontal(8))
        row.addView(buildLeverageStepper())
        row.addView(spacerHorizontal(8))
        row.addView(pill("S"))
        row.addView(spacerHorizontal(10))
        row.addView(
            buildAccountSummaryBlock().apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        return row
    }

    private fun pill(text: String): View =
        TextView(context).apply {
            this.text = text
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(pillBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

    /** Same look as [pill] but tappable, showing which margin mode is active and opening the Cross/Isolated picker. */
    private fun buildMarginModePill(): View {
        marginModePillText = TextView(context).apply {
            text = "${marginModeOptionLabels[currentMarginMode.ordinal]} ▾"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(pillBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { anchor ->
                showOptionsPopup(anchor, marginModeOptionLabels, currentMarginMode.ordinal) { index ->
                    currentMarginMode = MarginMode.values()[index]
                    marginModePillText.text = "${marginModeOptionLabels[index]} ▾"
                    refreshDerivedFields()
                }
            }
        }
        return marginModePillText
    }

    /**
     * Small dropdown menu of [options] anchored below [anchor], styled to
     * match the drawer's own dark surface/border palette. Used by both the
     * cross/isolated margin-mode pill and the futures-unit picker on the
     * cost box so the two selectors behave identically.
     */
    private fun showOptionsPopup(anchor: View, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        optionsPopup?.dismiss()

        val listCol = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEachIndexed { index, label ->
            listCol.addView(
                TextView(context).apply {
                    text = label
                    textSize = 12.5f
                    setTextColor(if (index == selectedIndex) bullColor else labelColor)
                    typeface = if (index == selectedIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        optionsPopup?.dismiss()
                        onSelect(index)
                    }
                },
            )
        }

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        listCol.measure(unspecified, unspecified)
        val popupWidth = listCol.measuredWidth.coerceAtLeast(dp(140))
        val popupHeight = listCol.measuredHeight

        val popup = PopupWindow(listCol, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        optionsPopup = popup

        val safeArea = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val location = PopupPlacement.below(anchor, popupWidth, popupHeight, safeArea, gapPx = dp(4))
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, location.x, location.y)
    }

    private fun buildLeverageStepper(): View {
        val stepper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(pillBackground)
                setStroke(dp(1), borderColor)
            }
        }
        stepper.addView(stepperButton("−") { adjustLeverage(-1) })
        leverageInput = EditText(context).apply {
            setText(leverageDisplayText(currentLeverage))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            gravity = Gravity.CENTER
            background = null
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(0, 0, 0, 0)
            setSingleLine(true)
            layoutParams = LayoutParams(dp(46), LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingLeverageText) return
                    val digitsOnly = s?.toString().orEmpty().filter { it.isDigit() }
                    val parsed = digitsOnly.toIntOrNull()
                    currentLeverage = (parsed ?: 1).coerceIn(1, PaperTradingRepository.MAX_LEVERAGE)
                    setLeverageText(this@apply, leverageDisplayText(currentLeverage))
                    refreshDerivedFields()
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    setLeverageText(this, leverageDisplayText(currentLeverage))
                }
            }
        }
        stepper.addView(leverageInput)
        stepper.addView(stepperButton("+") { adjustLeverage(1) })
        return stepper
    }

    private fun leverageDisplayText(value: Int): String = "${value}x"

    /** Updates [leverageInput]'s text while suppressing its own watcher, keeping the cursor just before the trailing "x". */
    private fun setLeverageText(target: EditText, text: String) {
        isUpdatingLeverageText = true
        target.setText(text)
        target.setSelection((text.length - 1).coerceAtLeast(0))
        isUpdatingLeverageText = false
    }

    private fun stepperButton(label: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(dp(28), dp(28))
            setOnClickListener { onClick() }
        }

    private fun adjustLeverage(delta: Int) {
        currentLeverage = (currentLeverage + delta).coerceIn(1, PaperTradingRepository.MAX_LEVERAGE)
        setLeverageText(leverageInput, leverageDisplayText(currentLeverage))
        refreshDerivedFields()
    }

    /** Selected leverage, clamped exactly the way [PaperTradingRepository] clamps it before sizing margin. */
    private fun effectiveLeverage(): Int =
        currentLeverage.coerceIn(1, PaperTradingRepository.MAX_LEVERAGE)

    // ---------------------------------------------------------------------
    // Manual / Agent tabs
    // ---------------------------------------------------------------------

    /**
     * Switches the whole drawer body between the existing manual order
     * ticket ([buildSplitRow] - order book, order form, open positions,
     * pending orders) and a placeholder for agent-driven trading, wired up
     * in a later change. Mirrors the "one child, swapped in place" pattern
     * [PaperTradePanel.swapContent] uses.
     */
    private fun buildManualAgentTabs(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
            }
        }
        manualTabText = tabSegment("Manual") { setAgentTabSelected(false) }
        agentTabText = tabSegment("Agent") { setAgentTabSelected(true) }
        row.addView(manualTabText.apply { layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(agentTabText.apply { layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
        return row
    }

    private fun tabSegment(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    private fun setAgentTabSelected(selected: Boolean) {
        if (isAgentTabSelected == selected) return
        isAgentTabSelected = selected
        applyManualAgentTabStyle()
        modeContentContainer.removeAllViews()
        modeContentContainer.addView(if (selected) agentContentView else manualContentView)
    }

    private fun applyManualAgentTabStyle() {
        manualTabText.setTextColor(if (!isAgentTabSelected) labelColor else mutedColor)
        agentTabText.setTextColor(if (isAgentTabSelected) labelColor else mutedColor)
    }

    /** The existing order-book + order-form + positions/pending-orders UI, unchanged. */
    private lateinit var manualContentView: View

    /** RL-agent control surface - see [buildAgentContent]. */
    private lateinit var agentContentView: View

    private fun buildModeContent(): View {
        manualContentView = buildSplitRow()
        agentContentView = buildAgentContent()
        modeContentContainer = FrameLayout(context)
        modeContentContainer.addView(manualContentView)
        return modeContentContainer
    }

    // ---------------------------------------------------------------------
    // Agent tab: RL-driven trading control surface.
    //
    // Deliberately reuses the exact same building blocks as the Manual tab
    // ([fieldBoxRow]/[kvRow]/[pill]/[tradeButton]/[buildDivider]/[spacer]/
    // [showOptionsPopup]) so the two tabs read as one drawer with two modes,
    // not two differently-styled screens. Single scrolling column rather
    // than Manual's order-book/form split, since there's no order book to
    // show here - just agent state, learning/risk config, live inference
    // latency, and running performance.
    //
    // This class holds no RL logic itself, same spirit as the Manual side
    // holding no trading logic: [Callbacks.onToggleAgent] /
    // [Callbacks.onAgentConfigChanged] / [Callbacks.onAgentKillSwitch] /
    // [Callbacks.onResetAgent] hand off to whatever drives the actual
    // policy (see [renderAgentState], [renderAgentPerformance],
    // [recordAgentInferenceLatency] for the feed back in the other
    // direction).
    // ---------------------------------------------------------------------

    private fun buildAgentContent(): View {
        val col = LinearLayout(context).apply { orientation = VERTICAL }

        col.addView(buildAgentHeaderRow())
        col.addView(spacer(2))
        agentPolicyVersionText = TextView(context).apply {
            text = "No policy loaded yet"
            textSize = 10.5f
            setTextColor(mutedColor)
        }
        col.addView(agentPolicyVersionText)

        col.addView(spacer(10))
        col.addView(buildAgentTerminalSection())

        col.addView(spacer(14))
        col.addView(buildDivider())
        col.addView(spacer(10))
        col.addView(sectionLabel("Learning"))
        col.addView(spacer(6))

        val (lrRow, lrInput) = buildAgentFieldRow("Learning rate", "0.001")
        agentLearningRateInput = lrInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(lrRow)
        col.addView(spacer(6))

        val (epsRow, epsInput) = buildAgentFieldRow("Exploration rate (ε)", "0.10")
        agentExplorationRateInput = epsInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(epsRow)
        col.addView(spacer(6))

        val (rewardFunctionRow, rewardFunctionValue) = agentPickerRow("Reward function", currentRewardFunction.label) { anchor ->
            showOptionsPopup(anchor, rewardFunctionOptionLabels, currentRewardFunction.ordinal) { index ->
                currentRewardFunction = RewardFunction.values()[index]
                agentRewardFunctionButton.text = "${currentRewardFunction.label} ▾"
                publishAgentConfig()
            }
        }
        agentRewardFunctionButton = rewardFunctionValue
        col.addView(rewardFunctionRow)
        col.addView(spacer(6))

        val (updateFrequencyRow, updateFrequencyValue) = agentPickerRow("Update frequency", currentUpdateFrequency.label) { anchor ->
            showOptionsPopup(anchor, updateFrequencyOptionLabels, currentUpdateFrequency.ordinal) { index ->
                currentUpdateFrequency = UpdateFrequency.values()[index]
                agentUpdateFrequencyButton.text = "${currentUpdateFrequency.label} ▾"
                publishAgentConfig()
            }
        }
        agentUpdateFrequencyButton = updateFrequencyValue
        col.addView(updateFrequencyRow)
        col.addView(spacer(8))

        val freezeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        agentFreezeLearningCheckbox = CheckBox(context).apply {
            text = "Freeze learning (trade on current policy only)"
            textSize = 12.5f
            setTextColor(labelColor)
            isChecked = learningFrozen
            setOnCheckedChangeListener { _, checked ->
                learningFrozen = checked
                publishAgentConfig()
            }
        }
        freezeRow.addView(agentFreezeLearningCheckbox)
        col.addView(freezeRow)

        col.addView(spacer(14))
        col.addView(buildDivider())
        col.addView(spacer(10))
        col.addView(sectionLabel("Risk guardrails"))
        col.addView(spacer(6))

        val (maxPosRow, maxPosInput) = buildAgentFieldRow("Max position size (USDT)", "0.00")
        agentMaxPositionInput = maxPosInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(maxPosRow)
        col.addView(spacer(6))

        val (maxLevRow, maxLevInput) = buildAgentFieldRow("Max leverage", "${PaperTradingRepository.MAX_LEVERAGE}")
        agentMaxLeverageInput = maxLevInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(maxLevRow)
        col.addView(spacer(6))

        val (maxLossRow, maxLossInput) = buildAgentFieldRow("Max daily loss (USDT)", "0.00")
        agentMaxDailyLossInput = maxLossInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(maxLossRow)
        col.addView(spacer(6))

        val (riskPctRow, riskPctInput) = buildAgentFieldRow("Risk per trade (% of equity)", "2.0")
        agentRiskPerTradeInput = riskPctInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(riskPctRow)
        col.addView(spacer(2))
        col.addView(
            TextView(context).apply {
                text = "At full confidence, a trade risks this % of equity. Actual size scales down with the agent's confidence - a $2 trade isn't inherently risky or safe, it depends what % of equity that is."
                textSize = 10f
                setTextColor(mutedColor)
            },
        )
        col.addView(spacer(6))

        val (minConfRow, minConfInput) = buildAgentFieldRow("Min confidence to trade", "0.40")
        agentMinConfidenceInput = minConfInput.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher { publishAgentConfig() })
        }
        col.addView(minConfRow)
        col.addView(spacer(2))
        col.addView(
            TextView(context).apply {
                text = "Below this, the agent still learns from the tick but won't commit capital. 0.33 = pure guess (3 actions); higher requires real conviction."
                textSize = 10f
                setTextColor(mutedColor)
            },
        )
        col.addView(spacer(14))
        col.addView(buildDivider())
        col.addView(spacer(10))

        // Below this point the Agent tab mirrors the Manual tab's split
        // layout: open positions/pending orders on the left (same live
        // feed, its own View instances - see buildPositionsAndPendingSection),
        // kill switch + inference/performance/reset compressed into a
        // single narrower column on the right.
        val splitRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        splitRow.addView(
            buildPositionsAndPendingSection(
                openContainerSetter = { agentOpenPositionsContainer = it },
                openEmptyTextSetter = { agentOpenPositionsEmptyText = it },
                pendingContainerSetter = { agentPendingOrdersContainer = it },
                pendingEmptyTextSetter = { agentPendingOrdersEmptyText = it },
            ).apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        splitRow.addView(spacerHorizontal(12))

        val rightCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.35f)
        }

        rightCol.addView(
            tradeButton("Kill switch — stop & flatten", bearColor) {
                isAgentRunning = false
                renderAgentState(AgentState.IDLE)
                callbacks?.onAgentKillSwitch?.invoke()
            },
        )

        rightCol.addView(spacer(14))
        rightCol.addView(buildDivider())
        rightCol.addView(spacer(10))
        rightCol.addView(sectionLabel("Inference latency"))
        rightCol.addView(spacer(4))
        agentLastLatencyText = kvRow(rightCol, "Last")
        agentAvgLatencyText = kvRow(rightCol, "Avg")
        agentP95LatencyText = kvRow(rightCol, "P95")
        agentMaxLatencyText = kvRow(rightCol, "Max")
        agentSampleCountText = kvRow(rightCol, "Samples")
        renderInferenceLatencyStats()

        rightCol.addView(spacer(14))
        rightCol.addView(buildDivider())
        rightCol.addView(spacer(10))
        rightCol.addView(sectionLabel("Performance"))
        rightCol.addView(spacer(4))
        agentCumulativeRewardText = kvRow(rightCol, "Cumulative reward")
        agentWinRateText = kvRow(rightCol, "Win rate")
        agentSharpeText = kvRow(rightCol, "Sharpe (session)")
        agentTradeCountText = kvRow(rightCol, "Trades this session")
        rightCol.addView(spacer(8))

        val resetRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        resetRow.addView(
            TextView(context).apply {
                text = "Reset agent"
                textSize = 12f
                setTextColor(mutedColor)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    renderAgentPerformance(cumulativeReward = 0.0, winRatePct = 0.0, sharpe = 0.0, tradeCount = 0)
                    inferenceLatencySamplesMs.clear()
                    renderInferenceLatencyStats()
                    callbacks?.onResetAgent?.invoke()
                }
            },
        )
        rightCol.addView(resetRow)

        splitRow.addView(rightCol)
        col.addView(splitRow)

        renderAgentPerformance(cumulativeReward = 0.0, winRatePct = 0.0, sharpe = 0.0, tradeCount = 0)
        publishAgentConfig()

        return col
    }

    private fun buildAgentHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        agentStateBadge = pill(agentState.label) as TextView
        row.addView(agentStateBadge)
        applyAgentStateBadgeStyle()

        row.addView(spacerHorizontal(8))
        row.addView(View(context).apply { layoutParams = LayoutParams(0, 0, 1f) })

        agentToggleButton = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { toggleAgentRunning() }
        }
        applyAgentToggleStyle()
        row.addView(agentToggleButton)
        return row
    }

    // ---------------------------------------------------------------------
    // Agent tab: transparency terminal. Collapsed by default (it's a debug
    // surface, not the primary control flow). Its data source (the agent
    // decision loop's log bus) has been removed - see AgentTerminalView.
    // ---------------------------------------------------------------------

    private fun buildAgentTerminalSection(): View {
        val section = LinearLayout(context).apply { orientation = VERTICAL }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleAgentTerminal() }
        }
        headerRow.addView(
            TextView(context).apply {
                text = "Terminal — live agent log"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(labelColor)
            },
        )
        headerRow.addView(View(context).apply { layoutParams = LayoutParams(0, 0, 1f) })
        agentTerminalToggle = TextView(context).apply {
            text = "Show ▾"
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        headerRow.addView(agentTerminalToggle)
        section.addView(headerRow)

        section.addView(spacer(6))
        agentTerminalContainer = FrameLayout(context)
        agentTerminalView = AgentTerminalView(context)
        section.addView(agentTerminalContainer)
        applyAgentTerminalExpandedStyle()

        return section
    }

    private fun toggleAgentTerminal() {
        isAgentTerminalExpanded = !isAgentTerminalExpanded
        applyAgentTerminalExpandedStyle()
    }

    private fun applyAgentTerminalExpandedStyle() {
        agentTerminalToggle.text = if (isAgentTerminalExpanded) "Hide ▴" else "Show ▾"
        agentTerminalContainer.removeAllViews()
        if (isAgentTerminalExpanded) {
            agentTerminalContainer.addView(agentTerminalView, LayoutParams(LayoutParams.MATCH_PARENT, dp(220)))
        }
    }

    private fun toggleAgentRunning() {
        isAgentRunning = !isAgentRunning
        renderAgentState(if (isAgentRunning) AgentState.TRADING else AgentState.IDLE)
        callbacks?.onToggleAgent?.invoke(isAgentRunning)
    }

    private fun applyAgentToggleStyle() {
        agentToggleButton.text = if (isAgentRunning) "Stop agent" else "Start agent"
        agentToggleButton.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (isAgentRunning) bearColor else bullColor)
        }
    }

    private fun applyAgentStateBadgeStyle() {
        val color = when (agentState) {
            AgentState.IDLE -> mutedColor
            AgentState.OBSERVING -> warnColor
            AgentState.LEARNING -> warnColor
            AgentState.TRADING -> bullColor
        }
        agentStateBadge.text = "●  ${agentState.label}"
        agentStateBadge.setTextColor(color)
    }

    private fun sectionLabel(text: String): View =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            setTextColor(mutedColor)
        }

    /** Same boxed-field look as [buildPriceFieldRow], relabeled/generalized for the Agent tab's plain numeric inputs. */
    private fun buildAgentFieldRow(label: String, hint: String): Pair<View, EditText> {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 11.5f
                setTextColor(mutedColor)
            },
        )
        val input = EditText(context).apply {
            this.hint = hint
            textSize = 13.5f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            background = null
            setPadding(0, dp(2), 0, 0)
            setSingleLine(true)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        row.addView(input)
        return row to input
    }

    /** Same tappable-pill-with-dropdown look as [buildMarginModePill]/the futures-unit picker, generalized to (label, value). Returns the boxed row to add to a parent, plus the value TextView to update later - mirrors the (row, input) pairing [buildAgentFieldRow] already uses, since the value TextView is already parented inside the row and can't also be added on its own. */
    private fun agentPickerRow(label: String, initialValue: String, onClick: (View) -> Unit): Pair<View, TextView> {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 12.5f
                setTextColor(labelColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val valueText = TextView(context).apply {
            text = "$initialValue ▾"
            textSize = 12.5f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(this) }
        }
        row.addView(valueText)
        // Whole row is tappable too, same reach as the value label alone.
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick(valueText) }
        return row to valueText
    }

    private fun publishAgentConfig() {
        if (!::agentLearningRateInput.isInitialized) return
        val config = AgentConfig(
            learningRate = agentLearningRateInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.001,
            explorationRate = agentExplorationRateInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.1,
            rewardFunction = currentRewardFunction,
            updateFrequency = currentUpdateFrequency,
            learningFrozen = learningFrozen,
            maxPositionSizeUsdt = agentMaxPositionInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0,
            maxLeverage = agentMaxLeverageInput.text?.toString()?.trim()?.toIntOrNull()
                ?.coerceIn(1, PaperTradingRepository.MAX_LEVERAGE) ?: PaperTradingRepository.MAX_LEVERAGE,
            maxDailyLossUsdt = agentMaxDailyLossInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0,
            riskPerTradePct = agentRiskPerTradeInput.text?.toString()?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 20.0) ?: 2.0,
            minConfidenceToTrade = agentMinConfidenceInput.text?.toString()?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.40,
        )
        callbacks?.onAgentConfigChanged?.invoke(config)
    }

    // ---- feed-in points for whatever drives the actual RL loop ----

    /** Updates the state badge + Start/Stop button to reflect the agent's actual lifecycle state (e.g. after a reconnect). */
    fun renderAgentState(state: AgentState) {
        agentState = state
        isAgentRunning = state == AgentState.TRADING || state == AgentState.LEARNING || state == AgentState.OBSERVING
        if (::agentStateBadge.isInitialized) applyAgentStateBadgeStyle()
        if (::agentToggleButton.isInitialized) applyAgentToggleStyle()
    }

    /** Updates the small caption under the header row, e.g. "policy v14 · updated 2m ago". */
    fun renderAgentPolicyInfo(text: String) {
        if (::agentPolicyVersionText.isInitialized) agentPolicyVersionText.text = text
    }

    fun renderAgentPerformance(cumulativeReward: Double, winRatePct: Double, sharpe: Double, tradeCount: Int) {
        if (!::agentCumulativeRewardText.isInitialized) return
        agentCumulativeRewardText.text = String.format(Locale.US, "%,.4f", cumulativeReward)
        agentWinRateText.text = String.format(Locale.US, "%.1f%%", winRatePct)
        agentSharpeText.text = String.format(Locale.US, "%.2f", sharpe)
        agentTradeCountText.text = tradeCount.toString()
    }

    /**
     * Records one inference-latency sample (wall-clock milliseconds for a
     * single forward pass / action decision) and refreshes the Last/Avg/P95/
     * Max readout. Call this from wherever the agent's inference step
     * actually runs, e.g.:
     *
     *   val startNanos = System.nanoTime()
     *   val action = policy.act(state)
     *   quickTradePanel.recordAgentInferenceLatency((System.nanoTime() - startNanos) / 1_000_000)
     *
     * Kept as a bounded rolling window ([maxLatencySamples]) rather than an
     * all-time accumulator so Avg/P95/Max track *recent* behavior - useful
     * for noticing a model or device regression instead of it being diluted
     * by months of history.
     */
    fun recordAgentInferenceLatency(latencyMs: Long) {
        inferenceLatencySamplesMs.addLast(latencyMs)
        while (inferenceLatencySamplesMs.size > maxLatencySamples) {
            inferenceLatencySamplesMs.removeFirst()
        }
        renderInferenceLatencyStats()
    }

    private fun renderInferenceLatencyStats() {
        if (!::agentLastLatencyText.isInitialized) return
        if (inferenceLatencySamplesMs.isEmpty()) {
            agentLastLatencyText.text = "-- ms"
            agentAvgLatencyText.text = "-- ms"
            agentP95LatencyText.text = "-- ms"
            agentMaxLatencyText.text = "-- ms"
            agentSampleCountText.text = "0"
            return
        }
        val sorted = inferenceLatencySamplesMs.sorted()
        val last = inferenceLatencySamplesMs.last()
        val avg = inferenceLatencySamplesMs.average()
        val p95Index = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)
        val p95 = sorted[p95Index]
        val max = sorted.last()

        agentLastLatencyText.text = "$last ms"
        agentLastLatencyText.setTextColor(latencyColor(last))
        agentAvgLatencyText.text = String.format(Locale.US, "%.1f ms", avg)
        agentAvgLatencyText.setTextColor(latencyColor(avg.toLong()))
        agentP95LatencyText.text = "$p95 ms"
        agentP95LatencyText.setTextColor(latencyColor(p95))
        agentMaxLatencyText.text = "$max ms"
        agentMaxLatencyText.setTextColor(latencyColor(max))
        agentSampleCountText.text = inferenceLatencySamplesMs.size.toString()
    }

    /** Green under 50ms (fine for per-tick decisions), amber up to 150ms, red beyond - tune to taste once real inference numbers are in. */
    private fun latencyColor(ms: Long): Int = when {
        ms < 50 -> bullColor
        ms < 150 -> warnColor
        else -> bearColor
    }

    // ---------------------------------------------------------------------
    // Split row: order book + open positions (left) + order form (right)
    // ---------------------------------------------------------------------

    private fun buildSplitRow(): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(
            buildOrderBookColumn().apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(spacerHorizontal(12))
        row.addView(
            buildFormColumn().apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.35f)
            },
        )
        return row
    }

    // ---------------------------------------------------------------------
    // Order form column
    // ---------------------------------------------------------------------

    private fun buildFormColumn(): View {
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(buildOrderTypeRow())
        col.addView(spacer(8))
        col.addView(buildPriceInputRow())
        col.addView(spacer(10))
        col.addView(buildCostRow())
        col.addView(spacer(6))
        estimateText = TextView(context).apply { textSize = 11.5f }
        col.addView(estimateText)
        col.addView(spacer(10))
        col.addView(buildTpSlToggleRow())
        val (tpRowView, tpInputView) = buildPriceFieldRow("TP (USDT)")
        tpRow = tpRowView
        tpInput = tpInputView
        col.addView(tpRow)
        col.addView(spacer(6))
        val (slRowView, slInputView) = buildPriceFieldRow("SL (USDT)")
        slRow = slRowView
        slInput = slInputView
        col.addView(slRow)
        col.addView(spacer(12))

        availableValueText = kvRow(col, "Available")
        maxOpenLongText = kvRow(col, "Max open")
        estLiqLongText = kvRow(col, "Est. liq. price")
        col.addView(spacer(8))
        longButton = tradeButton("Open long", bullColor) { submitOrder(PositionSide.LONG) }
        longButtonSubtext = TextView(context).apply {
            textSize = 10.5f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }
        col.addView(buildTradeButtonBlock(longButton, longButtonSubtext))

        col.addView(spacer(12))
        maxOpenShortText = kvRow(col, "Max open")
        estLiqShortText = kvRow(col, "Est. liq. price")
        col.addView(spacer(8))
        shortButton = tradeButton("Open short", bearColor) { submitOrder(PositionSide.SHORT) }
        shortButtonSubtext = TextView(context).apply {
            textSize = 10.5f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }
        col.addView(buildTradeButtonBlock(shortButton, shortButtonSubtext))

        return col
    }

    private fun buildOrderTypeRow(): View {
        orderTypeText = TextView(context).apply {
            text = "ⓘ Market ▾"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { toggleOrderType() }
        }
        return orderTypeText
    }

    private fun toggleOrderType() {
        currentOrderType = if (currentOrderType == OrderType.MARKET) OrderType.LIMIT else OrderType.MARKET
        val isMarket = currentOrderType == OrderType.MARKET
        orderTypeText.text = if (isMarket) "ⓘ Market ▾" else "ⓘ Limit ▾"
        priceInput.isEnabled = !isMarket
        priceInput.hint = if (isMarket) "Fill at market price" else "Limit price"
        if (isMarket) priceInput.setText("")
        refreshDerivedFields()
    }

    private fun buildPriceInputRow(): View {
        priceInput = EditText(context).apply {
            hint = "Fill at market price"
            isEnabled = false
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13.5f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addTextChangedListener(simpleWatcher { refreshDerivedFields() })
        }
        return priceInput
    }

    private fun buildCostRow(): View {
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val amountCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        costLabelText = TextView(context).apply {
            text = "${currentFuturesUnit.label} (${currentFuturesUnit.unitSuffix})"
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        amountCol.addView(costLabelText)
        costAmountInput = EditText(context).apply {
            hint = "0.00"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            background = null
            setPadding(0, 0, 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addTextChangedListener(simpleWatcher { refreshDerivedFields() })
        }
        amountCol.addView(costAmountInput)
        box.addView(amountCol)

        // Futures-unit picker, defaulting to "Cost" - sits on the right of
        // the box (previously empty space) and opens the same style of
        // dropdown as the Cross/Isolated pill.
        futuresUnitButton = TextView(context).apply {
            text = "${currentFuturesUnit.label} ▾"
            textSize = 11.5f
            setTextColor(mutedColor)
            gravity = Gravity.END
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(2), dp(0), dp(2))
            setOnClickListener { anchor ->
                showOptionsPopup(anchor, futuresUnitOptionLabels, currentFuturesUnit.ordinal) { index ->
                    currentFuturesUnit = FuturesUnit.values()[index]
                    futuresUnitButton.text = "${currentFuturesUnit.label} ▾"
                    costLabelText.text = "${currentFuturesUnit.label} (${currentFuturesUnit.unitSuffix})"
                    refreshDerivedFields()
                }
            }
        }
        box.addView(futuresUnitButton)
        return box
    }

    private fun buildTpSlToggleRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tpSlCheckbox = CheckBox(context).apply {
            text = "TP/SL"
            textSize = 13f
            setTextColor(labelColor)
            isChecked = tpSlEnabled
            setOnCheckedChangeListener { _, checked ->
                tpSlEnabled = checked
                tpRow.visibility = if (checked) View.VISIBLE else View.GONE
                slRow.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        row.addView(tpSlCheckbox)
        row.addView(
            TextView(context).apply {
                text = "Advanced"
                textSize = 12f
                setTextColor(mutedColor)
                gravity = Gravity.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        return row
    }

    private fun buildPriceFieldRow(label: String): Pair<View, EditText> {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val input = EditText(context).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            background = null
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(input)
        row.addView(
            TextView(context).apply {
                text = "Price ▾"
                textSize = 12f
                setTextColor(mutedColor)
            },
        )
        return row to input
    }

    /** Adds a muted label / bold value row to [parent] and returns the value TextView so it can be updated later. */
    private fun kvRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 11.5f
                setTextColor(mutedColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val value = TextView(context).apply {
            text = "0.00 USDT"
            textSize = 12f
            setTextColor(labelColor)
            gravity = Gravity.END
        }
        row.addView(value)
        parent.addView(row)
        return value
    }

    private fun buildTradeButtonBlock(button: TextView, subtext: TextView): View {
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(button)
        col.addView(subtext)
        return col
    }

    private fun tradeButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color)
            }
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    private fun applyOrderTypeStyle() {
        // Kept as a hook: order type is now a single toggling label rather
        // than a segmented control, styled directly in toggleOrderType().
    }

    // ---------------------------------------------------------------------
    // Order book column (top 5 bids + top 5 asks), with open positions
    // filling the space freed up by trimming the book from 9+9 to 5+5.
    // ---------------------------------------------------------------------

    /**
     * Compact account balance / unrealized PnL summary shown inline in the
     * mode bar, to the right of the "S" pill, fed by the same
     * [PaperAccountBalance] passed to [render] - equity for the balance
     * line, [PaperAccountBalance.unrealizedPnl] (signed, colored
     * bull/bear/muted) for the PnL line. [effectiveLeverageCaption] rides
     * along underneath since it previously lived at the end of this same
     * row.
     */
    private fun buildAccountSummaryBlock(): View {
        val block = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }

        val balanceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        balanceRow.addView(
            TextView(context).apply {
                text = "Bal "
                textSize = 10f
                setTextColor(mutedColor)
            },
        )
        accountBalanceValueText = TextView(context).apply {
            text = "0.00 USDT"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        }
        balanceRow.addView(accountBalanceValueText)
        block.addView(balanceRow)

        block.addView(spacer(2))

        val pnlRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        pnlRow.addView(
            TextView(context).apply {
                text = "PnL "
                textSize = 10f
                setTextColor(mutedColor)
            },
        )
        unrealizedPnlValueText = TextView(context).apply {
            text = "0.00 USDT"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(mutedColor)
        }
        pnlRow.addView(unrealizedPnlValueText)
        block.addView(pnlRow)

        effectiveLeverageCaption = TextView(context).apply {
            textSize = 9.5f
            setTextColor(mutedColor)
            gravity = Gravity.END
        }
        block.addView(effectiveLeverageCaption)

        return block
    }

    private fun buildOrderBookColumn(): View {
        val col = LinearLayout(context).apply { orientation = VERTICAL }

        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        header.addView(
            TextView(context).apply {
                text = "Price (USDT)"
                textSize = 10.5f
                setTextColor(mutedColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            TextView(context).apply {
                text = "Qty (USDT)"
                textSize = 10.5f
                setTextColor(mutedColor)
                gravity = Gravity.END
            },
        )
        col.addView(header)
        col.addView(spacer(4))

        askRowsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(askRowsContainer)

        val lastPriceBlock = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        lastPriceText = TextView(context).apply {
            text = "—"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(bullColor)
        }
        lastPriceSubtext = TextView(context).apply {
            text = "—"
            textSize = 10.5f
            setTextColor(mutedColor)
        }
        lastPriceBlock.addView(lastPriceText)
        lastPriceBlock.addView(lastPriceSubtext)
        col.addView(lastPriceBlock)

        bidRowsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(bidRowsContainer)

        // Seed 5+5 empty rows immediately so the layout is stable before the first tick arrives.
        repeat(5) { askRowsContainer.addView(depthRow("--", "--", mutedColor, 0f, Color.TRANSPARENT)) }
        repeat(5) { bidRowsContainer.addView(depthRow("--", "--", mutedColor, 0f, Color.TRANSPARENT)) }

        col.addView(spacer(8))
        val pressureRow = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        buyPressureText = TextView(context).apply {
            text = "B 50%"
            textSize = 10f
            setTextColor(bullColor)
        }
        sellPressureText = TextView(context).apply {
            text = "50% S"
            textSize = 10f
            setTextColor(bearColor)
            gravity = Gravity.END
        }
        val barsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(2) }
        }
        buyPressureBar = View(context).apply {
            setBackgroundColor(bullColor)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        sellPressureBar = View(context).apply {
            setBackgroundColor(bearColor)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        barsRow.addView(buyPressureBar)
        barsRow.addView(sellPressureBar)

        val labelsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        labelsRow.addView(buyPressureText.apply { layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })
        labelsRow.addView(sellPressureText.apply { layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) })

        pressureRow.addView(labelsRow)
        pressureRow.addView(barsRow)
        col.addView(pressureRow)

        col.addView(spacer(12))
        col.addView(buildDivider())
        col.addView(spacer(8))
        col.addView(
            buildPositionsAndPendingSection(
                openContainerSetter = { openPositionsContainer = it },
                openEmptyTextSetter = { openPositionsEmptyText = it },
                pendingContainerSetter = { pendingOrdersContainer = it },
                pendingEmptyTextSetter = { pendingOrdersEmptyText = it },
            ),
        )

        return col
    }

    /**
     * Builds a self-contained "Open positions" + "Pending limit orders"
     * block and hands the container/empty-text views it creates back via
     * the setters, so this can be instantiated once for the Manual tab's
     * left column and again for the Agent tab's left column - each tab
     * gets its own live View instances (a View can only have one parent),
     * kept in sync from the same [renderOpenPositions]/[renderPendingOrders]
     * calls.
     */
    private fun buildPositionsAndPendingSection(
        openContainerSetter: (LinearLayout) -> Unit,
        openEmptyTextSetter: (TextView) -> Unit,
        pendingContainerSetter: (LinearLayout) -> Unit,
        pendingEmptyTextSetter: (TextView) -> Unit,
    ): View {
        val col = LinearLayout(context).apply { orientation = VERTICAL }

        col.addView(
            TextView(context).apply {
                text = "Open positions"
                textSize = 10.5f
                setTextColor(mutedColor)
            },
        )
        col.addView(spacer(4))

        val openEmptyText = TextView(context).apply {
            text = "No open positions"
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(2))
        }
        col.addView(openEmptyText)
        openEmptyTextSetter(openEmptyText)

        val openContainer = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(openContainer)
        openContainerSetter(openContainer)

        col.addView(spacer(12))
        col.addView(buildDivider())
        col.addView(spacer(8))
        col.addView(
            TextView(context).apply {
                text = "Pending limit orders"
                textSize = 10.5f
                setTextColor(mutedColor)
            },
        )
        col.addView(spacer(4))

        val pendingEmptyText = TextView(context).apply {
            text = "No pending orders"
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(2))
        }
        col.addView(pendingEmptyText)
        pendingEmptyTextSetter(pendingEmptyText)

        val pendingContainer = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(pendingContainer)
        pendingContainerSetter(pendingContainer)

        return col
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun depthRow(priceText: String, qtyText: String, priceColor: Int, fillFraction: Float, fillColor: Int): View {
        val rowHeight = dp(17)
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight)
        }
        val maxBarWidth = dp(96)
        val barWidth = (maxBarWidth * fillFraction.coerceIn(0f, 1f)).toInt()
        frame.addView(
            View(context).apply {
                setBackgroundColor(fillColor)
                layoutParams = FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                }
            },
        )
        val textRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        textRow.addView(
            TextView(context).apply {
                text = priceText
                textSize = 10.5f
                setTextColor(priceColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        textRow.addView(
            TextView(context).apply {
                text = qtyText
                textSize = 10f
                setTextColor(mutedColor)
                gravity = Gravity.END
            },
        )
        frame.addView(textRow)
        return frame
    }

    private fun translucent(color: Int, alpha: Int = 60): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun formatAbbreviated(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.2fK", value / 1_000.0)
        else -> String.format(Locale.US, "%.0f", value)
    }

    // ---------------------------------------------------------------------
    // Derived math shared by the estimate line, Max open, and Est. liq. price
    // ---------------------------------------------------------------------

    private fun refreshDerivedFields() {
        if (!::costAmountInput.isInitialized) return

        val effLeverage = effectiveLeverage()
        val nominalLeverage = currentLeverage.coerceIn(1, PaperTradingRepository.MAX_LEVERAGE)
        effectiveLeverageCaption.text = if (effLeverage != nominalLeverage) {
            "risk-capped to ${effLeverage}x"
        } else {
            ""
        }

        val costUsdt = enteredCostUsdt(effLeverage)
        val notionalUsdt = costUsdt * effLeverage
        val refPrice = lastMarkPrice ?: 0.0
        val qty = if (refPrice > 0.0) notionalUsdt / refPrice else 0.0
        estimateText.text = String.format(Locale.US, "≈%,.2f / %,.5f BTC", costUsdt, qty)

        val maxOpenNotional = lastAvailableUsdt * effLeverage
        maxOpenLongText.text = String.format(Locale.US, "%,.2f USDT", maxOpenNotional)
        maxOpenShortText.text = String.format(Locale.US, "%,.2f USDT", maxOpenNotional)

        val typedLimitPrice = priceInput.text?.toString()?.trim()?.toDoubleOrNull()
        val longEntry = if (currentOrderType == OrderType.LIMIT) typedLimitPrice else (lastBestAsk ?: lastMarkPrice)
        val shortEntry = if (currentOrderType == OrderType.LIMIT) typedLimitPrice else (lastBestBid ?: lastMarkPrice)

        estLiqLongText.text = estimateLiquidationPrice(longEntry, effLeverage, costUsdt, isLong = true)?.let {
            String.format(Locale.US, "%,.1f USDT", it)
        } ?: "-- USDT"
        estLiqShortText.text = estimateLiquidationPrice(shortEntry, effLeverage, costUsdt, isLong = false)?.let {
            String.format(Locale.US, "%,.1f USDT", it)
        } ?: "-- USDT"

        longButtonSubtext.text = String.format(Locale.US, "%,.2f USDT", costUsdt)
        shortButtonSubtext.text = String.format(Locale.US, "%,.2f USDT", costUsdt)
    }

    /**
     * Estimated liquidation price for a hypothetical position opened at [entryPrice]
     * with [leverage]x and [costUsdt] margin, using a standard maintenance-margin
     * liquidation model:
     *
     *   marginRatio  = availableMargin / notional
     *   liqDistance  = marginRatio - maintenanceMarginRate - (2 x takerFeeRate)
     *   liqPrice     = entry x (1 -+ liqDistance)   // minus for long, plus for short
     *
     * [currentMarginMode] changes what "available margin" means:
     *  - ISOLATED: only [costUsdt] backs the position, so marginRatio == 1 / leverage,
     *    same as before.
     *  - CROSS: the whole free wallet balance ([lastAvailableUsdt]) also backs the
     *    position, so it can absorb a bigger adverse move before liquidating - this
     *    is why cross-margin liquidation prices sit further from entry than isolated
     *    at the same leverage and size.
     *
     * The 2x taker fee rate accounts for the round trip: the taker fee already paid
     * to open the position, plus the taker fee charged on the forced-liquidation
     * close, both of which eat into the margin cushion before the price fully
     * accounts for it.
     *
     * Recomputed on every call to [refreshDerivedFields] - i.e. on every live mark
     * price tick, every order-book tick, and every edit to leverage, cost, or
     * margin mode - so the estimate always reflects current inputs.
     *
     * Returns null (rendered as "-- USDT") if there isn't enough info yet to
     * estimate: no entry price, or zero cost/leverage.
     */
    private fun estimateLiquidationPrice(
        entryPrice: Double?,
        leverage: Int,
        costUsdt: Double,
        isLong: Boolean,
    ): Double? {
        if (entryPrice == null || entryPrice <= 0.0 || costUsdt <= 0.0 || leverage <= 0) return null

        val notionalUsdt = costUsdt * leverage
        val availableMarginUsdt = when (currentMarginMode) {
            MarginMode.ISOLATED -> costUsdt
            MarginMode.CROSS -> costUsdt + lastAvailableUsdt
        }
        val marginRatio = availableMarginUsdt / notionalUsdt
        val liqDistance = (marginRatio - MAINTENANCE_MARGIN_RATE - 2 * TAKER_FEE_RATE)
            .coerceIn(0.0, 0.999)

        return if (isLong) entryPrice * (1.0 - liqDistance) else entryPrice * (1.0 + liqDistance)
    }

    /**
     * Converts whatever the person typed into [costAmountInput] - interpreted
     * according to the currently selected [FuturesUnit] - into an equivalent
     * USDT margin cost, the same basis all the downstream sizing math
     * (estimate line, Max open, Est. liq. price, order submission) expects.
     */
    private fun enteredCostUsdt(effLeverage: Int): Double {
        val raw = costAmountInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        val refPrice = lastMarkPrice ?: 0.0
        return when (currentFuturesUnit) {
            FuturesUnit.COST -> raw
            FuturesUnit.VALUE -> if (effLeverage > 0) raw / effLeverage else 0.0
            FuturesUnit.QUANTITY -> {
                val notionalUsdt = raw * refPrice
                if (effLeverage > 0) notionalUsdt / effLeverage else 0.0
            }
        }
    }

    private fun submitOrder(side: PositionSide) {
        val effLeverage = effectiveLeverage()
        val costUsdt = enteredCostUsdt(effLeverage)
        if (costUsdt <= 0.0) {
            Toast.makeText(context, "Enter an amount above 0", Toast.LENGTH_SHORT).show()
            return
        }
        val notionalUsdt = costUsdt * effLeverage
        val limitPrice = priceInput.text?.toString()?.trim()
        if (currentOrderType == OrderType.LIMIT && limitPrice.isNullOrEmpty()) {
            priceInput.error = "Enter a limit price"
            return
        }
        val tp = tpInput.text?.toString()?.trim().takeIf { tpSlEnabled && !it.isNullOrEmpty() }
        val sl = slInput.text?.toString()?.trim().takeIf { tpSlEnabled && !it.isNullOrEmpty() }
        callbacks?.onOpenPosition?.invoke(
            side,
            notionalUsdt.toString(),
            currentLeverage,
            currentOrderType,
            limitPrice.takeIf { currentOrderType == OrderType.LIMIT && !it.isNullOrEmpty() },
            tp,
            sl,
        )
    }

    private fun simpleWatcher(onChanged: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun spacerHorizontal(widthDp: Int): View = View(context).apply {
        layoutParams = LayoutParams(dp(widthDp), 0)
    }
}
