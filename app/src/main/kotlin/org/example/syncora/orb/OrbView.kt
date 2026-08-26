package org.example.syncora.orb

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android/Kotlin port of the React "Orb" background component (ogl + GLSL).
 *
 * Usage (mirrors the React props):
 * ```
 * orbView.hue = 0f
 * orbView.hoverIntensity = 0.5f
 * orbView.rotateOnHover = true
 * orbView.forceHoverState = false
 * orbView.saturation = 0f       // 0 = monochrome, 1 = original violet/cyan colors
 * orbView.setBackgroundColorHex("#000000")
 * ```
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = OrbRenderer()

    /** The base hue for the orb, in degrees. */
    var hue: Float
        get() = renderer.hue
        set(value) { renderer.hue = value }

    /** Controls the intensity of the hover/warp distortion effect. */
    var hoverIntensity: Float
        get() = renderer.hoverIntensity
        set(value) { renderer.hoverIntensity = value }

    /** Whether the orb spins continuously while "hovered". */
    var rotateOnHover: Boolean
        get() = renderer.rotateOnHover
        set(value) { renderer.rotateOnHover = value }

    /** Forces hover animations even when there's no active touch — useful for a background. */
    var forceHoverState: Boolean
        get() = renderer.forceHoverState
        set(value) { renderer.forceHoverState = value }

    /** 0 = fully desaturated/monochrome orb, 1 = the original violet/cyan palette. */
    var saturation: Float
        get() = renderer.saturation
        set(value) { renderer.saturation = value }

    /**
     * 0 = perfect circular orb, 1 = fully morphed into the §-shaped rim.
     * Set directly for a static shape, or drive it via [animateToSection] /
     * [animateToOrb] for the transition.
     */
    var sectionMorph: Float
        get() = renderer.sectionMorph
        set(value) { renderer.sectionMorph = value.coerceIn(0f, 1f) }

    private var morphAnimator: ValueAnimator? = null

    /** Animates from the current shape into the full § form. */
    fun animateToSection(durationMs: Long = 900L, onEnd: (() -> Unit)? = null) {
        animateMorphTo(1f, durationMs, onEnd)
    }

    /** Animates from the current shape back into a perfect orb. */
    fun animateToOrb(durationMs: Long = 900L, onEnd: (() -> Unit)? = null) {
        animateMorphTo(0f, durationMs, onEnd)
    }

    private fun animateMorphTo(target: Float, durationMs: Long, onEnd: (() -> Unit)?) {
        morphAnimator?.cancel()
        morphAnimator = ValueAnimator.ofFloat(sectionMorph, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { renderer.sectionMorph = it.animatedValue as Float }
            if (onEnd != null) {
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {}
                    override fun onAnimationEnd(animation: Animator) = onEnd()
                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                })
            }
            start()
        }
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setBackgroundColorHex(hex: String) {
        val color = Color.parseColor(hex)
        renderer.backgroundColor = floatArrayOf(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
        )
    }

    /** Sets the first of the two colors the orb's outer glow cycles/mixes between. */
    fun setColor1Hex(hex: String) {
        renderer.color1 = hexToFloatRgb(hex)
    }

    /** Sets the second of the two colors the orb's outer glow cycles/mixes between. */
    fun setColor2Hex(hex: String) {
        renderer.color2 = hexToFloatRgb(hex)
    }

    /** Sets the darker shadow-side color mixed in on the far side of the orb. */
    fun setColor3Hex(hex: String) {
        renderer.color3 = hexToFloatRgb(hex)
    }

    private fun hexToFloatRgb(hex: String): FloatArray {
        val color = Color.parseColor(hex)
        return floatArrayOf(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val size = min(width, height).toFloat()
                if (size <= 0f) return true
                val uvX = ((event.x - width / 2f) / size) * 2f
                val uvY = ((event.y - height / 2f) / size) * 2f
                renderer.targetHover = if (sqrt(uvX * uvX + uvY * uvY) < 0.8f) 1f else 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.targetHover = 0f
            }
        }
        return true
    }
}
