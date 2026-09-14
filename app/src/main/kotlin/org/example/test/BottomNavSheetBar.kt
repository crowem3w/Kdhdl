package org.example.test

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/**
 * The Sketch editor's bottom tool bar (Select/Pages/Text/Upload/Elements), rendered as a
 * pill-shaped, floating, physically-thick "sheet" whose upper contour dents inward around
 * whichever tab is currently selected - as though that tab's frame were being pushed up
 * through a thin flexible membrane from underneath, leaving a shallow bowl-shaped dimple in
 * the sheet around its base while the frame itself floats independently above it. The frame's
 * own elevation/translation lives in [SketchActivity] (it's a perfectly normal elevated child
 * view), driven in lock-step with this view's dimple via [onSelectionProgress] so both halves
 * of the illusion move together.
 *
 * Replaces the old static bg_bottom_nav_pill background drawable: everything here is painted
 * in [onDraw], which - because this is a ViewGroup - runs *before* [dispatchDraw] paints the
 * tab columns and their elevation shadows. That gives the desired stacking for free:
 * background -> bent sheet -> elevated selected frame, in natural draw order, with no extra
 * layering work needed.
 *
 * The bottom ~6dp "edge" slab (the sheet's visible side-wall, which is what originally sold
 * its sense of physical thickness) is left completely static - only the top face's contour is
 * ever deformed, so the pill's overall silhouette is preserved exactly as before.
 */
class BottomNavSheetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val edgeThicknessPx = dp(6f)
    private val hugeCornerPx = dp(999f)

    private val dipDepthPx = dp(10f)
    private val dipHalfWidthFallbackPx = dp(38f)
    private val dipHalfWidthPaddingPx = dp(8f) // the dimple is a bit wider than the pill it cradles

    private var activeIndex = 0
    private var dipCenterX = 0f
    private var dipHalfWidth = dipHalfWidthFallbackPx
    private var geometryReady = false

    private var selectionAnimator: ValueAnimator? = null

    /**
     * Fired on every frame of a selection change so the Activity can move the (elevated,
     * independently-drawn) tab frames in perfect lockstep with the dimple beneath them.
     * `t` is intentionally NOT clamped to [0,1] - it carries the OvershootInterpolator's small
     * overshoot through so the frame lift/descent, its color fade, and the dimple all bounce
     * together instead of the dimple settling early.
     */
    var onSelectionProgress: ((oldIndex: Int, newIndex: Int, t: Float) -> Unit)? = null

    // --- Paint --------------------------------------------------------

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A10") }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#33343F")
    }
    private val dimpleShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
        color = Color.parseColor("#4A4B58")
        strokeCap = Paint.Cap.ROUND
    }

    private val facePath = Path()
    private val dipCurvePath = Path() // just the deformed segment, reused for the rim highlight
    private val edgeRect = RectF()
    private val faceRect = RectF()
    private val cornerOval = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val faceBottom = h - edgeThicknessPx
        facePaint.shader = LinearGradient(
            0f, 0f, 0f, faceBottom,
            intArrayOf(Color.parseColor("#24252F"), Color.parseColor("#1A1B24"), Color.parseColor("#131319")),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        if (!geometryReady) {
            val cx = pillCenterX(activeIndex)
            if (cx > 0f) {
                dipCenterX = cx
                dipHalfWidth = pillHalfWidth(activeIndex)
            } else {
                dipCenterX = w / 2f
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Keep the resting dimple locked under the active tab across any layout pass that
        // isn't itself part of an in-flight selection-change animation (rotation, the very
        // first layout pass, etc.) - a live animation owns dipCenterX/dipHalfWidth on its own.
        if (selectionAnimator?.isRunning != true) {
            val cx = pillCenterX(activeIndex)
            if (cx > 0f) {
                dipCenterX = cx
                dipHalfWidth = pillHalfWidth(activeIndex)
                geometryReady = true
                invalidate()
            }
        }
    }

    // --- Public API -----------------------------------------------------

    /**
     * Selects tab [index]. When [animate] is true (the normal case), the dimple travels from
     * its current position to the new tab's position over a single spring-like animation, and
     * [onSelectionProgress] fires every frame with the shared progress `t` so the Activity can
     * raise the new tab's frame and lower the old one in sync.
     */
    fun setActiveTabIndex(index: Int, animate: Boolean = true) {
        if (index == activeIndex && geometryReady) return
        val oldIndex = activeIndex
        activeIndex = index

        val oldCenter = if (geometryReady) dipCenterX else pillCenterX(oldIndex)
        val oldHalf = if (geometryReady) dipHalfWidth else pillHalfWidth(oldIndex)
        val newCenter = pillCenterX(index)
        val newHalf = pillHalfWidth(index)

        selectionAnimator?.cancel()

        if (!animate || newCenter <= 0f) {
            if (newCenter > 0f) {
                dipCenterX = newCenter
                dipHalfWidth = newHalf
                geometryReady = true
            } else if (oldCenter > 0f) {
                dipCenterX = oldCenter
                dipHalfWidth = oldHalf
            }
            invalidate()
            onSelectionProgress?.invoke(oldIndex, index, 1f)
            return
        }

        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener {
                val t = it.animatedValue as Float
                dipCenterX = oldCenter + (newCenter - oldCenter) * t
                dipHalfWidth = oldHalf + (newHalf - oldHalf) * t
                geometryReady = true
                invalidate()
                onSelectionProgress?.invoke(oldIndex, index, t)
            }
            start()
        }
    }

    // --- Geometry helpers -------------------------------------------------

    /**
     * Center-x, in this view's own coordinate space, of the tab pill at [index] (the small
     * rounded-square frame nested one level inside the tab column child at that index).
     * Returns 0f if that part of the hierarchy isn't laid out yet.
     */
    private fun pillCenterX(index: Int): Float {
        val column = getChildAt(index) as? LinearLayout ?: return 0f
        val pill = column.getChildAt(0) ?: return 0f
        if (pill.width <= 0) return 0f
        return column.left + pill.left + pill.width / 2f
    }

    private fun pillHalfWidth(index: Int): Float {
        val column = getChildAt(index) as? LinearLayout ?: return dipHalfWidthFallbackPx
        val pill = column.getChildAt(0) ?: return dipHalfWidthFallbackPx
        if (pill.width <= 0) return dipHalfWidthFallbackPx
        return pill.width / 2f + dipHalfWidthPaddingPx
    }

    // --- Drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Static side-wall/edge slab - untouched by the dimple, keeps the pill's silhouette
        // intact from the waist down.
        edgeRect.set(0f, edgeThicknessPx, w, h)
        val edgeRadius = min(hugeCornerPx, edgeRect.height() / 2f)
        canvas.drawRoundRect(edgeRect, edgeRadius, edgeRadius, edgePaint)

        // Deformable face/top surface.
        val faceBottom = h - edgeThicknessPx
        faceRect.set(0f, 0f, w, faceBottom)
        val r = min(hugeCornerPx, faceRect.height() / 2f)
        buildFacePath(faceRect, r)
        canvas.drawPath(facePath, facePaint)
        canvas.drawPath(facePath, faceStroke)

        if (geometryReady) {
            canvas.save()
            canvas.clipPath(facePath)
            drawDimpleShading(canvas)
            drawContactShadow(canvas)
            canvas.restore()
            // Rim highlight along the curve itself - drawn unclipped so the stroke isn't
            // trimmed right at the silhouette edge.
            canvas.drawPath(dipCurvePath, rimHighlightPaint)
        }
    }

    private fun buildFacePath(rect: RectF, r: Float) {
        facePath.reset()
        dipCurvePath.reset()

        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom

        val dipLeft = max(left + r, dipCenterX - dipHalfWidth)
        val dipRight = min(right - r, dipCenterX + dipHalfWidth)
        val dipUsable = geometryReady && dipRight - dipLeft > dp(4f)

        facePath.moveTo(left + r, top)

        if (dipUsable) {
            facePath.lineTo(dipLeft, top)
            dipCurvePath.moveTo(dipLeft, top)

            val bottomY = top + dipDepthPx
            val leftCtrlSpan = (dipCenterX - dipLeft) * 0.55f
            val rightCtrlSpan = (dipRight - dipCenterX) * 0.55f

            // Left half of the bowl: the flat edge dips away from the button, curving down
            // toward the centre with a flat tangent at both ends (no sharp corners).
            facePath.cubicTo(
                dipLeft + leftCtrlSpan, top,
                dipCenterX - leftCtrlSpan, bottomY,
                dipCenterX, bottomY,
            )
            dipCurvePath.cubicTo(
                dipLeft + leftCtrlSpan, top,
                dipCenterX - leftCtrlSpan, bottomY,
                dipCenterX, bottomY,
            )
            // Right half: mirrors back up, rejoining the flat edge just as smoothly.
            facePath.cubicTo(
                dipCenterX + rightCtrlSpan, bottomY,
                dipRight - rightCtrlSpan, top,
                dipRight, top,
            )
            dipCurvePath.cubicTo(
                dipCenterX + rightCtrlSpan, bottomY,
                dipRight - rightCtrlSpan, top,
                dipRight, top,
            )
            facePath.lineTo(right - r, top)
        } else {
            facePath.lineTo(right - r, top)
        }

        cornerOval.set(right - 2 * r, top, right, top + 2 * r)
        facePath.arcTo(cornerOval, -90f, 90f, false)
        facePath.lineTo(right, bottom - r)
        cornerOval.set(right - 2 * r, bottom - 2 * r, right, bottom)
        facePath.arcTo(cornerOval, 0f, 90f, false)
        facePath.lineTo(left + r, bottom)
        cornerOval.set(left, bottom - 2 * r, left + 2 * r, bottom)
        facePath.arcTo(cornerOval, 90f, 90f, false)
        facePath.lineTo(left, top + r)
        cornerOval.set(left, top, left + 2 * r, top + 2 * r)
        facePath.arcTo(cornerOval, 180f, 90f, false)
        facePath.close()
    }

    /** Soft inner-shadow shading that darkens the floor of the dimple, selling concave depth
     * rather than a flat cut-out. Must be called inside a canvas clipped to [facePath]. */
    private fun drawDimpleShading(canvas: Canvas) {
        val cy = dipDepthPx
        val radius = dipHalfWidth * 1.35f
        dimpleShadePaint.shader = RadialGradient(
            dipCenterX, cy, radius,
            intArrayOf(0x66000000, 0x2E000000, 0x00000000),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(dipCenterX, cy, radius, dimpleShadePaint)
    }

    /** A tighter, darker blob directly beneath where the elevated frame floats, so the frame
     * reads as grounded above the dimple rather than simply hovering unrelated to the sheet.
     * Must be called inside a canvas clipped to [facePath]. */
    private fun drawContactShadow(canvas: Canvas) {
        val cy = dipDepthPx * 0.9f
        val radius = dipHalfWidth * 0.85f
        contactShadowPaint.shader = RadialGradient(
            dipCenterX, cy, radius,
            intArrayOf(0x59000000, 0x24000000, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(dipCenterX, cy, radius, contactShadowPaint)
    }
}
