package org.example.syncora.ui

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
import org.example.syncora.agent.TrainingRunOutcome
import org.example.syncora.agent.TrainingRunRecord
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PaperTradingRepository
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PositionSide
import java.text.SimpleDateFormat
import java.util.Date
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
        /** Fired after the user confirms the kill-switch dialog - [MainActivity] is expected to call [org.example.syncora.agent.AgentKillSwitchController.engage] and report back via [showKillSwitchResult] / [renderAgentStatus]. */
        val onEngageKillSwitch: () -> Unit = {},
        /** Fired from the "Resume agent" state - [MainActivity] is expected to call [org.example.syncora.agent.AgentKillSwitchController.resumeLoopOnly]. */
        val onResumeAgent: () -> Unit = {},
    )

    private val surfaceColor = Color.parseColor("#0A1015")
    private val borderColor = Color.parseColor("#1B2530")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#8A96A3")
    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")
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
     * [org.example.syncora.bitget.DepthPipeline] already publishes them.
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
        positions.forEach { position ->
            openPositionsContainer.addView(buildOpenPositionRow(position))
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
        orders.forEach { order ->
            pendingOrdersContainer.addView(buildPendingOrderRow(order))
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

    /** Live agent status, decision history, and the manual kill switch - see the "Agent tab" section near the bottom of this class. */
    private lateinit var agentContentView: View

    private fun buildModeContent(): View {
        manualContentView = buildSplitRow()
        agentContentView = buildAgentContent()
        modeContentContainer = FrameLayout(context)
        modeContentContainer.addView(manualContentView)
        return modeContentContainer
    }

    // ---------------------------------------------------------------------
    // Agent tab: live status, decision history, kill switch
    // ---------------------------------------------------------------------

    /**
     * Everything the Agent tab's status card needs for one render pass - assembled by
     * [MainActivity] from [org.example.syncora.agent.DecisionLoopScheduler.lastDecision],
     * [org.example.syncora.bitget.LiveTradingRepository.positions], and
     * [org.example.syncora.agent.TrainingRunStore], so this panel stays a dumb render target,
     * the same contract [render]/[renderOpenPositions]/[renderOrderBook] already follow.
     */
    data class AgentStatusUiState(
        val position: PaperPosition?,
        val lastDecisionAtMs: Long?,
        val lastDecisionSummary: String,
        val lastPromotionAtMs: Long,
        val lastGateDecisionAtMs: Long,
        val lastGateDecisionPassed: Boolean,
        val lastGateDecisionSummary: String,
        val autoTradingEnabled: Boolean,
    )

    /** High-level state the top-of-tab badge collapses everything else down to - see [renderAgentOverview]'s kdoc for how [MainActivity] derives this. */
    enum class AgentState { TRAINING, LIVE_TRADING, HALTED, NO_POLICY }

    /**
     * Everything the "Agent overview" card at the top of the Agent tab needs - the agent's
     * current [AgentState], the live [org.example.syncora.work.PolicyTrainingWorker] run's
     * progress (if one is in flight), and a description of the policy
     * [org.example.syncora.ml.PolicyInferenceEngine] is currently running inference against.
     * Assembled by [MainActivity], same contract as [AgentStatusUiState].
     */
    data class AgentOverviewUiState(
        val agentState: AgentState,
        /** `true` while [org.example.syncora.work.TrainingScheduler]'s periodic work is `RUNNING` right now. */
        val trainingIsRunning: Boolean,
        /** `0..100`, only meaningful while [trainingIsRunning] - the in-flight run's latest reported stage. */
        val trainingProgressPercent: Int,
        /** Human-readable label for the in-flight run's current stage, or the outcome of the most recently completed one when nothing is running. */
        val trainingStatusText: String,
        /** True once any resolved-training outcome (success, skip, or reject) has ever been recorded - distinguishes "never run yet" from "ran and had nothing to do." */
        val hasTrainingHistory: Boolean,
        val policyLoaded: Boolean,
        /** e.g. "current_live_model.tflite" - just the filename, [policyModelSubtext] carries the rest. */
        val policyModelLabel: String,
        /** Size + last-modified summary for the currently loaded model file. */
        val policyModelSubtext: String,
        val lastPromotionAtMs: Long,
        /** e.g. "PPO, clip ε=0.20, lr=3.0e-4 · PBO 0.04 over 6 splits" for the most recent promotion's winning sweep config, or `null` if nothing has ever passed the gate. */
        val policyHyperparametersText: String?,
    )

    /**
     * Live `f_t` indicator block plus the two scalar context fields (`F_t`, `q_t`) from the same
     * [org.example.syncora.bitget.MdpStateSnapshot] the policy is actually fed each decision
     * tick - a direct window into what the agent is looking at right now, not a re-derivation
     * of it. [available] is `false` (and every numeric field meaningless) whenever
     * [org.example.syncora.bitget.StateVectorBuilder.snapshot] can't produce one yet (no
     * connection, insufficient kline history, etc.) - same "not ready, don't fake a zero"
     * discipline [org.example.syncora.bitget.TechnicalIndicators] itself follows.
     */
    data class AgentIndicatorsUiState(
        val available: Boolean,
        val unavailableReason: String = "",
        val rsi: Double = 0.0,
        val dx: Double = 0.0,
        val ultimateOscillator: Double = 0.0,
        val obv: Double = 0.0,
        val htDominantCycle: Double = 0.0,
        val logVolume: Double = 0.0,
        val fundingRate: Double = 0.0,
        val liquidationDistance: Double = 0.0,
        val asOfMs: Long = 0L,
    )

    /** One row of [renderAgentHistory] - a resolved (reward-known) transition off [org.example.syncora.agent.ExperienceLogStore.resolvedRowsSince]. */
    data class AgentHistoryEntryUiState(
        val timestampMs: Long,
        val action: Double,
        val reward: Double,
    )

    private val agentTimeFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private var lastAgentAutoTradingEnabled = false

    private lateinit var agentPositionText: TextView
    private lateinit var agentPositionSubtext: TextView
    private lateinit var agentLastDecisionText: TextView
    private lateinit var agentLastDecisionTimeText: TextView
    private lateinit var agentPromotionText: TextView
    private lateinit var agentPromotionSubtext: TextView
    private lateinit var agentKillSwitchButton: TextView
    private lateinit var agentKillSwitchSubtext: TextView
    private lateinit var agentHistoryContainer: LinearLayout
    private lateinit var agentHistoryEmptyText: TextView

    private lateinit var agentStateDot: View
    private lateinit var agentStateText: TextView
    private lateinit var agentTrainingProgressBar: android.widget.ProgressBar
    private lateinit var agentTrainingStatusText: TextView
    private lateinit var agentPolicyLabelText: TextView
    private lateinit var agentPolicySubtext: TextView
    private lateinit var agentPolicyHyperparamsText: TextView

    private lateinit var agentIndicatorsGrid: LinearLayout
    private lateinit var agentIndicatorsEmptyText: TextView
    private lateinit var agentIndicatorsAsOfText: TextView

    private lateinit var trainingTrendChart: TrainingTrendChartView
    private lateinit var trainingTrendEmptyText: TextView
    private lateinit var trainingTrendSummaryText: TextView
    private lateinit var trainingTrendRunsContainer: LinearLayout

    private fun buildAgentContent(): View {
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(buildAgentOverviewCard())
        col.addView(spacer(12))
        col.addView(buildTrainingTrendCard())
        col.addView(spacer(12))
        col.addView(buildAgentStatusCard())
        col.addView(spacer(12))
        col.addView(buildAgentIndicatorsCard())
        col.addView(spacer(12))
        col.addView(buildAgentKillSwitchCard())
        col.addView(spacer(12))
        col.addView(buildAgentHistoryCard())
        return col
    }

    /**
     * Top-of-tab summary card: at-a-glance agent state, batch-training progress, and which
     * policy is currently live - the three things a "what is my agent doing right now" glance
     * needs, ahead of the more detailed position/decision/promotion breakdown below it.
     */
    private fun buildAgentOverviewCard(): View = agentSectionCard {
        addView(agentSectionLabel("Agent state"))
        addView(spacer(6))
        val stateRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        agentStateDot = View(context).apply {
            layoutParams = LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(mutedColor)
            }
        }
        stateRow.addView(agentStateDot)
        agentStateText = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        }
        stateRow.addView(agentStateText)
        addView(stateRow)

        addView(spacer(12))
        addView(agentDivider())
        addView(spacer(12))

        addView(agentSectionLabel("Training progress"))
        addView(spacer(6))
        agentTrainingProgressBar = android.widget.ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            isIndeterminate = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(6))
        }
        addView(agentTrainingProgressBar)
        addView(spacer(6))
        agentTrainingStatusText = TextView(context).apply {
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        addView(agentTrainingStatusText)

        addView(spacer(12))
        addView(agentDivider())
        addView(spacer(12))

        addView(agentSectionLabel("Current policy"))
        addView(spacer(4))
        agentPolicyLabelText = TextView(context).apply {
            textSize = 13f
            setTextColor(labelColor)
        }
        addView(agentPolicyLabelText)
        agentPolicySubtext = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
        }
        addView(agentPolicySubtext)
        agentPolicyHyperparamsText = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
        }
        addView(agentPolicyHyperparamsText)
    }

    /**
     * Read-only view into the live `f_t` block plus `F_t`/`q_t` - see [AgentIndicatorsUiState].
     * Purely diagnostic: nothing here is editable or drives a callback, it's just "what is the
     * policy currently seeing," rendered as a small label/value grid.
     */
    private fun buildAgentIndicatorsCard(): View = agentSectionCard {
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(agentSectionLabel("State snapshot").apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        agentIndicatorsAsOfText = TextView(context).apply {
            textSize = 10f
            setTextColor(mutedColor)
        }
        headerRow.addView(agentIndicatorsAsOfText)
        addView(headerRow)
        addView(spacer(8))

        agentIndicatorsEmptyText = TextView(context).apply {
            text = "Not ready yet — waiting on account data and kline history."
            textSize = 12f
            setTextColor(mutedColor)
        }
        addView(agentIndicatorsEmptyText)

        agentIndicatorsGrid = LinearLayout(context).apply { orientation = VERTICAL }
        addView(agentIndicatorsGrid)
    }

    private fun indicatorRow(label: String, value: String): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 12f
                    setTextColor(mutedColor)
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(labelColor)
                },
            )
        }

    /**
     * Trend view over the last several
     * [org.example.syncora.work.PolicyTrainingWorker] runs - a
     * [TrainingTrendChartView] bar chart of PBO probability per run plus a short scrollable list
     * of the same runs with their outcome/PBO/splits, so "is the gate passing more or less often
     * lately, and how close to the reject threshold" is visible without leaving the tab. Purely
     * historical/diagnostic, fed by [renderTrainingTrend] off
     * [org.example.syncora.agent.TrainingRunHistoryStore.recent] - distinct from
     * [buildAgentOverviewCard]'s in-flight progress and [buildAgentStatusCard]'s single
     * latest-run summary.
     */
    private fun buildTrainingTrendCard(): View = agentSectionCard {
        addView(agentSectionLabel("Training run trend"))
        addView(spacer(6))
        trainingTrendEmptyText = TextView(context).apply {
            text = "No completed training runs yet."
            textSize = 12f
            setTextColor(mutedColor)
        }
        addView(trainingTrendEmptyText)

        trainingTrendChart = TrainingTrendChartView(context).apply {
            passColor = bullColor
            rejectColor = bearColor
            neutralColor = mutedColor
            thresholdColor = mutedColor
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(56))
        }
        addView(trainingTrendChart)
        addView(spacer(4))

        val legendRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        legendRow.addView(trendLegendChip("Passed", bullColor))
        legendRow.addView(spacerHorizontal(10))
        legendRow.addView(trendLegendChip("Rejected", bearColor))
        legendRow.addView(spacerHorizontal(10))
        legendRow.addView(trendLegendChip("Skipped/failed", mutedColor))
        addView(legendRow)
        addView(spacer(4))

        trainingTrendSummaryText = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
        }
        addView(trainingTrendSummaryText)

        addView(spacer(10))
        addView(agentDivider())
        addView(spacer(10))

        trainingTrendRunsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(trainingTrendRunsContainer)
    }

    private fun trendLegendChip(label: String, color: Int): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            View(context).apply {
                layoutParams = LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(4) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            },
        )
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 10f
                setTextColor(mutedColor)
            },
        )
        return row
    }

    private fun agentSectionCard(builder: LinearLayout.() -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            builder()
        }

    private fun agentSectionLabel(text: String): TextView =
        TextView(context).apply {
            this.text = text.uppercase(Locale.US)
            textSize = 10.5f
            letterSpacing = 0.05f
            setTextColor(mutedColor)
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun agentDivider(): View =
        View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(borderColor)
        }

    private fun buildAgentStatusCard(): View = agentSectionCard {
        addView(agentSectionLabel("Position"))
        addView(spacer(4))
        agentPositionText = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        }
        addView(agentPositionText)
        agentPositionSubtext = TextView(context).apply {
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        addView(agentPositionSubtext)

        addView(spacer(12))
        addView(agentDivider())
        addView(spacer(12))

        addView(agentSectionLabel("Last decision"))
        addView(spacer(4))
        agentLastDecisionText = TextView(context).apply {
            textSize = 13f
            setTextColor(labelColor)
        }
        addView(agentLastDecisionText)
        agentLastDecisionTimeText = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
        }
        addView(agentLastDecisionTimeText)

        addView(spacer(12))
        addView(agentDivider())
        addView(spacer(12))

        addView(agentSectionLabel("Last model promotion"))
        addView(spacer(4))
        agentPromotionText = TextView(context).apply {
            textSize = 13f
            setTextColor(labelColor)
        }
        addView(agentPromotionText)
        agentPromotionSubtext = TextView(context).apply {
            textSize = 11f
            setTextColor(mutedColor)
        }
        addView(agentPromotionSubtext)
    }

    private fun buildAgentKillSwitchCard(): View = agentSectionCard {
        addView(agentSectionLabel("Manual override"))
        addView(spacer(6))
        agentKillSwitchSubtext = TextView(context).apply {
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        addView(agentKillSwitchSubtext)
        addView(spacer(10))
        agentKillSwitchButton = tradeButton("Kill switch — halt & flatten", bearColor) { confirmKillSwitch() }
        addView(agentKillSwitchButton)
    }

    private fun buildAgentHistoryCard(): View = agentSectionCard {
        addView(agentSectionLabel("Decision history"))
        addView(spacer(6))
        agentHistoryEmptyText = TextView(context).apply {
            text = "No resolved decisions logged yet."
            textSize = 12f
            setTextColor(mutedColor)
        }
        addView(agentHistoryEmptyText)
        agentHistoryContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(agentHistoryContainer)
    }

    /**
     * Guards the destructive action behind an explicit confirmation - this stops live trading
     * and force-closes a real position, so it should never fire off a single accidental tap.
     */
    private fun confirmKillSwitch() {
        AlertDialog.Builder(context)
            .setTitle("Engage kill switch?")
            .setMessage(
                "This immediately stops the agent's decision loop and closes any open " +
                    "BTCUSDT position at market. Auto-trading stays off until you resume it.",
            )
            .setPositiveButton("Halt & flatten") { dialog, _ ->
                dialog.dismiss()
                renderAgentKillSwitchState(engaged = true, busy = true)
                callbacks?.onEngageKillSwitch?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Renders the top-of-tab overview card - state badge, training-run progress, and current
     * policy - from an [AgentOverviewUiState] [MainActivity] assembles once per poll tick (see
     * that class's `buildAgentOverviewUiState`). Separate entry point from [renderAgentStatus]
     * since the two are fed by different underlying sources (`WorkManager`'s `WorkInfo` for
     * this one, [org.example.syncora.agent.DecisionLoopScheduler]/[org.example.syncora.bitget.LiveTradingRepository]
     * for that one) and there's no reason to force one render pass to wait on the other.
     */
    fun renderAgentOverview(overview: AgentOverviewUiState) {
        val (dotColor, label) = when (overview.agentState) {
            AgentState.TRAINING -> bullColor to "Training"
            AgentState.LIVE_TRADING -> bullColor to "Live — trading"
            AgentState.HALTED -> bearColor to "Halted"
            AgentState.NO_POLICY -> mutedColor to "No policy loaded"
        }
        (agentStateDot.background as? GradientDrawable)?.setColor(dotColor)
        agentStateText.text = label
        agentStateText.setTextColor(dotColor)

        if (overview.trainingIsRunning) {
            agentTrainingProgressBar.isIndeterminate = false
            agentTrainingProgressBar.progress = overview.trainingProgressPercent.coerceIn(0, 100)
            agentTrainingProgressBar.alpha = 1f
        } else {
            agentTrainingProgressBar.progress = if (overview.hasTrainingHistory) 100 else 0
            agentTrainingProgressBar.alpha = 0.35f
        }
        agentTrainingStatusText.text = overview.trainingStatusText

        if (overview.policyLoaded) {
            agentPolicyLabelText.text = overview.policyModelLabel
            agentPolicyLabelText.setTextColor(labelColor)
        } else {
            agentPolicyLabelText.text = "No model loaded yet"
            agentPolicyLabelText.setTextColor(mutedColor)
        }
        agentPolicySubtext.text = overview.policyModelSubtext
        agentPolicyHyperparamsText.text = overview.policyHyperparametersText ?: "No config has passed the CPCV/PBO gate yet"
    }

    /** Renders [AgentIndicatorsUiState] into the "State snapshot" card - a plain label/value grid when [AgentIndicatorsUiState.available], an explanatory empty-state line otherwise. */
    fun renderAgentIndicators(state: AgentIndicatorsUiState) {
        agentIndicatorsGrid.removeAllViews()
        if (!state.available) {
            agentIndicatorsEmptyText.visibility = View.VISIBLE
            agentIndicatorsEmptyText.text = state.unavailableReason.ifBlank {
                "Not ready yet — waiting on account data and kline history."
            }
            agentIndicatorsAsOfText.text = ""
            return
        }
        agentIndicatorsEmptyText.visibility = View.GONE
        agentIndicatorsAsOfText.text = agentTimeFormatter.format(Date(state.asOfMs))

        agentIndicatorsGrid.addView(indicatorRow("RSI (14)", String.format(Locale.US, "%.1f", state.rsi)))
        agentIndicatorsGrid.addView(indicatorRow("DX (14)", String.format(Locale.US, "%.1f", state.dx)))
        agentIndicatorsGrid.addView(indicatorRow("Ultimate Osc.", String.format(Locale.US, "%.1f", state.ultimateOscillator)))
        agentIndicatorsGrid.addView(indicatorRow("OBV (z, tanh)", String.format(Locale.US, "%.3f", state.obv)))
        agentIndicatorsGrid.addView(indicatorRow("HT dominant cycle", String.format(Locale.US, "%.1f bars", state.htDominantCycle)))
        agentIndicatorsGrid.addView(indicatorRow("log(volume)", String.format(Locale.US, "%.3f", state.logVolume)))
        agentIndicatorsGrid.addView(indicatorRow("Funding rate", String.format(Locale.US, "%.4f%%", state.fundingRate * 100.0)))
        agentIndicatorsGrid.addView(indicatorRow("Liq. distance", String.format(Locale.US, "%.2f%%", state.liquidationDistance * 100.0)))
    }

    /**
     * Status feed for the Agent tab - current position, last decision-loop outcome, and last
     * model promotion/gate result. Separate entry point from [renderOpenPositions] since that
     * one drives the manual tab's multi-symbol list, while this is a single-symbol summary
     * plus loop/training telemetry that has nothing to do with the manual order ticket.
     */
    fun renderAgentStatus(status: AgentStatusUiState) {
        lastAgentAutoTradingEnabled = status.autoTradingEnabled

        val position = status.position
        if (position == null) {
            agentPositionText.text = "Flat"
            agentPositionText.setTextColor(mutedColor)
            agentPositionSubtext.text = "No open BTCUSDT position"
        } else {
            val sideLabel = if (position.side == PositionSide.LONG) "Long" else "Short"
            agentPositionText.text = String.format(Locale.US, "%s %.4f BTC", sideLabel, position.total)
            agentPositionText.setTextColor(if (position.side == PositionSide.LONG) bullColor else bearColor)
            val pnlSign = if (position.unrealizedPnl >= 0.0) "+" else ""
            agentPositionSubtext.text = String.format(
                Locale.US,
                "Entry %,.1f · Mark %,.1f · uPnL %s%,.2f USDT",
                position.entryPrice,
                position.markPrice,
                pnlSign,
                position.unrealizedPnl,
            )
        }

        agentLastDecisionText.text = status.lastDecisionSummary
        agentLastDecisionTimeText.text = status.lastDecisionAtMs
            ?.let { agentTimeFormatter.format(Date(it)) }
            ?: "No decisions logged yet"

        if (status.lastPromotionAtMs > 0L) {
            agentPromotionText.text = "Promoted " + agentTimeFormatter.format(Date(status.lastPromotionAtMs))
            agentPromotionText.setTextColor(bullColor)
        } else {
            agentPromotionText.text = "No model has been promoted yet"
            agentPromotionText.setTextColor(mutedColor)
        }
        agentPromotionSubtext.text = if (status.lastGateDecisionAtMs > 0L) {
            val outcome = if (status.lastGateDecisionPassed) "pass" else "reject"
            "Last gate check ($outcome) ${agentTimeFormatter.format(Date(status.lastGateDecisionAtMs))} — ${status.lastGateDecisionSummary}"
        } else {
            "No training run has completed a gate check yet"
        }

        renderAgentKillSwitchState(engaged = !status.autoTradingEnabled, busy = false)
    }

    /**
     * Reflects the master [org.example.syncora.bitget.RiskSettingsStore.autoTradingEnabled]
     * switch plus in-flight state. [busy] disables the button while
     * [org.example.syncora.agent.AgentKillSwitchController.engage] is running so a slow
     * network round-trip can't be double-tapped into two concurrent flatten attempts.
     */
    fun renderAgentKillSwitchState(engaged: Boolean, busy: Boolean) {
        agentKillSwitchButton.isEnabled = !busy
        agentKillSwitchButton.alpha = if (busy) 0.6f else 1f
        when {
            busy -> {
                agentKillSwitchButton.text = "Halting…"
                agentKillSwitchSubtext.text = "Stopping the decision loop and closing open positions."
            }
            engaged -> {
                agentKillSwitchButton.text = "Resume agent"
                (agentKillSwitchButton.background as? GradientDrawable)?.setColor(bullColor)
                agentKillSwitchButton.setOnClickListener { callbacks?.onResumeAgent?.invoke() }
                agentKillSwitchSubtext.text = "Auto-trading is OFF. The decision loop is halted."
            }
            else -> {
                agentKillSwitchButton.text = "Kill switch — halt & flatten"
                (agentKillSwitchButton.background as? GradientDrawable)?.setColor(bearColor)
                agentKillSwitchButton.setOnClickListener { confirmKillSwitch() }
                agentKillSwitchSubtext.text =
                    "Auto-trading is ON. This stops the loop immediately and closes any open position at market."
            }
        }
    }

    /**
     * One-shot result toast after [org.example.syncora.agent.AgentKillSwitchController.engage]
     * completes - kept separate from [renderAgentStatus] so [MainActivity] doesn't have to
     * re-derive the whole status card just to report this.
     */
    fun showKillSwitchResult(flattenedCount: Int, failedSymbols: List<String>) {
        val message = when {
            failedSymbols.isNotEmpty() ->
                "Loop halted. Flattened $flattenedCount position(s); failed to close: ${failedSymbols.joinToString()}"
            flattenedCount > 0 -> "Loop halted and $flattenedCount position(s) flattened."
            else -> "Loop halted. No open positions to flatten."
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Decision-history feed for the Agent tab - resolved (reward-known) transitions off
     * [org.example.syncora.agent.ExperienceLogStore.resolvedRowsSince], newest first. Rebuilds
     * the row list on each call since this is a bounded, infrequently-refreshed list (see
     * [MainActivity]'s polling interval), not a hot per-tick update - the same simplicity
     * tradeoff [renderOpenPositions] already makes for its own list.
     */
    fun renderAgentHistory(entries: List<AgentHistoryEntryUiState>) {
        agentHistoryContainer.removeAllViews()
        agentHistoryEmptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { entry -> agentHistoryContainer.addView(agentHistoryRow(entry)) }
    }

    /**
     * Feeds [buildTrainingTrendCard] off [org.example.syncora.agent.TrainingRunHistoryStore.recent]
     * (newest-first, same order that store returns). Rebuilds the run-list rows on each call for
     * the same reason [renderAgentHistory] does - a bounded, infrequently-refreshed list, not a
     * hot per-tick update.
     */
    fun renderTrainingTrend(runsNewestFirst: List<TrainingRunRecord>) {
        val hasHistory = runsNewestFirst.isNotEmpty()
        trainingTrendEmptyText.visibility = if (hasHistory) View.GONE else View.VISIBLE
        trainingTrendChart.visibility = if (hasHistory) View.VISIBLE else View.GONE
        trainingTrendChart.render(runsNewestFirst)

        val passed = runsNewestFirst.count { it.outcome == TrainingRunOutcome.PASSED }
        val rejected = runsNewestFirst.count { it.outcome == TrainingRunOutcome.REJECTED }
        val latestPbo = runsNewestFirst.firstOrNull { it.pboProbability != null }?.pboProbability
        trainingTrendSummaryText.text = if (hasHistory) {
            val pboText = latestPbo?.let { String.format(Locale.US, ", latest PBO %.3f", it) } ?: ""
            "Last ${runsNewestFirst.size} runs — $passed passed, $rejected rejected$pboText"
        } else {
            ""
        }

        trainingTrendRunsContainer.removeAllViews()
        runsNewestFirst.take(8).forEach { run -> trainingTrendRunsContainer.addView(trainingTrendRunRow(run)) }
    }

    private fun trainingTrendRunRow(run: TrainingRunRecord): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val (outcomeLabel, outcomeColor) = when (run.outcome) {
            TrainingRunOutcome.PASSED -> "Passed" to bullColor
            TrainingRunOutcome.REJECTED -> "Rejected" to bearColor
            TrainingRunOutcome.SKIPPED_INSUFFICIENT_DATA -> "Skipped (data)" to mutedColor
            TrainingRunOutcome.SKIPPED_INSUFFICIENT_SPLITS -> "Skipped (splits)" to mutedColor
            TrainingRunOutcome.FAILED -> "Failed" to bearColor
        }
        row.addView(
            TextView(context).apply {
                text = agentTimeFormatter.format(Date(run.timestampMs))
                textSize = 11f
                setTextColor(mutedColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.3f)
            },
        )
        row.addView(
            TextView(context).apply {
                text = outcomeLabel
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(outcomeColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(context).apply {
                text = run.pboProbability?.let { String.format(Locale.US, "PBO %.3f", it) } ?: "—"
                textSize = 11f
                setTextColor(labelColor)
                gravity = Gravity.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        return row
    }

    private fun agentHistoryRow(entry: AgentHistoryEntryUiState): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(
            TextView(context).apply {
                text = agentTimeFormatter.format(Date(entry.timestampMs))
                textSize = 11.5f
                setTextColor(mutedColor)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.1f)
            },
        )
        row.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "a=%.3f", entry.action)
                textSize = 11.5f
                setTextColor(labelColor)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(context).apply {
                val sign = if (entry.reward >= 0.0) "+" else ""
                text = String.format(Locale.US, "%s%.4f", sign, entry.reward)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (entry.reward >= 0.0) bullColor else bearColor)
                gravity = Gravity.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        return row
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
            TextView(context).apply {
                text = "Open positions"
                textSize = 10.5f
                setTextColor(mutedColor)
            },
        )
        col.addView(spacer(4))

        openPositionsEmptyText = TextView(context).apply {
            text = "No open positions"
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(2))
        }
        col.addView(openPositionsEmptyText)

        openPositionsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(openPositionsContainer)

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

        pendingOrdersEmptyText = TextView(context).apply {
            text = "No pending orders"
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(2))
        }
        col.addView(pendingOrdersEmptyText)

        pendingOrdersContainer = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(pendingOrdersContainer)

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
        val liqDistance = 1.0 / effLeverage

        estLiqLongText.text = if (longEntry != null && longEntry > 0.0) {
            String.format(Locale.US, "%,.1f USDT", longEntry * (1.0 - liqDistance))
        } else {
            "-- USDT"
        }
        estLiqShortText.text = if (shortEntry != null && shortEntry > 0.0) {
            String.format(Locale.US, "%,.1f USDT", shortEntry * (1.0 + liqDistance))
        } else {
            "-- USDT"
        }

        longButtonSubtext.text = String.format(Locale.US, "%,.2f USDT", costUsdt)
        shortButtonSubtext.text = String.format(Locale.US, "%,.2f USDT", costUsdt)
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
