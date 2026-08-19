package org.example.test.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.roundToInt

class NeumorphicPillDrawable(
    private val density: Float,
    var selected: Boolean = false,
    private val haloDp: Float = 10f,
) : Drawable() {

    private val cornerRadiusDp = 32f
    private val activeSurfaceColor = Color.parseColor("#102A2B")
    private val inactiveSurfaceColor = Color.TRANSPARENT

    private val highlightAlpha = pctToAlpha(0.08f)
    private val highlightOffsetDp = -1f
    private val highlightBlurDp = 18f

    private val shadowAlpha = pctToAlpha(0.20f)
    private val shadowOffsetDp = 4f
    private val shadowBlurDp = 20f
    private val shadowSpreadDp = -4f

    private val aoAlpha = pctToAlpha(0.12f)
    private val aoBlurDp = 12f

    private val glowAlpha = pctToAlpha(0.05f)
    private val glowBlurDp = 24f

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (selected) activeSurfaceColor else inactiveSurfaceColor
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = glowAlpha
        maskFilter = BlurMaskFilter(blurRadiusPx(glowBlurDp), BlurMaskFilter.Blur.NORMAL)
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = shadowAlpha
        maskFilter = BlurMaskFilter(blurRadiusPx(shadowBlurDp), BlurMaskFilter.Blur.NORMAL)
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = highlightAlpha
        maskFilter = BlurMaskFilter(blurRadiusPx(highlightBlurDp), BlurMaskFilter.Blur.NORMAL)
    }

    private val aoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.px()
        color = Color.BLACK
        alpha = aoAlpha
        maskFilter = BlurMaskFilter(blurRadiusPx(aoBlurDp), BlurMaskFilter.Blur.NORMAL)
    }

    private val scratch = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val halo = haloDp.px()
        val surfaceLeft = b.left + halo
        val surfaceTop = b.top + halo
        val surfaceRight = b.right - halo
        val surfaceBottom = b.bottom - halo
        if (surfaceRight <= surfaceLeft || surfaceBottom <= surfaceTop) return

        if (!selected) return

        val cornerPx = cornerRadiusDp.px()
        val offsetPx = shadowOffsetDp.px()
        val highlightOffsetPx = highlightOffsetDp.px()
        val spreadPx = shadowSpreadDp.px()

        val shadowDx = -offsetPx
        val shadowDy = -offsetPx
        val highlightDy = -highlightOffsetPx

        scratch.set(surfaceLeft, surfaceTop, surfaceRight, surfaceBottom)
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, glowPaint)

        scratch.set(
            surfaceLeft - spreadPx + shadowDx,
            surfaceTop - spreadPx + shadowDy,
            surfaceRight + spreadPx + shadowDx,
            surfaceBottom + spreadPx + shadowDy,
        )
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, shadowPaint)

        scratch.set(surfaceLeft, surfaceTop + highlightDy, surfaceRight, surfaceBottom + highlightDy)
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, highlightPaint)

        scratch.set(surfaceLeft, surfaceTop, surfaceRight, surfaceBottom)
        surfacePaint.color = activeSurfaceColor
        canvas.drawRoundRect(scratch, cornerPx, cornerPx, surfacePaint)

        val aoInset = aoPaint.strokeWidth
        scratch.set(
            surfaceLeft + aoInset,
            surfaceTop + aoInset,
            surfaceRight - aoInset,
            surfaceBottom - aoInset,
        )
        canvas.drawRoundRect(scratch, cornerPx - aoInset, cornerPx - aoInset, aoPaint)
    }

    override fun setAlpha(alpha: Int) {

    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        surfacePaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun Float.px(): Float = this * density

    private fun blurRadiusPx(blurDp: Float): Float = (blurDp.px() / 2f).coerceAtLeast(0.1f)

    private fun pctToAlpha(pct: Float): Int = (pct * 255f).roundToInt().coerceIn(0, 255)

    companion object {

        fun applyTo(view: View, drawable: NeumorphicPillDrawable) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.background = drawable
        }
    }
}
