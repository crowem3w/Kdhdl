package org.example.syncora.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PositionSide
import org.example.syncora.chart.AgentPerformanceChartView
import org.example.syncora.chart.AgentPerformancePoint
import org.example.syncora.rrl.RrlStepResult

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

    private val borderColor = android.graphics.Color.parseColor("#1B2530")

    var onHandleDrag: ((phase: ScrollRevealContainer.DragPhase, deltaY: Float) -> Unit)? = null

    private var handleDownY = 0f

    private lateinit var scrollView: View

    val scrollableContent: View
        get() = scrollView

    private lateinit var leftColumn: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var performanceChart: AgentPerformanceChartView

    /** Left column container (order entry / controls), exposed for populating this panel's content. */
    val leftColumnContent: LinearLayout
        get() = leftColumn

    /** Right column container, exposed for populating any content below the performance chart. */
    val rightColumnContent: LinearLayout
        get() = rightColumn

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        addView(buildGrabHandle())
        scrollView = buildColumns()
        addView(scrollView)
    }

    private fun buildColumns(): View {
        val columns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }

        leftColumn = LinearLayout(context).apply { orientation = VERTICAL }
        rightColumn = LinearLayout(context).apply { orientation = VERTICAL }

        performanceChart = AgentPerformanceChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132)).apply {
                bottomMargin = dp(10)
            }
        }
        rightColumn.addView(performanceChart)

        columns.addView(
            leftColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(6)
            },
        )
        columns.addView(
            rightColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(6)
            },
        )
        return columns
    }

    /** Renders the agent's performance metrics as a gradient line chart above the right column. */
    fun renderAgentPerformance(
        points: List<AgentPerformancePoint>,
        xAxisLabel: String,
        yAxisLabel: String,
    ) {
        performanceChart.submit(points, xAxisLabel, yAxisLabel)
    }

    /** Convenience overload: plots the agent's cumulative reward across a series of RRL steps. */
    fun renderAgentPerformance(
        steps: List<RrlStepResult>,
        xAxisLabel: String = "Step",
        yAxisLabel: String = "Cumulative Reward",
    ) {
        var cumulative = 0f
        val points = steps.mapIndexed { index, step ->
            cumulative += step.reward.toFloat()
            AgentPerformancePoint(index.toFloat(), cumulative)
        }
        renderAgentPerformance(points, xAxisLabel, yAxisLabel)
    }

    fun bind(callbacks: Callbacks) {
    }

    fun render(balance: PaperAccountBalance?) = Unit

    fun renderMarkPrice(price: Double?) = Unit

    fun renderOrderBook(bids: List<DepthLevel>, asks: List<DepthLevel>) = Unit

    fun renderOpenPositions(positions: List<PaperPosition>) = Unit

    fun renderPendingOrders(orders: List<PendingLimitOrder>) = Unit

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
}
