package org.example.syncora.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-bleed, edge-to-edge atmospheric backdrop meant to sit behind glass
 * panels (e.g. [TradingModeDialog]'s card). It is intentionally low-detail:
 * a near-black base, a handful of very soft, slowly drifting radial "light"
 * patches in charcoal / cool-gray tones, a faint grain layer for texture,
 * and a gentle vignette for depth. Nothing here is a focal object - the
 * goal is a quiet sense of depth and motion that reads through translucent
 * UI without competing with it.
 *
 * On API 31+ the whole view is additionally soft-blurred via [RenderEffect]
 * for a cinematic depth-of-field feel. On older devices the radial patches'
 * own soft falloff (no hard edges anywhere) stands in for that.
 */
class CinematicBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val CYCLE_MS = 52_000L
        const val BLUR_RADIUS_PX = 60f
        const val GRAIN_TILE_PX = 96
        const val GRAIN_ALPHA = 10

        val BASE_COLOR = Color.parseColor("#050506")
        val VIGNETTE_COLOR = Color.parseColor("#000000")

        // Each patch: fractional center, fractional radius, color, drift
        // amplitude (fraction of view size), and integer angular frequencies
        // so the whole loop is seamless across one animator repeat.
        data class Patch(
            val cx: Float,
            val cy: Float,
            val radius: Float,
            val color: Int,
            val ampX: Float,
            val ampY: Float,
            val freqX: Int,
            val freqY: Int,
            val phase: Float,
        )

        val PATCHES = listOf(
            Patch(0.28f, 0.22f, 0.55f, Color.parseColor("#1E2024"), 0.05f, 0.03f, 1, 2, 0f),
            Patch(0.75f, 0.18f, 0.42f, Color.parseColor("#26282D"), 0.04f, 0.05f, 2, 1, 1.9f),
            Patch(0.82f, 0.70f, 0.60f, Color.parseColor("#1A1B1F"), 0.03f, 0.04f, 1, 1, 3.4f),
            Patch(0.20f, 0.78f, 0.48f, Color.parseColor("#2E3136"), 0.05f, 0.03f, 2, 2, 5.1f),
            Patch(0.50f, 0.50f, 0.70f, Color.parseColor("#151619"), 0.02f, 0.02f, 1, 1, 0.8f),
        )
    }

    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = GRAIN_ALPHA }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BASE_COLOR }

    private var phase = 0f
    private var grainShader: BitmapShader? = null
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            grainShader = BitmapShader(
                buildGrainTile(),
                Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT,
            )
            grainPaint.shader = grainShader
            vignettePaint.shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(140, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP))
        }
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Near-black base - everything else is a soft overlay on top of this.
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Slow-drifting soft light patches; radial gradients fade fully to
        // transparent so there are no hard edges to read as "shapes".
        val dim = max(w, h)
        for (patch in PATCHES) {
            val driftX = patch.ampX * dim * sin(patch.freqX * phase + patch.phase)
            val driftY = patch.ampY * dim * cos(patch.freqY * phase + patch.phase)
            val cx = patch.cx * w + driftX
            val cy = patch.cy * h + driftY
            val radius = patch.radius * dim
            patchPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(patch.color, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, patchPaint)
        }

        // Faint grain for texture/aliveness - kept extremely subtle.
        canvas.drawRect(0f, 0f, w, h, grainPaint)

        // Gentle vignette for depth toward the edges.
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    /** One-off tileable noise bitmap; cheap to generate, reused every frame. */
    private fun buildGrainTile(): Bitmap {
        val bmp = Bitmap.createBitmap(GRAIN_TILE_PX, GRAIN_TILE_PX, Bitmap.Config.ARGB_8888)
        val rng = Random(1)
        for (y in 0 until GRAIN_TILE_PX) {
            for (x in 0 until GRAIN_TILE_PX) {
                val v = 60 + rng.nextInt(80) // muted cool-gray speckle range
                bmp.setPixel(x, y, Color.rgb(v, v, v + 4))
            }
        }
        return bmp
    }
}
