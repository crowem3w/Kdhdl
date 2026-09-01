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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        addView(buildGrabHandle())
        scrollView = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        addView(scrollView)
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
