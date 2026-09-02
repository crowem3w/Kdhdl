package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PositionSide
import org.example.syncora.chart.OrderbookDepthSurfaceView

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

    private lateinit var scrollView: ScrollView

    private lateinit var depthSurfaceView: OrderbookDepthSurfaceView

    val scrollableContent: View
        get() = scrollView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        addView(buildGrabHandle())
        scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(buildScrollContent())
        }
        addView(scrollView)
    }

    private fun buildScrollContent(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(buildLiquiditySurfaceSection())
        }

    private fun buildLiquiditySurfaceSection(): View =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply {
                topMargin = dp(8)
                marginStart = dp(4)
                marginEnd = dp(4)
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#0A0E14"))
                setStroke(dp(1), borderColor)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            depthSurfaceView = OrderbookDepthSurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            addView(depthSurfaceView)
        }

    fun bind(callbacks: Callbacks) {
    }

    fun render(balance: PaperAccountBalance?) = Unit

    fun renderMarkPrice(price: Double?) = Unit

    fun renderOrderBook(bids: List<DepthLevel>, asks: List<DepthLevel>) {
        depthSurfaceView.submitOrderBook(bids, asks)
    }

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
