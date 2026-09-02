package org.example.syncora.chart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import org.example.syncora.bitget.DepthLevel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * "Orderbook Depth & Microstructure Liquidity Surface"
 *
 * Renders a rotatable / zoomable pseudo-3D mesh where:
 *   X = depth level, walking outward from the mid-price on the bid (left) and ask (right) sides
 *   Y = time, each row is one sub-second order-book sample (oldest -> back, newest -> front)
 *   Z = resting volume at that depth level in that sample
 *
 * The surface is built and touch-manipulated entirely with [Canvas] + manual 3D->2D projection
 * (rotation matrices + a light perspective term) so it has no extra rendering dependency.
 * Volume "cliffs" - a level whose size jumps well above its inward neighbour - are rimmed with a
 * bright highlight, since a sudden step in resting size is the visual signature of a spoofing
 * wall or an emerging support/resistance shelf.
 */
class OrderbookDepthSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------------------
    // Data model
    // ---------------------------------------------------------------------

    /** Depth levels rendered per side of the book (bid + ask), i.e. X-resolution. */
    private val levelsPerSide = 12

    /** Rows of sub-second history retained, i.e. Y-resolution. */
    private val maxHistoryRows = 40

    /** Minimum spacing between sampled rows - keeps the mesh on a genuine sub-second cadence. */
    private val minSampleIntervalMs = 130L

    private val columns = levelsPerSide * 2
    private val history = ArrayDeque<FloatArray>()
    private var lastSampleMs = 0L
    private var runningPeakVolume = 1f

    /** How many seconds of history the current row buffer spans, for the time-axis label. */
    private val historySpanSeconds: Float
        get() = (history.size * minSampleIntervalMs) / 1000f

    fun submitOrderBook(bids: List<DepthLevel>, asks: List<DepthLevel>, nowMs: Long = System.currentTimeMillis()) {
        if (bids.isEmpty() && asks.isEmpty()) return
        if (nowMs - lastSampleMs < minSampleIntervalMs) return
        lastSampleMs = nowMs

        val row = FloatArray(columns)
        val bidSide = bids.sortedByDescending { it.price }.take(levelsPerSide)
        val askSide = asks.sortedBy { it.price }.take(levelsPerSide)

        // Nearest-to-mid bid sits at column (levelsPerSide - 1), furthest at column 0.
        for (i in bidSide.indices) {
            row[levelsPerSide - 1 - i] = bidSide[i].size.toFloat()
        }
        // Nearest-to-mid ask sits at column levelsPerSide, furthest at the last column.
        for (i in askSide.indices) {
            row[levelsPerSide + i] = askSide[i].size.toFloat()
        }

        history.addLast(row)
        if (history.size > maxHistoryRows) history.removeFirst()

        val rowPeak = row.maxOrNull() ?: 0f
        // Slow decay so the colour scale settles rather than flickering with every tick,
        // but still climbs instantly to a fresh peak (e.g. a wall being dropped in).
        runningPeakVolume = max(runningPeakVolume * 0.985f, max(rowPeak, 1e-4f))

        if (!userHasInteracted) restartIdleSpin()
        invalidate()
    }

    fun clear() {
        history.clear()
        runningPeakVolume = 1f
        invalidate()
    }

    // ---------------------------------------------------------------------
    // Camera state (rotation + zoom)
    // ---------------------------------------------------------------------

    private var yawDeg = -35f
    private var pitchDeg = 26f
    private var zoom = 1f

    private val minPitch = 8f
    private val maxPitch = 82f
    private val minZoom = 0.55f
    private val maxZoom = 2.8f

    private var userHasInteracted = false
    private var idleSpinAnimator: ValueAnimator? = null

    private fun restartIdleSpin() {
        if (userHasInteracted) return
        idleSpinAnimator?.cancel()
        idleSpinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 24_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (userHasInteracted) return@addUpdateListener
                yawDeg = -35f + it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopIdleSpin() {
        userHasInteracted = true
        idleSpinAnimator?.cancel()
        idleSpinAnimator = null
    }

    fun resetCamera() {
        userHasInteracted = false
        yawDeg = -35f
        pitchDeg = 26f
        zoom = 1f
        restartIdleSpin()
        invalidate()
    }

    // ---------------------------------------------------------------------
    // Touch handling: one-finger drag rotates, pinch zooms, double-tap resets.
    // ---------------------------------------------------------------------

    private val rotateSensitivity = 0.35f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPinching = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                stopIdleSpin()
                isPinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinching = false
            }
        },
    )

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetCamera()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        doubleTapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                stopIdleSpin()
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPinching && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    yawDeg += dx * rotateSensitivity
                    pitchDeg = (pitchDeg - dy * rotateSensitivity).coerceIn(minPitch, maxPitch)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartIdleSpin()
    }

    override fun onDetachedFromWindow() {
        idleSpinAnimator?.cancel()
        idleSpinAnimator = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------------
    // Styling
    // ---------------------------------------------------------------------

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val backgroundPaint = Paint().apply { color = Color.parseColor("#0A0E14") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A3644")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
    }
    private val cliffRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDF4FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        alpha = 210
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val midPlanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EEAD4")
        style = Paint.Style.FILL
        alpha = 40
    }
    private val midPlaneEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EEAD4")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        alpha = 160
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A94A3")
        textSize = sp(10f)
    }
    private val titleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C7CED9")
        textSize = sp(10.5f)
        isFakeBoldText = true
    }
    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B6472")
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }

    // Purple -> Yellow -> Green liquidity gradient. Purple = thin/quiet depth,
    // yellow = building interest, green = heavy resting size / a wall.
    private val gradientStopPurple = intArrayOf(0x8B, 0x5C, 0xF6) // #8B5CF6
    private val gradientStopYellow = intArrayOf(0xFA, 0xCC, 0x15) // #FACC15
    private val gradientStopGreen = intArrayOf(0x22, 0xC5, 0x5E) // #22C55E

    private fun volumeColor(t: Float, alpha: Int): Int {
        val clamped = t.coerceIn(0f, 1f)
        // Gamma-lift so low/mid volume differences stay visible rather than crowding near purple.
        val eased = clamped.toDouble().pow(0.72).toFloat()
        val (r, g, b) = if (eased < 0.5f) {
            val f = eased / 0.5f
            Triple(
                lerp(gradientStopPurple[0], gradientStopYellow[0], f),
                lerp(gradientStopPurple[1], gradientStopYellow[1], f),
                lerp(gradientStopPurple[2], gradientStopYellow[2], f),
            )
        } else {
            val f = (eased - 0.5f) / 0.5f
            Triple(
                lerp(gradientStopYellow[0], gradientStopGreen[0], f),
                lerp(gradientStopYellow[1], gradientStopGreen[1], f),
                lerp(gradientStopYellow[2], gradientStopGreen[2], f),
            )
        }
        return Color.argb(alpha, r, g, b)
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).toInt().coerceIn(0, 255)

    // ---------------------------------------------------------------------
    // Projection
    // ---------------------------------------------------------------------

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class Projected(val sx: Float, val sy: Float, val depth: Float)

    private val heightScale = 0.85f
    private val perspectiveDistance = 4.2f

    private var originX = 0f
    private var originY = 0f
    private var baseScale = 0f

    private fun project(model: Vec3): Projected {
        val yawRad = Math.toRadians(yawDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble())

        // Yaw: rotate around the vertical (Z / volume) axis - this is the "turntable" spin.
        val cosYaw = cos(yawRad).toFloat()
        val sinYaw = sin(yawRad).toFloat()
        val rx = model.x * cosYaw - model.y * sinYaw
        val ry = model.x * sinYaw + model.y * cosYaw
        val rz = model.z

        // Pitch: tilt the time/depth plane up or down toward the camera.
        val cosPitch = cos(pitchRad).toFloat()
        val sinPitch = sin(pitchRad).toFloat()
        val fy = ry * cosPitch - rz * sinPitch
        val fz = ry * sinPitch + rz * cosPitch

        // Light perspective: things further along the (rotated) depth axis shrink slightly.
        val perspective = perspectiveDistance / (perspectiveDistance - fy)
        val sx = originX + rx * baseScale * zoom * perspective
        val sy = originY - fz * baseScale * zoom * perspective
        return Projected(sx, sy, fy)
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        originX = w / 2f
        originY = h * 0.56f
        baseScale = min(w, h) * 0.62f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawHeader(canvas)

        if (history.size < 2) {
            canvas.drawText(
                "Streaming order book\u2026 building the liquidity surface",
                width / 2f,
                height / 2f,
                emptyStatePaint,
            )
            return
        }

        val rows = history.size
        // Snapshot once per frame so the mesh doesn't tear if a new row lands mid-draw.
        val frame = history.toTypedArray()

        // Pre-project every grid vertex.
        val projected = Array(rows) { r ->
            Array(columns) { c ->
                val mx = ((c.toFloat() / (columns - 1)) - 0.5f) * 2f
                val my = ((r.toFloat() / (rows - 1)) - 0.5f) * 2f
                val vol = frame[r][c]
                val norm = (vol / runningPeakVolume).coerceIn(0f, 1f)
                project(Vec3(mx, my, norm * heightScale))
            }
        }

        // Mid-price divider plane, drawn first as the visual "floor seam" between bid & ask.
        drawMidPlane(canvas, rows)

        // Build every quad with its average depth (for the painter's algorithm) and colour.
        data class Quad(val path: Path, val color: Int, val depth: Float, val isCliff: Boolean)
        val quads = ArrayList<Quad>((rows - 1) * (columns - 1))

        for (r in 0 until rows - 1) {
            for (c in 0 until columns - 1) {
                val p00 = projected[r][c]
                val p10 = projected[r][c + 1]
                val p11 = projected[r + 1][c + 1]
                val p01 = projected[r + 1][c]

                val v00 = frame[r][c]
                val v10 = frame[r][c + 1]
                val v11 = frame[r + 1][c + 1]
                val v01 = frame[r + 1][c]
                val avgVol = (v00 + v10 + v11 + v01) / 4f
                val norm = (avgVol / runningPeakVolume).coerceIn(0f, 1f)

                // Row-shading: newer rows (larger r) read slightly brighter -> depth cue in time.
                val recency = 0.55f + 0.45f * (r.toFloat() / (rows - 1))
                val alpha = (150 + 90 * recency).toInt().coerceIn(0, 235)

                val path = Path().apply {
                    moveTo(p00.sx, p00.sy)
                    lineTo(p10.sx, p10.sy)
                    lineTo(p11.sx, p11.sy)
                    lineTo(p01.sx, p01.sy)
                    close()
                }

                // A "cliff": this level rests well above its inward (nearer-to-mid) neighbour -
                // the visual signature of a spoofing wall or a shelf that could act as
                // support/resistance before price actually gets there.
                val inwardVol = if (c < levelsPerSide - 1 || c >= levelsPerSide) {
                    if (c < levelsPerSide) v10 else v00 // neighbour that sits closer to mid
                } else 0f
                val isCliff = avgVol > runningPeakVolume * 0.32f &&
                    inwardVol > 0f &&
                    avgVol > inwardVol * 2.1f

                val depthKey = (p00.depth + p10.depth + p11.depth + p01.depth) / 4f
                quads.add(Quad(path, volumeColor(norm, alpha), depthKey, isCliff))
            }
        }

        // Painter's algorithm: farthest (smallest depth) first, nearest last.
        quads.sortBy { it.depth }
        for (quad in quads) {
            fillPaint.color = quad.color
            canvas.drawPath(quad.path, fillPaint)
            canvas.drawPath(quad.path, gridPaint)
            if (quad.isCliff) canvas.drawPath(quad.path, cliffRimPaint)
        }

        drawAxisLabels(canvas, rows)
        drawLegend(canvas)
    }

    private fun drawMidPlane(canvas: Canvas, rows: Int) {
        val midCol = levelsPerSide - 0.5f
        val mx = ((midCol / (columns - 1)) - 0.5f) * 2f
        val top = project(Vec3(mx, -1f, 0f))
        val bottom = project(Vec3(mx, 1f, 0f))
        val topHigh = project(Vec3(mx, -1f, heightScale))
        val bottomHigh = project(Vec3(mx, 1f, heightScale))

        val path = Path().apply {
            moveTo(top.sx, top.sy)
            lineTo(bottom.sx, bottom.sy)
            lineTo(bottomHigh.sx, bottomHigh.sy)
            lineTo(topHigh.sx, topHigh.sy)
            close()
        }
        canvas.drawPath(path, midPlanePaint)
        canvas.drawLine(top.sx, top.sy, bottom.sx, bottom.sy, midPlaneEdgePaint)
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawText("Orderbook Depth & Microstructure Liquidity Surface", dp(12f), dp(18f), titleLabelPaint)
        canvas.drawText(
            "drag to rotate \u00b7 pinch to zoom \u00b7 double-tap to reset",
            dp(12f),
            dp(32f),
            labelPaint,
        )
    }

    private fun drawAxisLabels(canvas: Canvas, rows: Int) {
        val bidAnchor = project(Vec3(-1f, 1.08f, 0f))
        val askAnchor = project(Vec3(1f, 1.08f, 0f))
        // row index (rows-1) = the most recently sampled tick (newest), row 0 = oldest.
        val nowAnchor = project(Vec3(0f, 1.12f, 0f))
        val pastAnchor = project(Vec3(0f, -1.16f, 0f))

        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("BIDS \u2190 depth", bidAnchor.sx, bidAnchor.sy, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("depth \u2192 ASKS", askAnchor.sx, askAnchor.sy, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("now", nowAnchor.sx, nowAnchor.sy, labelPaint)
        if (historySpanSeconds > 0f) {
            canvas.drawText(
                "-${"%.1f".format(historySpanSeconds)}s",
                pastAnchor.sx,
                pastAnchor.sy,
                labelPaint,
            )
        }
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawLegend(canvas: Canvas) {
        val barWidth = dp(10f)
        val barHeight = dp(74f)
        val left = width - dp(12f) - barWidth
        val top = height - dp(24f) - barHeight
        val right = left + barWidth
        val bottom = top + barHeight

        val gradient = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                volumeColor(1f, 255),
                volumeColor(0.5f, 255),
                volumeColor(0f, 255),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
        canvas.drawRect(left, top, right, bottom, legendPaint)
        canvas.drawRect(left, top, right, bottom, gridPaint)

        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("heavy", left - dp(4f), top + dp(9f), labelPaint)
        canvas.drawText("thin", left - dp(4f), bottom, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }
}
