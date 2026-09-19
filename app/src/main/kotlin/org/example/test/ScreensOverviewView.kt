package org.example.test

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shown by SketchActivity when the sketch canvas is pinched out to its maximum zoom-out. Lays
 * every screen of the project out as one side-by-side row of snapshots on a black background:
 *
 *  - swipe left/right to move along the row (it snaps so one screen is always centered),
 *  - tap a screen (or its name) to open it,
 *  - spread two fingers to open whichever screen is currently centered.
 *
 * The view only draws pre-rendered snapshots (see SketchCanvasView.renderPageSnapshot) - it never
 * edits anything - and the bitmaps are handed over via setCards() and freed again by release().
 */
class ScreensOverviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** One screen in the row. [snapshot] is already scaled to its on-screen size. */
    class Card(val pageId: Long, val title: String, val snapshot: Bitmap?)

    interface Listener {
        fun onScreenTapped(pageId: Long)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val cardGap = 28f * density
    private val cardCornerRadius = 10f * density
    private val labelGap = 12f * density
    private val labelTextSize = 13f * density
    private val fallbackCardWidth = 180f * density
    private val fallbackCardHeight = 390f * density

    private var cards: List<Card> = emptyList()
    private var shaders: List<BitmapShader?> = emptyList()
    private var selectedId = -1L

    // Horizontal scroll position: 0 centers the first card, (cards.size - 1) * step the last.
    private var offsetX = 0f
    private var snapAnimator: ValueAnimator? = null
    private var flingHandled = false

    private val cardRect = RectF()
    private val shaderMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F3F4F6") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = labelTextSize
        textAlign = Paint.Align.CENTER
    }

    private val selectedBorderColor = Color.parseColor("#6750A4")
    private val idleBorderColor = Color.parseColor("#33FFFFFF")
    private val selectedLabelColor = Color.WHITE
    private val idleLabelColor = Color.parseColor("#B3FFFFFF")

    // ---- Layout helpers -------------------------------------------------------------------

    private val cardWidth: Float
        get() = cards.firstOrNull { it.snapshot != null }?.snapshot?.width?.toFloat() ?: fallbackCardWidth

    private fun cardHeight(index: Int): Float =
        cards[index].snapshot?.height?.toFloat() ?: fallbackCardHeight

    private val step: Float get() = cardWidth + cardGap
    private val startPad: Float get() = (width - cardWidth) / 2f
    private val maxOffset: Float get() = max(0f, (cards.size - 1) * step)
    private val labelBlockHeight: Float get() = labelGap + labelTextSize * 1.4f

    private fun cardLeft(index: Int): Float = startPad + index * step - offsetX

    // Cards are top-aligned to each other; the tallest one (plus its label) is centered vertically.
    private fun rowTop(): Float {
        var tallest = 0f
        for (i in cards.indices) tallest = max(tallest, cardHeight(i))
        return max(0f, (height - (tallest + labelBlockHeight)) / 2f)
    }

    private fun indexForOffset(offset: Float): Int =
        (offset / step).roundToInt().coerceIn(0, max(0, cards.size - 1))

    private fun centeredCard(): Card? = cards.getOrNull(indexForOffset(offsetX))

    private fun cardAt(x: Float, y: Float): Card? {
        val top = rowTop()
        val w = cardWidth
        for (i in cards.indices) {
            val left = cardLeft(i)
            if (x >= left && x <= left + w && y >= top && y <= top + cardHeight(i) + labelBlockHeight) {
                return cards[i]
            }
        }
        return null
    }

    // ---- Content --------------------------------------------------------------------------

    /** Replaces the row's content and centers [selectedPageId]. Takes ownership of the bitmaps. */
    fun setCards(newCards: List<Card>, selectedPageId: Long) {
        release()
        cards = newCards
        shaders = newCards.map { card ->
            card.snapshot?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
        selectedId = selectedPageId
        val index = newCards.indexOfFirst { it.pageId == selectedPageId }.coerceAtLeast(0)
        offsetX = (index * step).coerceIn(0f, maxOffset)
        invalidate()
    }

    /** Frees the snapshot bitmaps. Safe to call repeatedly. */
    fun release() {
        snapAnimator?.cancel()
        cards.forEach { it.snapshot?.recycle() }
        cards = emptyList()
        shaders = emptyList()
        invalidate()
    }

    // ---- Drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (cards.isEmpty()) return

        val w = cardWidth
        val top = rowTop()
        for (i in cards.indices) {
            val left = cardLeft(i)
            if (left + w < 0f || left > width) continue
            val h = cardHeight(i)
            val isSelected = cards[i].pageId == selectedId

            cardRect.set(left, top, left + w, top + h)
            val shader = shaders.getOrNull(i)
            if (shader != null) {
                shaderMatrix.setTranslate(left, top)
                shader.setLocalMatrix(shaderMatrix)
                bitmapPaint.shader = shader
                canvas.drawRoundRect(cardRect, cardCornerRadius, cardCornerRadius, bitmapPaint)
            } else {
                canvas.drawRoundRect(cardRect, cardCornerRadius, cardCornerRadius, placeholderPaint)
            }

            // Border sits just outside the card so it never eats into the snapshot.
            val strokeWidth = (if (isSelected) 2.5f else 1f) * density
            borderPaint.strokeWidth = strokeWidth
            borderPaint.color = if (isSelected) selectedBorderColor else idleBorderColor
            cardRect.inset(-strokeWidth / 2f, -strokeWidth / 2f)
            canvas.drawRoundRect(
                cardRect,
                cardCornerRadius + strokeWidth / 2f,
                cardCornerRadius + strokeWidth / 2f,
                borderPaint,
            )

            labelPaint.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            labelPaint.color = if (isSelected) selectedLabelColor else idleLabelColor
            val title = TextUtils.ellipsize(cards[i].title, labelPaint, w, TextUtils.TruncateAt.END).toString()
            canvas.drawText(title, left + w / 2f, top + h + labelGap - labelPaint.ascent(), labelPaint)
        }
    }

    // ---- Touch: swipe to scroll + snap, tap to open, spread to open the centered screen ---------

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var startSpan = 0f
            private var consumed = false

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startSpan = detector.currentSpan
                consumed = false
                snapAnimator?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!consumed && startSpan > 0f && detector.currentSpan / startSpan > SPREAD_TO_OPEN_RATIO) {
                    consumed = true
                    centeredCard()?.let { listener?.onScreenTapped(it.pageId) }
                }
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress) return true
                offsetX = (offsetX + distanceX).coerceIn(0f, maxOffset)
                invalidate()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (scaleDetector.isInProgress) return true
                flingHandled = true
                // Project a short distance ahead so a flick can carry past a neighbor, then snap.
                snapTo(indexForOffset(offsetX - velocityX * FLING_PROJECTION_SECONDS))
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                cardAt(e.x, e.y)?.let { listener?.onScreenTapped(it.pageId) }
                return true
            }
        },
    )

    private fun snapTo(index: Int) {
        val target = (index * step).coerceIn(0f, maxOffset)
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(offsetX, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                offsetX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            snapAnimator?.cancel()
            flingHandled = false
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (released && !flingHandled && !scaleDetector.isInProgress && cards.isNotEmpty()) {
            snapTo(indexForOffset(offsetX))
        }
        return true
    }

    override fun onDetachedFromWindow() {
        snapAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SNAP_DURATION_MS = 220L
        const val FLING_PROJECTION_SECONDS = 0.18f
        const val SPREAD_TO_OPEN_RATIO = 1.35f
    }
}
