package org.example.syncora.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs































class ScrollRevealContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class DragPhase { START, MOVE, END, CANCEL }

    
    var excludedInteractiveView: View? = null

    
    var excludedRightInsetPx: Float = 0f

    
    var excludedBottomInsetPx: Float = 0f

    
    var excludedScrollableView: View? = null

    
    var onVerticalDrag: ((phase: DragPhase, deltaY: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isEligibleZone = false
    private var isDragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isEligibleZone = !isInsideExcludedPlotArea(ev.x, ev.y)
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isEligibleZone && !isDragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        isDragging = true
                        onVerticalDrag?.invoke(DragPhase.START, 0f)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isEligibleZone = false
                isDragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> onVerticalDrag?.invoke(DragPhase.MOVE, event.y - downY)
            MotionEvent.ACTION_UP -> {
                onVerticalDrag?.invoke(DragPhase.END, event.y - downY)
                isDragging = false
                isEligibleZone = false
            }
            MotionEvent.ACTION_CANCEL -> {
                onVerticalDrag?.invoke(DragPhase.CANCEL, event.y - downY)
                isDragging = false
                isEligibleZone = false
            }
        }
        return true
    }

    private fun isInsideExcludedPlotArea(x: Float, y: Float): Boolean {
        val chart = excludedInteractiveView
        if (chart != null && isInsideRegion(x, y, chart, excludedRightInsetPx, excludedBottomInsetPx)) {
            return true
        }
        val scrollable = excludedScrollableView
        if (scrollable != null && isInsideRegion(x, y, scrollable, requireScrollable = true)) {
            return true
        }
        return false
    }

    private fun isInsideRegion(
        x: Float,
        y: Float,
        target: View,
        rightInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f,
        requireScrollable: Boolean = false,
    ): Boolean {
        if (target.visibility != View.VISIBLE) return false
        
        
        if (requireScrollable && !target.canScrollVertically(1) && !target.canScrollVertically(-1)) return false
        val containerLoc = IntArray(2)
        val targetLoc = IntArray(2)
        getLocationOnScreen(containerLoc)
        target.getLocationOnScreen(targetLoc)
        val left = (targetLoc[0] - containerLoc[0]).toFloat()
        val top = (targetLoc[1] - containerLoc[1]).toFloat()
        val right = left + target.width - rightInsetPx
        val bottom = top + target.height - bottomInsetPx
        return x in left..right && y in top..bottom
    }
}