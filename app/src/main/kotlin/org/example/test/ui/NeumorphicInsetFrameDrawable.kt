package org.example.test.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.roundToInt

/**
 * A "pressed-in" / concave neumorphic frame, the inverse of [NeumorphicPillDrawable]'s raised
 * pill. Instead of a convex surface catching a highlight on one edge and casting a shadow on the
 * other, this carves an indented well: a dark inner shadow on the light-facing edge and a soft
 * highlight on the opposite edge, so the icon reads as sunk into the surface.
 *
 * Intended for small icon-only controls (e.g. the double-chevron timeframe-expand button and the
 * drawing tools button) that only need this framing while selected/active.
 */
class NeumorphicInsetFrameDrawable(
    private val density: Float,
    var selected: Boolean = false,
    private val cornerRadiusDp: Float = 15f,
    private val haloDp: Float = 4f,
) : Drawable() {

    private val surfaceColor = Color.parseColor("#0B1418")

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surfaceColor
    }

    private val innerShadowAlpha = pctToAlpha(0.55f)
    private val innerShadowOffsetDp = 3f
    private val innerShadowBlurDp = 10f

    private val innerHighlightAlpha = pctToAlpha(0.10f)
    private val innerHighlightOffsetDp = 3f
    private val innerHighlightBlurDp = 10f

    private val edgeAlpha = pctToAlpha(0.55f)

    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = innerShadowAlpha
        maskFilter = BlurMaskFilter(blurRadiusPx(innerShadowBlurDp), BlurMaskFilter.Blur.NORMAL)
    }

    private val innerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = innerHighlightAlpha
        maskFilter = BlurMaskFilter(blurRadiusPx(innerHighlightBlurDp), BlurMaskFilter.Blur.NORMAL)
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.px()
        color = Color.BLACK
        alpha = edgeAlpha
    }

    private val clipPath = Path()
    private val scratch = RectF()

    override fun draw(canvas: Canvas) {
        if (!selected) return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val halo = haloDp.px()
        val left = b.left + halo
        val top = b.top + halo
        val right = b.right - halo
        val bottom = b.bottom - halo
        if (right <= left || bottom <= top) return

        val cornerPx = cornerRadiusDp.px()

        scratch.set(left, top, right, bottom)
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, surfacePaint)

        val saveCount = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(scratch, cornerPx, cornerPx, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val shadowOffsetPx = innerShadowOffsetDp.px()
        scratch.set(
            left - shadowOffsetPx,
            top - shadowOffsetPx,
            right - shadowOffsetPx,
            bottom - shadowOffsetPx,
        )
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, innerShadowPaint)

        val highlightOffsetPx = innerHighlightOffsetDp.px()
        scratch.set(
            left + highlightOffsetPx,
            top + highlightOffsetPx,
            right + highlightOffsetPx,
            bottom + highlightOffsetPx,
        )
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, innerHighlightPaint)

        canvas.restoreToCount(saveCount)

        scratch.set(left, top, right, bottom)
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, edgePaint)
    }

    override fun setAlpha(alpha: Int) {
        // No-op: opacity is controlled per-layer via the paints above.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        surfacePaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun Float.px(): Float = this * density

    private fun blurRadiusPx(blurDp: Float): Float = (blurDp.px() / 2f).coerceAtLeast(0.1f)

    private fun pctToAlpha(pct: Float): Int = (pct * 255f).roundToInt().coerceIn(0, 255)

    companion object {

        fun applyTo(view: View, drawable: NeumorphicInsetFrameDrawable) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.background = drawable
        }
    }
}
