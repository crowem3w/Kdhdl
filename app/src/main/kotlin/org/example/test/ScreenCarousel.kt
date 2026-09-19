package org.example.test

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen overlay shown when a screen's canvas is pinched all the way down to its minimum
 * zoom (see SketchCanvasView.Listener.onReachedMinZoom, wired up in SketchActivity's
 * canvasListener / openScreenCarousel()). Presents every ScreenPage as a shrunk card, one
 * centered at a time, that can be swiped through left/right - a quick way to browse and jump
 * between screens without leaving the canvas.
 *
 * Each card reuses the screen's *actual* SketchCanvasView as its content (temporarily
 * reparented in here from canvasArea, touch-disabled, and visually shrunk via scaleX/scaleY)
 * rather than a separate drawn/rasterized snapshot, so previews always match the live canvas
 * exactly. SketchActivity hands the views in via bind() and reclaims them via
 * releaseCanvasesTo() when the overlay closes.
 *
 * Interaction:
 *  - Swipe left/right (drag or fling) to page between screens; releases snap to the nearest one.
 *  - Tapping the centered (fully focused) card fires [Listener.onScreenPicked] - SketchActivity
 *    switches to it and zooms back in. Tapping a side card just brings it to center instead.
 *  - Pinching outward (a zoom-in gesture) on the overlay fires [Listener.onExitRequested] with a
 *    scale derived from how far the pinch opened, so the gesture reads as "zooming back into"
 *    whatever screen was active before the overlay opened.
 *
 * This view owns 100% of its own touch input (see onInterceptTouchEvent) so the reparented
 * SketchCanvasView children - which would otherwise treat swipes as pans/selections - never see
 * raw touch events while embedded here.
 */
class ScreenCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        /** The centered card was tapped - switch to and zoom back into this screen. */
        fun onScreenPicked(pageId: Long)

        /** Pinched open without picking a screen - just restore the previous view, zoomed to
         *  roughly [resumeScale] (already clamped to the canvas's own zoom range downstream). */
        fun onExitRequested(resumeScale: Float)
    }

    var listener: Listener? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cardWidthFraction = 0.6f
    private val cardSpacingPx = dp(22f)
    private val cardCornerRadiusPx = dp(22f)
    private val labelGapPx = dp(10f)
    private val sideCardScale = 0.86f
    private val sideCardAlpha = 0.5f
    private val flingVelocityThresholdPxPerSec = dp(600f)
    private val pinchExitThreshold = 1.16f
    private val touchSlopPx = dp(8f)

    private data class Card(
        val pageId: Long,
        val cell: LinearLayout,
        val holder: FrameLayout,
        val canvasView: SketchCanvasView,
        val label: TextView,
    )

    private val track = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private var cards: List<Card> = emptyList()
    private var pitchPx = 0f
    private var settledIndex = 0
    private var trackAnimator: ValueAnimator? = null

    private var dragging = false
    private var dragStartX = 0f
    private var dragStartTranslation = 0f
    private var totalMovePx = 0f
    private var velocityTracker: VelocityTracker? = null

    private var pinchCumulativeScale = 1f
    private var pinchExitFired = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchCumulativeScale = 1f
                pinchExitFired = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchCumulativeScale *= detector.scaleFactor
                if (!pinchExitFired && pinchCumulativeScale >= pinchExitThreshold) {
                    pinchExitFired = true
                    val resumeScale = (0.5f * pinchCumulativeScale).coerceIn(0.55f, 1f)
                    listener?.onExitRequested(resumeScale)
                }
                return true
            }
        },
    )

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        addView(
            track,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ),
        )
    }

    /**
     * Populates the strip: one card per entry in [pages], [canvasOf] supplying (and implicitly
     * detaching from canvasArea) each screen's live SketchCanvasView. Deferred to the next
     * layout pass if this view hasn't been measured yet (its width/height are needed to size the
     * cards), same pattern as SketchActivity's own initialCanvas.post{} in onCreate.
     */
    fun bind(pages: List<ScreenPage>, initialPageId: Long, canvasOf: (ScreenPage) -> SketchCanvasView) {
        if (width == 0 || height == 0) {
            post { bind(pages, initialPageId, canvasOf) }
            return
        }
        trackAnimator?.cancel()
        track.removeAllViews()
        pinchExitFired = false

        val naturalW = width
        val naturalH = height
        val cardW = width * cardWidthFraction
        val cardH = cardW * (naturalH.toFloat() / naturalW.toFloat())
        pitchPx = cardW + cardSpacingPx
        val sidePadPx = ((width - cardW) / 2f).roundToInt()
        track.setPadding(sidePadPx, 0, sidePadPx, 0)

        val built = mutableListOf<Card>()
        pages.forEachIndexed { index, page ->
            val canvasView = canvasOf(page)
            (canvasView.parent as? ViewGroup)?.removeView(canvasView)
            canvasView.layoutParams = FrameLayout.LayoutParams(naturalW, naturalH)
            canvasView.pivotX = 0f
            canvasView.pivotY = 0f
            canvasView.translationX = 0f
            canvasView.translationY = 0f
            canvasView.scaleX = cardW / naturalW.toFloat()
            canvasView.scaleY = cardH / naturalH.toFloat()
            canvasView.visibility = View.VISIBLE

            val holder = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(cardW.roundToInt(), cardH.roundToInt())
                clipChildren = true
                setBackgroundColor(0xFF1E1E1E.toInt())
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cardCornerRadiusPx)
                    }
                }
                clipToOutline = true
                elevation = dp(6f)
                addView(canvasView)
            }

            val label = TextView(context).apply {
                text = page.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
            }

            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(cardW.roundToInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = if (index == 0) 0 else cardSpacingPx.roundToInt()
                }
                addView(holder)
                addView(label)
            }
            (label.layoutParams as? LinearLayout.LayoutParams)?.topMargin = labelGapPx.roundToInt()

            track.addView(cell)
            built.add(Card(page.id, cell, holder, canvasView, label))
        }
        cards = built

        settledIndex = pages.indexOfFirst { it.id == initialPageId }.coerceAtLeast(0)
        track.translationX = -(settledIndex * pitchPx)
        refreshCardVisualStates(settledIndex.toFloat())
    }

    /** Hands every reparented SketchCanvasView back via [reattach] (pageId, view) and clears the
     *  strip. Call before hiding this overlay. */
    fun releaseCanvasesTo(reattach: (Long, SketchCanvasView) -> Unit) {
        trackAnimator?.cancel()
        cards.forEach { card ->
            card.canvasView.scaleX = 1f
            card.canvasView.scaleY = 1f
            card.canvasView.pivotX = 0f
            card.canvasView.pivotY = 0f
            (card.canvasView.parent as? ViewGroup)?.removeView(card.canvasView)
            reattach(card.pageId, card.canvasView)
        }
        cards = emptyList()
        track.removeAllViews()
    }

    // Owns every touch event itself so the reparented SketchCanvasView children never see raw
    // input while embedded here (they'd otherwise interpret swipes as pans/marquee-selects).
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            dragging = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackAnimator?.cancel()
                dragging = true
                dragStartX = event.x
                dragStartTranslation = track.translationX
                totalMovePx = 0f
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging || cards.isEmpty()) return true
                velocityTracker?.addMovement(event)
                val dx = event.x - dragStartX
                totalMovePx = abs(dx)
                val minTranslation = -(pitchPx * (cards.size - 1))
                track.translationX = (dragStartTranslation + dx).coerceIn(minTranslation, 0f)
                refreshCardVisualStates(currentIndexFloat())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && cards.isNotEmpty()) {
                        velocityTracker?.addMovement(event)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.xVelocity ?: 0f
                        if (totalMovePx < touchSlopPx) {
                            handleTap(event.x, event.y)
                        } else {
                            resolveSwipe(vx)
                        }
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private fun currentIndexFloat(): Float = if (pitchPx > 0f) -track.translationX / pitchPx else 0f

    private fun refreshCardVisualStates(focusIndex: Float) {
        cards.forEachIndexed { i, card ->
            val dist = abs(i - focusIndex).coerceIn(0f, 1f)
            val scale = 1f - (1f - sideCardScale) * dist
            val alpha = 1f - (1f - sideCardAlpha) * dist
            card.holder.scaleX = scale
            card.holder.scaleY = scale
            card.holder.alpha = alpha
            card.label.alpha = alpha
        }
    }

    private fun resolveSwipe(velocityXPxPerSec: Float) {
        val nearest = currentIndexFloat().roundToInt()
        val target = if (abs(velocityXPxPerSec) > flingVelocityThresholdPxPerSec) {
            nearest + if (velocityXPxPerSec < 0) 1 else -1
        } else {
            nearest
        }
        settleToIndex(target.coerceIn(0, cards.size - 1))
    }

    private fun settleToIndex(index: Int) {
        settledIndex = index
        val target = -(index * pitchPx)
        trackAnimator?.cancel()
        trackAnimator = ValueAnimator.ofFloat(track.translationX, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                track.translationX = it.animatedValue as Float
                refreshCardVisualStates(currentIndexFloat())
            }
            start()
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (cards.isEmpty()) return
        if (y < track.top || y > track.top + track.height) return
        val contentX = x - track.translationX
        val idx = cards.indexOfFirst { contentX >= it.cell.left && contentX <= it.cell.right }
        if (idx < 0) return
        if (idx == settledIndex) {
            listener?.onScreenPicked(cards[idx].pageId)
        } else {
            settleToIndex(idx)
        }
    }
}
