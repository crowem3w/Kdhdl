package org.example.syncora.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * A pill-shaped toggle button with an animated "specular" rim highlight that sweeps
 * around the border and steers toward the touch/pointer position - a Kotlin/View port
 * of the React Bits <SpecularButton/> component, used here for the agent Pause/Resume
 * control at the bottom of [AgentStatePanelView].
 *
 * On API 33+ the rim is rendered with an AGSL [android.graphics.RuntimeShader] using
 * almost the same rounded-rect SDF and elliptical-normal rim math as the original GLSL
 * fragment shader. Below API 33 (this app's minSdk is 30) it falls back to a rotating
 * [SweepGradient] stroke that approximates the same look without a fragment shader.
 *
 * This view never decides the paused/running state itself: a tap invokes [onToggle],
 * and the caller updates [isPaused] once the underlying agent has actually been
 * paused/resumed, so the control always reflects ground truth rather than optimistic UI.
 */
class SpecularToggleButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val IDLE_SPEED = 0.35f // rad/sec sweep while idle, matches the original `speed` default
        const val ANGLE_EASE = 7f
        const val BRIGHT_EASE = 8f
        const val SHINE_SIZE_DEG = 12f
        const val SHINE_FADE_DEG = 46f
        const val PROXIMITY_DP = 120f

        const val SHADER_SRC = """
uniform float2 uCenter;
uniform float2 uHalfSize;
uniform float uRadius;
uniform float uAngle;
uniform float uPx;
uniform float3 uLineColor;
uniform float3 uBaseColor;
uniform float uIntensity;
uniform float uShineSize;
uniform float uShineFade;
uniform float uThickness;
uniform float uBaseWidth;

float sdRoundedRect(float2 p, float2 b, float r) {
  float2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float gaussianLine(float d, float sigma) {
  float x = d / (sigma + 1e-6);
  float k = mix(1.0, 1.6, smoothstep(0.0, 1.5, x));
  return exp(-k * x * x);
}

half4 main(float2 fragCoord) {
  float2 p = fragCoord - uCenter;
  float d = sdRoundedRect(p, uHalfSize, uRadius);
  float2 L = float2(cos(uAngle), sin(uAngle));

  float base = (1.0 - smoothstep(0.0, uBaseWidth, abs(d))) * 0.45;

  float2 nEll = normalize(p / (uHalfSize * uHalfSize) + 1e-6);
  float phi = acos(clamp(abs(dot(nEll, L)), 0.0, 1.0));
  float rim = 1.0 - smoothstep(uShineSize - uShineFade, uShineSize + uShineFade + 1e-4, phi);
  float line = gaussianLine(d, uThickness);
  float edgeClamp = 1.0 - smoothstep(0.5 * uPx, 3.0 * uPx, abs(d));
  float hi = line * rim * edgeClamp * uIntensity;

  float3 col = uBaseColor * base + uLineColor * hi;
  float a = clamp(base + hi, 0.0, 1.0);
  return half4(half3(col * a), half(a));
}
"""
    }

    /** True while the agent is paused; flips the label and accent color. Caller-driven only. */
    var isPaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** Fired on tap. The caller decides whether the toggle actually succeeds and sets [isPaused]. */
    var onToggle: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cornerRadiusPx = dp(14f)
    private val strokeWidthPx = dp(1.4f)
    private val paddingHPx = dp(26f)
    private val paddingVPx = dp(12f)

    private val fillColor = Color.parseColor("#131722")
    private val baseStrokeColor = Color.parseColor("#2A3542")
    private val runningColor = Color.parseColor("#26A69A") // agent active -> button reads "Pause Agent"
    private val pausedColor = Color.parseColor("#F0B90B")  // agent paused -> button reads "Resume Agent"

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor; style = Paint.Style.FILL }
    private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = baseStrokeColor
    }

    /** Used on API 33+: RuntimeShader paints the whole fill area; the shader itself draws only the ring. */
    private val shaderFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Used below API 33: an actual stroked ring colored by a rotating SweepGradient. */
    private val sweepStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx * 1.6f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAECEF")
        textSize = dp(13.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val rect = RectF()

    // ---- animated state, advanced once per frame by the choreographer loop ----
    private var angle = 2.4f
    private var idleAngle = 2.4f
    private var bright = 0f
    private var pointerAngle: Float? = null
    private var proximityT = 0f
    private var lastFrameNanos = 0L

    private val runtimeShader: android.graphics.RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.graphics.RuntimeShader(SHADER_SRC)
        } else {
            null
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameNanos = frameTimeNanos

            idleAngle += IDLE_SPEED * dt
            val steer = pointerAngle != null && proximityT > 0f
            val target = if (steer) pointerAngle!! else idleAngle
            val twoPi = (PI * 2).toFloat()
            val diff = ((target - angle + PI.toFloat() * 3f) % twoPi) - PI.toFloat()
            angle += diff * (1f - exp(-dt * ANGLE_EASE))

            bright += (proximityT - bright) * (1f - exp(-dt * BRIGHT_EASE))

            invalidate()
            if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Pause or resume agent"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = labelFor(isPaused)
        val textWidth = textPaint.measureText(label)
        val desiredW = (textWidth + paddingHPx * 2f).toInt()
        val fm = textPaint.fontMetrics
        val desiredH = ((fm.descent - fm.ascent) + paddingVPx * 2f).toInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val inset = strokeWidthPx
        rect.set(inset, inset, w - inset, h - inset)
        val radius = min(cornerRadiusPx, min(rect.width(), rect.height()) / 2f)

        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, baseStrokePaint)

        val lineColor = if (isPaused) pausedColor else runningColor
        if (bright > 0.01f) drawShine(canvas, radius, lineColor)

        val label = labelFor(isPaused)
        val fm = textPaint.fontMetrics
        val textY = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, w / 2f, textY, textPaint)
    }

    private fun labelFor(paused: Boolean) = if (paused) "Resume Agent" else "Pause Agent"

    private fun drawShine(canvas: Canvas, radius: Float, lineColor: Int) {
        val shader = runtimeShader
        if (shader != null) {
            shader.setFloatUniform("uCenter", (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
            shader.setFloatUniform("uHalfSize", rect.width() / 2f, rect.height() / 2f)
            shader.setFloatUniform("uRadius", radius)
            shader.setFloatUniform("uAngle", angle)
            shader.setFloatUniform("uPx", density)
            shader.setFloatUniform("uLineColor", Color.red(lineColor) / 255f, Color.green(lineColor) / 255f, Color.blue(lineColor) / 255f)
            shader.setFloatUniform(
                "uBaseColor",
                Color.red(baseStrokeColor) / 255f,
                Color.green(baseStrokeColor) / 255f,
                Color.blue(baseStrokeColor) / 255f,
            )
            shader.setFloatUniform("uIntensity", bright)
            shader.setFloatUniform("uShineSize", SHINE_SIZE_DEG * (PI.toFloat() / 180f))
            shader.setFloatUniform("uShineFade", SHINE_FADE_DEG * (PI.toFloat() / 180f))
            shader.setFloatUniform("uThickness", strokeWidthPx * 1.5f)
            shader.setFloatUniform("uBaseWidth", density)

            shaderFillPaint.shader = shader
            canvas.drawRoundRect(rect, radius, radius, shaderFillPaint)
        } else {
            // Fallback for API 30-32: a rotating SweepGradient stroke approximates the same rim
            // highlight - two symmetric bright bands 180 degrees apart, matching the
            // abs(dot(normal, light)) symmetry of the original shader.
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f
            val r = Color.red(lineColor); val g = Color.green(lineColor); val b = Color.blue(lineColor)
            val dim = Color.argb((30 * bright).toInt().coerceIn(0, 255), r, g, b)
            val hot = Color.argb((235 * bright).toInt().coerceIn(0, 255), r, g, b)
            val sweep = SweepGradient(
                cx, cy,
                intArrayOf(dim, hot, dim, dim, hot, dim, dim),
                floatArrayOf(0f, 0.05f, 0.12f, 0.5f, 0.55f, 0.62f, 1f),
            )
            sweep.setLocalMatrix(Matrix().apply { postRotate(Math.toDegrees(angle.toDouble()).toFloat(), cx, cy) })
            sweepStrokePaint.shader = sweep
            canvas.drawRoundRect(rect, radius, radius, sweepStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updatePointerAngle(event.x, event.y)
                proximityT = 1f
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updatePointerAngle(event.x, event.y)
                proximityT = 1f
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                proximityT = 0f
                if (event.x >= 0f && event.y >= 0f && event.x <= width && event.y <= height) {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                proximityT = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val proximityPx = dp(PROXIMITY_DP)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                updatePointerAngle(event.x, event.y)
                val dx = if (event.x < 0f) -event.x else if (event.x > width) event.x - width else 0f
                val dy = if (event.y < 0f) -event.y else if (event.y > height) event.y - height else 0f
                val dist = hypot(dx, dy)
                val t = (1f - dist / proximityPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                proximityT = t * t * (3f - 2f * t)
            }
            MotionEvent.ACTION_HOVER_EXIT -> proximityT = 0f
        }
        return super.onHoverEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onToggle?.invoke()
        return true
    }

    private fun updatePointerAngle(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val nx = (x - w / 2f) / (w / 2f)
        val ny = (h / 2f - y) / (h / 2f)
        pointerAngle = atan2(2f / h, -2f / w) + nx * 0.3f + ny * 0.15f
    }
}
