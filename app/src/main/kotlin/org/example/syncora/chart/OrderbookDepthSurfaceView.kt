package org.example.syncora.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.example.syncora.bitget.DepthLevel
import kotlin.math.cos
import kotlin.math.max
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
 * (rotation matrices + a light perspective term) so it has no extra rendering dependency. It
 * holds a fixed default pose at rest (no auto-rotation) and only moves in response to the
 * person's own drag/pinch gestures.
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

    /** Bilinear upsampling factor applied at render time for a smooth, continuous surface. */
    private val smoothFactor = 2

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

    // Static default framing matching the reference surface: both the depth and time axes
    // recede from a near, low-volume (purple) corner toward a far, high-volume (yellow) one.
    // The view holds this pose at rest - it never animates on its own - and only moves when
    // the person actively drags or pinches it.
    private val defaultYawDeg = -65f
    private val defaultPitchDeg = 27.5f

    private var yawDeg = defaultYawDeg
    private var pitchDeg = defaultPitchDeg
    private var zoom = 1f

    // Vertical drag swings elevation across a wide band - from almost edge-on (near the floor)
    // to almost straight down (top-down) - so a swipe up/down reads as a real rotation rather
    // than a small nudge.
    private val minPitch = -70f
    private val maxPitch = 88f
    private val minZoom = 0.55f
    private val maxZoom = 2.8f

    fun resetCamera() {
        yawDeg = defaultYawDeg
        pitchDeg = defaultPitchDeg
        zoom = 1f
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

    // ---------------------------------------------------------------------
    // Styling
    // ---------------------------------------------------------------------

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val backgroundPaint = Paint().apply { color = Color.parseColor("#0A0E14") }

    // Quiet mesh seams: thin, low-alpha, softly rounded, and colour-matched to the fill so they
    // read as a faint facet edge rather than a hard, sharp lattice line.
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0E14")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.4f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        alpha = 32
    }
    private val floorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A4656")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
        strokeCap = Paint.Cap.ROUND
        alpha = 40
    }
    private val cliffRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FDF4FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        alpha = 190
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
    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B6472")
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val axisBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5568")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
        alpha = 130
    }
    private val axisLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C7CED9")
        textSize = sp(12.5f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val axisNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A94A3")
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
    }

    // Purple -> blue -> teal -> green -> yellow liquidity gradient: thin/quiet depth reads as
    // deep purple, building interest moves through teal-green, and a heavy resting wall reads
    // bright yellow-green - the same low-to-high volume ramp as the reference surface.
    private val gradientStops = arrayOf(
        intArrayOf(0x44, 0x01, 0x54), // #440154 - quiet / near-zero depth
        intArrayOf(0x3B, 0x52, 0x8B), // #3B528B
        intArrayOf(0x21, 0x90, 0x8C), // #21908C
        intArrayOf(0x5D, 0xC8, 0x63), // #5DC863
        intArrayOf(0xFD, 0xE7, 0x25), // #FDE725 - heavy wall
    )
    private fun volumeColor(t: Float, alpha: Int): Int {
        val clamped = t.coerceIn(0f, 1f)
        // Gamma-lift so low/mid volume differences stay visible rather than crowding near purple.
        val eased = clamped.toDouble().pow(0.78).toFloat()

        val segments = gradientStops.size - 1
        val scaled = eased * segments
        val segIndex = scaled.toInt().coerceIn(0, segments - 1)
        val f = (scaled - segIndex).coerceIn(0f, 1f)
        val a = gradientStops[segIndex]
        val b = gradientStops[segIndex + 1]
        return Color.argb(alpha, lerp(a[0], b[0], f), lerp(a[1], b[1], f), lerp(a[2], b[2], f))
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).toInt().coerceIn(0, 255)

    // ---------------------------------------------------------------------
    // Projection
    // ---------------------------------------------------------------------

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class Projected(val sx: Float, val sy: Float, val depth: Float)

    // Kept low so the mesh reads as a flattened sheet - relief communicated mostly through the
    // colour gradient, like a sheet of paper laid at an angle, rather than a tall relief map.
    private val heightScale = 0.32f
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
        // Scale off width (not min(w,h)): at the default yaw/pitch the mesh's horizontal spread
        // is the binding constraint, and tying scale to a taller container would otherwise blow
        // the surface past the view's edges as the panel grows taller.
        originX = w * 0.47f
        originY = h * 0.52f
        baseScale = w * 0.30f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

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

        // Cliff flags are computed on the raw (coarse) samples - smoothing must never blur away
        // a genuine wall, it should only make the surface between samples read continuously.
        val cliffCoarse = Array(rows - 1) { r ->
            BooleanArray(columns - 1) { c ->
                val v00 = frame[r][c]
                val v10 = frame[r][c + 1]
                val v01 = frame[r + 1][c]
                val v11 = frame[r + 1][c + 1]
                val avgVol = (v00 + v10 + v01 + v11) / 4f
                // A "cliff": this level rests well above its inward (nearer-to-mid) neighbour -
                // the visual signature of a spoofing wall or a shelf that could act as
                // support/resistance before price actually gets there.
                val inwardVol = if (c < levelsPerSide - 1 || c >= levelsPerSide) {
                    if (c < levelsPerSide) v10 else v00 // neighbour that sits closer to mid
                } else 0f
                avgVol > runningPeakVolume * 0.32f && inwardVol > 0f && avgVol > inwardVol * 2.1f
            }
        }

        fun sampleVolume(rf: Float, cf: Float): Float {
            val r0 = rf.toInt().coerceIn(0, rows - 1)
            val r1 = (r0 + 1).coerceAtMost(rows - 1)
            val c0 = cf.toInt().coerceIn(0, columns - 1)
            val c1 = (c0 + 1).coerceAtMost(columns - 1)
            val tr = rf - r0
            val tc = cf - c0
            val top = frame[r0][c0] + (frame[r0][c1] - frame[r0][c0]) * tc
            val bottom = frame[r1][c0] + (frame[r1][c1] - frame[r1][c0]) * tc
            return top + (bottom - top) * tr
        }

        // Upsample the sparse tick/level grid with bilinear interpolation so the rendered mesh
        // reads as a smooth, continuous surface rather than a blocky lattice.
        val fineRows = (rows - 1) * smoothFactor + 1
        val fineCols = (columns - 1) * smoothFactor + 1
        val fineVolume = Array(fineRows) { fr ->
            FloatArray(fineCols) { fc -> sampleVolume(fr.toFloat() / smoothFactor, fc.toFloat() / smoothFactor) }
        }
        val projected = Array(fineRows) { fr ->
            Array(fineCols) { fc ->
                val mx = ((fc.toFloat() / (fineCols - 1)) - 0.5f) * 2f
                val my = ((fr.toFloat() / (fineRows - 1)) - 0.5f) * 2f
                val norm = (fineVolume[fr][fc] / runningPeakVolume).coerceIn(0f, 1f)
                project(Vec3(mx, my, norm * heightScale))
            }
        }

        // Faint reference grid across the three axis planes (XY floor, XZ back wall, YZ side
        // wall), then the mid-price divider plane, then the corner posts framing the volume -
        // all drawn first as the backdrop the shaded surface sits on.
        drawFloorGrid(canvas)
        drawBackWallGrid(canvas)
        drawSideWallGrid(canvas)
        drawMidPlane(canvas, rows)
        drawAxesBox(canvas)

        // Build every quad with its average depth (for the painter's algorithm) and colour.
        data class Quad(val path: Path, val color: Int, val depth: Float, val isCliff: Boolean)
        val quads = ArrayList<Quad>((fineRows - 1) * (fineCols - 1))

        for (fr in 0 until fineRows - 1) {
            val coarseR = (fr / smoothFactor).coerceAtMost(rows - 2)
            // Newer rows (higher fr) read slightly brighter -> a gentle depth cue in time.
            val recency = 0.55f + 0.45f * (fr.toFloat() / (fineRows - 1))
            val alpha = (150 + 90 * recency).toInt().coerceIn(0, 235)

            for (fc in 0 until fineCols - 1) {
                val p00 = projected[fr][fc]
                val p10 = projected[fr][fc + 1]
                val p11 = projected[fr + 1][fc + 1]
                val p01 = projected[fr + 1][fc]

                val avgVol = (fineVolume[fr][fc] + fineVolume[fr][fc + 1] + fineVolume[fr + 1][fc + 1] + fineVolume[fr + 1][fc]) / 4f
                val norm = (avgVol / runningPeakVolume).coerceIn(0f, 1f)

                val path = Path().apply {
                    moveTo(p00.sx, p00.sy)
                    lineTo(p10.sx, p10.sy)
                    lineTo(p11.sx, p11.sy)
                    lineTo(p01.sx, p01.sy)
                    close()
                }

                val coarseC = (fc / smoothFactor).coerceAtMost(columns - 2)
                val isCliff = cliffCoarse[coarseR][coarseC]

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
    }

    private fun drawFloorGrid(canvas: Canvas) {
        // XY plane (z = 0): the floor. Lines across depth (X) and across time (Y).
        val depthLines = 8
        for (i in 0..depthLines) {
            val mx = ((i.toFloat() / depthLines) - 0.5f) * 2f
            val a = project(Vec3(mx, -1f, 0f))
            val b = project(Vec3(mx, 1f, 0f))
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, floorGridPaint)
        }
        val timeLines = 6
        for (i in 0..timeLines) {
            val my = ((i.toFloat() / timeLines) - 0.5f) * 2f
            val a = project(Vec3(-1f, my, 0f))
            val b = project(Vec3(1f, my, 0f))
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, floorGridPaint)
        }
    }

    private fun drawBackWallGrid(canvas: Canvas) {
        // XZ plane (y = -1, the back wall): lines across depth (X) and across volume tiers (Z).
        val z0 = 0f
        val z1 = heightScale
        val depthLines = 8
        for (i in 0..depthLines) {
            val mx = ((i.toFloat() / depthLines) - 0.5f) * 2f
            val a = project(Vec3(mx, -1f, z0))
            val b = project(Vec3(mx, -1f, z1))
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, floorGridPaint)
        }
        val tierLines = 4
        for (i in 0..tierLines) {
            val mz = z0 + (z1 - z0) * (i.toFloat() / tierLines)
            val a = project(Vec3(-1f, -1f, mz))
            val b = project(Vec3(1f, -1f, mz))
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, floorGridPaint)
        }
    }

    private fun drawSideWallGrid(canvas: Canvas) {
        // YZ plane (x = -1, the side wall): lines across time (Y) and across volume tiers (Z).
        val z0 = 0f
        val z1 = heightScale
        val timeLines = 6
        for (i in 0..timeLines) {
            val my = ((i.toFloat() / timeLines) - 0.5f) * 2f
            val a = project(Vec3(-1f, my, z0))
            val b = project(Vec3(-1f, my, z1))
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, floorGridPaint)
        }
        val tierLines = 4
        for (i in 0..tierLines) {
            val mz = z0 + (z1 - z0) * (i.toFloat() / tierLines)
            val a = project(Vec3(-1f, -1f, mz))
            val b = project(Vec3(-1f, 1f, mz))
            canvas.drawLine(a.sx, a.sy, b.sx, b.sy, floorGridPaint)
        }
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

    /**
     * Corner posts marking the full data volume (X = depth, Y = time, Z = resting volume), plus
     * single-letter axis labels so the mesh's rotation always reads against a fixed frame of
     * reference. The three faces themselves are gridded separately (see drawFloorGrid /
     * drawBackWallGrid / drawSideWallGrid).
     */
    private fun drawAxesBox(canvas: Canvas) {
        val z0 = 0f
        val z1 = heightScale

        val c000 = project(Vec3(-1f, -1f, z0))
        val c100 = project(Vec3(1f, -1f, z0))
        val c110 = project(Vec3(1f, 1f, z0))
        val c010 = project(Vec3(-1f, 1f, z0))
        val c001 = project(Vec3(-1f, -1f, z1))
        val c101 = project(Vec3(1f, -1f, z1))
        val c111 = project(Vec3(1f, 1f, z1))
        val c011 = project(Vec3(-1f, 1f, z1))

        fun edge(a: Projected, b: Projected) = canvas.drawLine(a.sx, a.sy, b.sx, b.sy, axisBoxPaint)

        // Floor rectangle
        edge(c000, c100); edge(c100, c110); edge(c110, c010); edge(c010, c000)
        // Ceiling rectangle
        edge(c001, c101); edge(c101, c111); edge(c111, c011); edge(c011, c001)
        // Vertical corner posts
        edge(c000, c001); edge(c100, c101); edge(c110, c111); edge(c010, c011)

        // X = depth (bid <-> ask), Y = time, Z = resting volume.
        val xAnchor = project(Vec3(1.18f, -1f, z0))
        val yAnchor = project(Vec3(-1.2f, 0f, z0))
        val zAnchor = project(Vec3(-1f, -1f, z1 * 1.25f))

        canvas.drawText("X", xAnchor.sx, xAnchor.sy, axisLetterPaint)
        canvas.drawText("depth", xAnchor.sx, xAnchor.sy + dp(12f), axisNamePaint)

        canvas.drawText("Y", yAnchor.sx, yAnchor.sy, axisLetterPaint)
        canvas.drawText("time", yAnchor.sx, yAnchor.sy + dp(12f), axisNamePaint)

        canvas.drawText("Z", zAnchor.sx, zAnchor.sy, axisLetterPaint)
        canvas.drawText("volume", zAnchor.sx, zAnchor.sy + dp(12f), axisNamePaint)
    }

    private fun drawAxisLabels(canvas: Canvas, rows: Int) {
        val bidAnchor = project(Vec3(-1f, 1.04f, 0f))
        val askAnchor = project(Vec3(1f, 1.04f, 0f))
        // row index (rows-1) = the most recently sampled tick (newest), row 0 = oldest.
        val nowAnchor = project(Vec3(0f, 1.07f, 0f))
        val pastAnchor = project(Vec3(0f, -1.08f, 0f))

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
}
