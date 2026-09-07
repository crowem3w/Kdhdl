package org.example.syncora.orb

import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.min
import kotlin.math.sqrt

class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = OrbRenderer()

    var hue: Float
        get() = renderer.hue
        set(value) { renderer.hue = value }

    var hoverIntensity: Float
        get() = renderer.hoverIntensity
        set(value) { renderer.hoverIntensity = value }

    var rotateOnHover: Boolean
        get() = renderer.rotateOnHover
        set(value) { renderer.rotateOnHover = value }

    var forceHoverState: Boolean
        get() = renderer.forceHoverState
        set(value) { renderer.forceHoverState = value }

    var saturation: Float
        get() = renderer.saturation
        set(value) { renderer.saturation = value }

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

    fun setColor1Hex(hex: String) {
        renderer.color1 = hexToFloatRgb(hex)
    }

    fun setColor2Hex(hex: String) {
        renderer.color2 = hexToFloatRgb(hex)
    }

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
