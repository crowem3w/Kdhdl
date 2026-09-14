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
 * This view no longer hosts the tab columns itself - they live in a sibling `bottomNavTabs`
 * row that merely overlays this one (see activity_sketch.xml / SketchActivity). Keeping the
 * tabs OUT of this view's hierarchy matters because this view carries its own elevation/shadow
 * for the sheet's physical-thickness look, and the selected tab's frame is deliberately
 * elevated and translated well above its resting position - if that frame were a descendant of
 * this (elevated) ViewGroup, it would get clipped flush against this view's own top edge the
 * moment it rose past it. As a plain sibling instead, it can float freely above the sheet with
 * an uncropped drop shadow of its own. [tabColumns] is set from outside purely so this view can
 * still read pill positions/widths to place its dimple correctly.
 *
 * Replaces the old static bg_bottom_nav_pill background drawable: everything here is painted
 * in [onDraw].
 *
 * The bottom ~6dp "edge" slab (the sheet's visible side-wall, which is what originally sold
 * its sense of physical thickness) is left completely static - only the top face's contour is
 * ever deformed, so the pill's overall silhouette is preserved exactly as before.
 */
class BottomNavSheetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** The tab columns living in the sibling `bottomNavTabs` row, set once by SketchActivity
     * after that row is inflated. Used only to read pill positions/widths for the dimple - this
     * view no longer parents them (see class doc). */
    var tabColumns: List<LinearLayout> = emptyList()

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val edgeThicknessPx = dp(6f)
    private val hugeCornerPx = dp(999f)

    // Shallower than before (was 10dp): now that the selected frame floats on a real sibling
    // layer with its own elevation shadow (see class doc), the sheet no longer needs a deep
    // pocket to sell the illusion - just a soft, subtle give in the surface underneath it.
    private val dipDepthPx = dp(4f)
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
        syncRestingGeometry()
    }

    /**
     * Keep the resting dimple locked under the active tab across any layout pass that isn't
     * itself part of an in-flight selection-change animation (rotation, the very first layout
     * pass, etc.) - a live animation owns dipCenterX/dipHalfWidth on its own. Called from this
     * view's own [onLayout], and also by SketchActivity whenever the sibling `bottomNavTabs`
     * row (which actually owns [tabColumns]) re-lays-out, since that can happen independently
     * of this view's own layout passes now that the tabs aren't its children.
     */
    fun syncRestingGeometry() {
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
     * rounded-square frame nested one level inside the tab column at that index, read from
     * [tabColumns] - the sibling `bottomNavTabs` row's children, not this view's own).
     * [tabColumns] and this view are siblings of equal width with no independent horizontal
     * offset between them, so a column's `left` coordinate in its own parent is directly usable
     * in this view's coordinate space. Returns 0f if that part of the hierarchy isn't laid out
     * yet.
     */
    private fun pillCenterX(index: Int): Float {
        val column = tabColumns.getOrNull(index) ?: return 0f
        val pill = column.getChildAt(0) ?: return 0f
        if (pill.width <= 0) return 0f
        return column.left + pill.left + pill.width / 2f
    }

    private fun pillHalfWidth(index: Int): Float {
        val column = tabColumns.getOrNull(index) ?: return dipHalfWidthFallbackPx
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
            intArrayOf(0x4D000000, 0x22000000, 0x00000000),
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
