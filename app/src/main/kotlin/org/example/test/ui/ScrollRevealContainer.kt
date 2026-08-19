package org.example.test.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Root container for the chart screen. Detects vertical drag gestures
 * happening anywhere *except* the chart's interactive plot area, and
 * reports them live through [onVerticalDrag] so the caller can drive an
 * on-screen, finger-following reveal animation (rather than a fire-once
 * threshold).
 *
 * The plot area to exclude is described relative to
 * [excludedInteractiveView] (the candlestick chart) via
 * [excludedRightInsetPx] / [excludedBottomInsetPx], which carve the price
 * axis (right strip) and time axis (bottom strip) back *out* of the
 * exclusion - so drags starting on those strips are still reported even
 * though they're visually inside the chart view's bounds. Everything
 * outside the chart view entirely (header, banner, timeframe row, toolbar
 * icons) is eligible by default.
 *
 * [excludedScrollableView], if set, carves out a second zone for a view
 * that does its own vertical scrolling - e.g. the quick-trade drawer's body
 * once it has more controls than fit in its allotted height. That zone is
 * only actually excluded when the view has something to scroll (checked via
 * [View.canScrollVertically] in both directions); when its content already
 * fits, dragging over it still drives the reveal gesture like anywhere else,
 * so nothing regresses on screens where the drawer never needs to scroll.
 *
 * Uses the standard "intercept once a real drag is detected" pattern (the
 * same one ScrollView/RecyclerView use) so taps on buttons that happen to
 * sit in an eligible zone - e.g. the timeframe pills - still register as
 * normal clicks; only gestures that move past touch-slop vertically get
 * stolen for the reveal.
 */
class ScrollRevealContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class DragPhase { START, MOVE, END, CANCEL }

    /** The chart view whose plot area (minus axis strips) is off-limits to this gesture. */
    var excludedInteractiveView: View? = null

    /** Width of the price-axis strip (screen right edge of [excludedInteractiveView]) to carve back out of the exclusion. */
    var excludedRightInsetPx: Float = 0f

    /** Height of the time-axis strip (screen bottom edge of [excludedInteractiveView]) to carve back out of the exclusion. */
    var excludedBottomInsetPx: Float = 0f

    /** A second, self-scrolling view (e.g. the quick-trade drawer's body). Only excluded from this gesture while it actually has content to scroll - see class doc. */
    var excludedScrollableView: View? = null

    /** [deltaY] is event.y - downY in this container's local coordinates: positive means the finger moved down. */
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
        // If the target has nothing to scroll in either direction, its bounds
        // aren't excluded - let the reveal gesture handle drags there as usual.
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
