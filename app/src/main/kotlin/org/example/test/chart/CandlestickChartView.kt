package org.example.test.chart

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import org.example.test.bitget.Kline
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class CandlestickChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {

        val defaultDrawingColor: Int = Color.parseColor("#2962FF")
        const val defaultDrawingLineWidthDp: Float = 1.6f
    }

    fun submitCandles(newCandles: List<Kline>) {
        val previousRange = timeRangeOverride
        val wasPinnedToRight = previousRange == null || isAtRightEdge(previousRange)

        candles = newCandles

        if (previousRange != null) {
            val nextRange = if (fixRightEdge && wasPinnedToRight) {
                previousRange.copy(rightIndex = maxRightIndex(previousRange.barSpacingPx))
            } else {
                previousRange
            }
            timeRangeOverride = clampTimeRange(nextRange)
        }
        invalidate()
    }

    fun setBarDurationMillis(millis: Long) {
        barDurationMillis = millis
        invalidate()
    }

    fun setSkeletonLoading(loading: Boolean) {
        if (isSkeletonLoading == loading) return
        isSkeletonLoading = loading
        if (loading) {
            startSkeletonShimmer()
        } else {
            stopSkeletonShimmer()
        }
        invalidate()
    }

    var onViewportChange: ((ChartPriceRange?) -> Unit)? = null

    var onTimeWindowChange: ((List<Kline>) -> Unit)? = null

    fun visibleCandles(): List<Kline> {
        if (candles.isEmpty()) return emptyList()
        val window = effectiveTimeWindow()
        return candles.subList(window.startIndex, window.endIndexExclusive)
    }

    data class DrawingPoint(val time: Long, val price: Double)

    data class Drawing(
        val tool: DrawingTool,
        var p1: DrawingPoint,
        var p2: DrawingPoint? = null,
        var color: Int = defaultDrawingColor,
        var opacityPercent: Int = 100,
        var lineWidthDp: Float = defaultDrawingLineWidthDp,
        var pattern: LinePattern = LinePattern.SOLID,
    )

    var onDrawingPlaced: (() -> Unit)? = null

    data class SelectedDrawingStyle(
        val color: Int,
        val opacityPercent: Int,
        val lineWidthDp: Float,
        val pattern: LinePattern,
    )

    var onSelectedDrawingChanged: ((SelectedDrawingStyle?) -> Unit)? = null

    fun selectedDrawingStyle(): SelectedDrawingStyle? {
        val index = selectedDrawingIndex ?: return null
        if (index !in drawings.indices) return null
        val d = drawings[index]
        return SelectedDrawingStyle(d.color, d.opacityPercent, d.lineWidthDp, d.pattern)
    }

    var onSelectedDrawingBoundsChanged: ((RectF?) -> Unit)? = null

    fun contentAreaPx(): RectF = RectF(
        0f,
        0f,
        (width - priceAxisWidth).coerceAtLeast(0f),
        (height - timeAxisHeight).coerceAtLeast(0f),
    )

    fun selectedDrawingBoundsPx(): RectF? {
        val index = selectedDrawingIndex ?: return null
        if (!mapValid || index !in drawings.indices) return null
        return selectionScreenBounds(drawings[index])
    }

    private fun selectionScreenBounds(drawing: Drawing): RectF {
        val x1 = timeToScreenX(drawing.p1.time)
        val y1 = priceToScreenY(drawing.p1.price)
        val p2 = drawing.p2
        if (p2 != null) {
            val x2 = timeToScreenX(p2.time)
            val y2 = priceToScreenY(p2.price)
            return RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        }
        val area = contentAreaPx()
        return when (drawing.tool) {
            DrawingTool.HORIZONTAL_LINE -> RectF(area.left, y1, area.right, y1)
            DrawingTool.HORIZONTAL_RAY -> RectF(x1, y1, area.right, y1)
            DrawingTool.VERTICAL_LINE -> RectF(x1, area.top, x1, area.bottom)
            else -> RectF(x1, y1, x1, y1)
        }
    }

    private fun notifySelectedDrawingChanged() {
        onSelectedDrawingChanged?.invoke(selectedDrawingStyle())
        onSelectedDrawingBoundsChanged?.invoke(selectedDrawingBoundsPx())
    }

    fun setSelectedLineColor(color: Int) {
        val index = selectedDrawingIndex ?: return
        if (index !in drawings.indices) return
        drawings[index].color = color
        invalidate()
        notifySelectedDrawingChanged()
    }

    fun setSelectedLineOpacity(percent: Int) {
        val index = selectedDrawingIndex ?: return
        if (index !in drawings.indices) return
        drawings[index].opacityPercent = percent.coerceIn(0, 100)
        invalidate()
        notifySelectedDrawingChanged()
    }

    fun setSelectedLineWidth(widthDp: Float) {
        val index = selectedDrawingIndex ?: return
        if (index !in drawings.indices) return
        drawings[index].lineWidthDp = widthDp
        invalidate()
        notifySelectedDrawingChanged()
    }

    fun setSelectedLinePattern(pattern: LinePattern) {
        val index = selectedDrawingIndex ?: return
        if (index !in drawings.indices) return
        drawings[index].pattern = pattern
        invalidate()
        notifySelectedDrawingChanged()
    }

    fun deleteSelectedDrawing() {
        val index = selectedDrawingIndex ?: return
        if (index !in drawings.indices) return
        drawings.removeAt(index)
        selectedDrawingIndex = null
        resetSelectionDrag()
        invalidate()
        notifySelectedDrawingChanged()
    }

    private val drawings = mutableListOf<Drawing>()
    private var activeDrawingTool: DrawingTool = DrawingTool.NONE
    private var pendingDrawing: Drawing? = null

    private val touchSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    private enum class AnchorPlacementPhase {

        IDLE,

        POSITIONING_ANCHOR_1,

        AWAITING_CONFIRM_ANCHOR_1,

        POSITIONING_ANCHOR_2,

        AWAITING_CONFIRM_ANCHOR_2,
    }

    private var placementPhase = AnchorPlacementPhase.IDLE

    private fun isTouchOnAnchor(x: Float, y: Float, anchor: DrawingPoint): Boolean {
        val ax = timeToScreenX(anchor.time)
        val ay = priceToScreenY(anchor.price)
        return hypot((x - ax).toDouble(), (y - ay).toDouble()) <= handleGrabRadiusPx
    }

    fun setActiveDrawingTool(tool: DrawingTool) {
        activeDrawingTool = tool
        pendingDrawing = null
        placementPhase = AnchorPlacementPhase.IDLE
        val hadSelection = selectedDrawingIndex != null
        selectedDrawingIndex = null
        resetSelectionDrag()
        if (hadSelection) notifySelectedDrawingChanged()
        isDrawingCrosshairActive = tool != DrawingTool.NONE
        if (isDrawingCrosshairActive) {

            if (width > 0 && height > 0 && !hasSeededDrawingCrosshair) {
                drawingCrosshairX = width / 2f
                drawingCrosshairY = height / 2f
            }
            hasSeededDrawingCrosshair = true
        } else {
            hasSeededDrawingCrosshair = false
        }
        invalidate()
    }

    fun clearDrawings() {
        if (drawings.isEmpty()) return
        val hadSelection = selectedDrawingIndex != null
        drawings.clear()
        selectedDrawingIndex = null
        resetSelectionDrag()
        invalidate()
        if (hadSelection) notifySelectedDrawingChanged()
    }

    private val bullColor = Color.parseColor("#22D3C5")
    private val bearColor = Color.parseColor("#FF5A6E")
    private val bgColor = Color.parseColor("#050608")
    private val gridColor = Color.parseColor("#141A20")
    private val axisTextColor = Color.parseColor("#B8C6CF")
    private val timeAxisTextColor = Color.parseColor("#8A96A3")
    private val lastPriceColor = Color.parseColor("#8A96A3")
    private val skeletonBodyColor = Color.parseColor("#2A2E39")
    private val skeletonWickColor = Color.parseColor("#363C4E")
    private val skeletonHighlightColor = Color.parseColor("#4B5368")

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private val priceAxisWidth get() = ChartLayoutMetrics.priceAxisWidthPx(resources)
    private val timeAxisHeight get() = ChartLayoutMetrics.timeAxisHeightPx(resources)

    private val baseGridLineCount = 15
    private val minGridLineCount = 3
    private val maxGridLineCount = 25

    private val minGridLineSpacingPx = dp(16f)

    private val minBodyPx = dp(1f)
    private val wickWidthPx = dp(0.75f)
    private val bodyMinWidthPx = dp(1.5f)
    private val bodyGapRatio = 0.35f

    private val bodyFillAlpha = 214
    private val bodyStrokeWidthPx = dp(0.75f)

    private val shadowPaddingPx = dp(0.5f)
    private val wickShadowWidthPx = wickWidthPx + shadowPaddingPx * 2f

    private val bgPaint = Paint().apply { color = bgColor; style = Paint.Style.FILL }
    private val gridPaint = Paint().apply {
        color = gridColor
        alpha = 140
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = axisTextColor
        textSize = dp(11f)
    }

    private val timeAxisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = timeAxisTextColor
        textSize = dp(10.5f)
        textAlign = Paint.Align.CENTER
    }

    private val minTimeLabelSpacingPx = dp(64f)

    private val niceTimeIntervalsMillis = longArrayOf(
        1_000L, 5_000L, 15_000L, 30_000L,
        60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 30 * 60_000L,
        3_600_000L, 2 * 3_600_000L, 4 * 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L,
        86_400_000L, 3 * 86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 365 * 86_400_000L,
    )

    private val timeOfDayFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    private val dayMonthFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
    private val yearFormat = java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
    private val bullBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bullColor; style = Paint.Style.FILL; alpha = bodyFillAlpha
    }
    private val bearBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bearColor; style = Paint.Style.FILL; alpha = bodyFillAlpha
    }

    private val bullBodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bullColor; style = Paint.Style.STROKE; strokeWidth = bodyStrokeWidthPx
    }
    private val bearBodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bearColor; style = Paint.Style.STROKE; strokeWidth = bodyStrokeWidthPx
    }
    private val bullWickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bullColor; strokeWidth = wickWidthPx
    }
    private val bearWickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bearColor; strokeWidth = wickWidthPx
    }

    private val bodyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor; style = Paint.Style.FILL
    }
    private val wickShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor; strokeWidth = wickShadowWidthPx
    }
    private val bullVolumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bullColor; alpha = 140 }
    private val bearVolumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bearColor; alpha = 140 }
    private val skeletonBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skeletonBodyColor
        style = Paint.Style.FILL
        alpha = bodyFillAlpha
    }
    private val skeletonBodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skeletonBodyColor
        style = Paint.Style.STROKE
        strokeWidth = bodyStrokeWidthPx
    }
    private val skeletonWickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skeletonWickColor
        strokeWidth = wickWidthPx
    }
    private val skeletonVolumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skeletonBodyColor
        alpha = 110
    }
    private val skeletonShimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 86
    }
    private val skeletonShimmerMatrix = Matrix()
    private var skeletonShimmerShader: Shader? = null
    private var skeletonShimmerAnimator: ValueAnimator? = null
    private var skeletonShimmerOffsetX = 0f
    private var skeletonShimmerSpan = 0f
    private var isSkeletonLoading = false
    private val skeletonShimmerDurationMs = 1_800L
    private val skeletonShimmerBandWidthFraction = 0.32f
    private val lastPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lastPriceColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val lastPriceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
    }
    private val lastPriceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lastPriceColor
        style = Paint.Style.FILL
    }
    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = axisTextColor
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val countdownBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val countdownTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10.5f)
        textAlign = Paint.Align.CENTER
    }
    private val countdownUrgentColor = Color.parseColor("#FF5A6E")

    private var candles: List<Kline> = emptyList()
    private val reusableRect = RectF()
    private val reusableShadowRect = RectF()

    private var priceRangeOverride: ChartPriceRange? = null

    private val minZoomSpanFraction = 0.05
    private val maxZoomSpanFraction = 8.0

    private data class TimeWindow(val startIndex: Int, val endIndexExclusive: Int)

    private data class LogicalTimeRange(val rightIndex: Double, val barSpacingPx: Float)

    private var timeRangeOverride: LogicalTimeRange? = null

    private val defaultVisibleCandleCount = 100
    private val minVisibleCandleCount = 10
    private val maxVisibleCandleCountFraction = 8.0
    private val defaultRightMarginFraction = 0.12

    private var fixedRightOffsetBars: Double? = null
    private var rightMarginFraction = defaultRightMarginFraction
    var fixRightEdge: Boolean = true
        set(value) {
            field = value
            clampTimeRangeOverride()
            notifyTimeWindowChanged()
            invalidate()
        }

    private var isCrosshairActive = false
    private var crosshairRawX = 0f
    private var crosshairRawY = 0f

    private var isDrawingCrosshairActive = false
    private var hasSeededDrawingCrosshair = false
    private var drawingCrosshairX = 0f
    private var drawingCrosshairY = 0f

    private val crosshairLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = axisTextColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val crosshairLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = axisTextColor
        style = Paint.Style.FILL
    }
    private val crosshairPriceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        textSize = dp(11f)
    }
    private val crosshairTimeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        textSize = dp(10.5f)
        textAlign = Paint.Align.CENTER
    }
    private val crosshairTimeFormat = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)

    private val drawingAccentColor = Color.parseColor("#2962FF")
    private val drawingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = drawingAccentColor
        strokeWidth = dp(1.6f)
        style = Paint.Style.STROKE
    }
    private val drawingPendingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = drawingAccentColor
        strokeWidth = dp(1.6f)
        style = Paint.Style.STROKE
        alpha = 170
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val drawingPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = drawingAccentColor
        style = Paint.Style.FILL
    }
    private val drawingLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = drawingAccentColor
        style = Paint.Style.FILL
    }
    private val drawingLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10.5f)
        textAlign = Paint.Align.LEFT
    }

    private var selectedDrawingIndex: Int? = null

    private enum class DragTarget { P1, P2, BODY }

    private var selectionDragTarget: DragTarget? = null

    private var isDraggingSelectedLine = false
    private var dragGestureStartX = 0f
    private var dragGestureStartY = 0f

    private var dragStartP1: DrawingPoint? = null
    private var dragStartP2: DrawingPoint? = null

    private val handleGrabRadiusPx = dp(16f)

    var hitTestTolerancePx: Float = dp(14f)

    private val handleRadiusPx = dp(5f)

    private val stylePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val styleSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val styleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dashedPatternIntervals = floatArrayOf(dp(6f), dp(4f))
    private val dottedPatternIntervals = floatArrayOf(dp(1.5f), dp(4.5f))

    private fun configureStylePaint(paint: Paint, drawing: Drawing, extraWidthDp: Float = 0f) {
        paint.color = drawing.color
        paint.alpha = (drawing.opacityPercent.coerceIn(0, 100) * 255 / 100)
        paint.strokeWidth = dp(drawing.lineWidthDp + extraWidthDp)
        when (drawing.pattern) {
            LinePattern.SOLID -> {
                paint.pathEffect = null
                paint.strokeCap = Paint.Cap.BUTT
            }
            LinePattern.DASHED -> {
                paint.pathEffect = android.graphics.DashPathEffect(dashedPatternIntervals, 0f)
                paint.strokeCap = Paint.Cap.BUTT
            }
            LinePattern.DOTTED -> {
                paint.pathEffect = android.graphics.DashPathEffect(dottedPatternIntervals, 0f)
                paint.strokeCap = Paint.Cap.ROUND
            }
        }
    }
    private val selectionHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val selectionHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = drawingAccentColor
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private var mapMinPrice = 0.0
    private var mapMaxPrice = 0.0
    private var mapPriceAreaHeight = 0f
    private var mapLeftIndex = 0.0
    private var mapSlotWidth = 0f
    private var mapValid = false

    private fun screenToIndex(x: Float): Double = mapLeftIndex + x / mapSlotWidth

    private fun screenToPrice(y: Float): Double =
        mapMaxPrice - (y / mapPriceAreaHeight).toDouble() * (mapMaxPrice - mapMinPrice)

    private fun indexToScreenX(index: Double): Float = ((index - mapLeftIndex) * mapSlotWidth).toFloat()

    private fun priceToScreenY(price: Double): Float =
        (((mapMaxPrice - price) / (mapMaxPrice - mapMinPrice)).toFloat()) * mapPriceAreaHeight

    private fun currentBarDurationMillis(): Long {
        if (barDurationMillis > 0L) return barDurationMillis
        if (candles.size >= 2) {
            val inferred = candles[1].startTime - candles[0].startTime
            if (inferred > 0L) return inferred
        }
        return 0L
    }

    private fun timeToIndex(time: Long): Double {
        val base = candles.firstOrNull()?.startTime ?: return 0.0
        val duration = currentBarDurationMillis()
        if (duration <= 0L) return 0.0
        return (time - base).toDouble() / duration.toDouble()
    }

    private fun indexToTime(index: Double): Long {
        val base = candles.firstOrNull()?.startTime ?: return 0L
        val duration = currentBarDurationMillis()
        return base + (index * duration).roundToLong()
    }

    private fun timeToScreenX(time: Long): Float = indexToScreenX(timeToIndex(time))

    private fun screenToTime(x: Float): Long = indexToTime(round(screenToIndex(x)))

    private fun screenToTimeRaw(x: Float): Long = indexToTime(screenToIndex(x))

    private fun distancePointToSegmentPx(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 0f) return hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
        val t = (((px - x1) * dx + (py - y1) * dy) / lengthSq).coerceIn(0f, 1f)
        val projX = x1 + t * dx
        val projY = y1 + t * dy
        return hypot((px - projX).toDouble(), (py - projY).toDouble()).toFloat()
    }

    private fun isNearOnePointDrawing(tool: DrawingTool, x: Float, y: Float, ax: Float, ay: Float): Boolean {
        val area = contentAreaPx()
        return when (tool) {
            DrawingTool.HORIZONTAL_LINE -> abs(y - ay) <= hitTestTolerancePx
            DrawingTool.HORIZONTAL_RAY -> distancePointToSegmentPx(x, y, ax, ay, area.right, ay) <= hitTestTolerancePx
            DrawingTool.VERTICAL_LINE -> abs(x - ax) <= hitTestTolerancePx
            DrawingTool.CROSS_LINE -> abs(y - ay) <= hitTestTolerancePx || abs(x - ax) <= hitTestTolerancePx
            else -> false
        }
    }

    private fun isNearTwoPointDrawingBody(
        tool: DrawingTool,
        x: Float,
        y: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Boolean {
        val area = contentAreaPx()
        return when (tool) {
            DrawingTool.RAY -> {
                val (ex, ey) = extendToBoundary(x1, y1, x2, y2, area.right, area.bottom)
                distancePointToSegmentPx(x, y, x1, y1, ex, ey) <= hitTestTolerancePx
            }
            DrawingTool.EXTENDED_LINE -> {
                val (fx, fy) = extendToBoundary(x1, y1, x2, y2, area.right, area.bottom)
                val (bx, by) = extendToBoundary(x2, y2, x1, y1, area.right, area.bottom)
                distancePointToSegmentPx(x, y, bx, by, fx, fy) <= hitTestTolerancePx
            }
            else -> distancePointToSegmentPx(x, y, x1, y1, x2, y2) <= hitTestTolerancePx
        }
    }

    private fun findDrawingHit(x: Float, y: Float): Int? {
        if (!mapValid) return null
        for (index in drawings.indices.reversed()) {
            val drawing = drawings[index]
            val x1 = timeToScreenX(drawing.p1.time)
            val y1 = priceToScreenY(drawing.p1.price)
            val p2 = drawing.p2
            val hit = if (p2 == null) {
                isNearOnePointDrawing(drawing.tool, x, y, x1, y1)
            } else {
                isNearTwoPointDrawingBody(drawing.tool, x, y, x1, y1, timeToScreenX(p2.time), priceToScreenY(p2.price))
            }
            if (hit) return index
        }
        return null
    }

    private fun handleSelectionDragTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && selectionDragTarget != null) {

            resetSelectionDrag()
            invalidate()
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            return tryStartSelectionDrag(event)
        }

        if (selectionDragTarget == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = selectedDrawingIndex
                val target = selectionDragTarget
                if (index == null || target == null || index !in drawings.indices) {
                    resetSelectionDrag()
                    return true
                }
                if (!isDraggingSelectedLine) {

                    val traveled = hypot(
                        (event.x - dragGestureStartX).toDouble(),
                        (event.y - dragGestureStartY).toDouble(),
                    )
                    if (traveled < touchSlop) return true
                    isDraggingSelectedLine = true
                }
                applySelectionDrag(index, target, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val index = selectedDrawingIndex
                val target = selectionDragTarget
                if (isDraggingSelectedLine && index != null && target != null && index in drawings.indices) {
                    applySelectionDrag(index, target, event.x, event.y)
                }
                resetSelectionDrag()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                resetSelectionDrag()
                invalidate()
            }
            else -> Unit
        }
        return true
    }

    private fun tryStartSelectionDrag(event: MotionEvent): Boolean {
        val index = selectedDrawingIndex ?: return false
        if (!mapValid || index !in drawings.indices) return false
        val drawing = drawings[index]
        val x1 = timeToScreenX(drawing.p1.time)
        val y1 = priceToScreenY(drawing.p1.price)
        val p2 = drawing.p2

        val target = if (p2 == null) {
            if (isNearOnePointDrawing(drawing.tool, event.x, event.y, x1, y1)) DragTarget.P1 else null
        } else {
            val x2 = timeToScreenX(p2.time)
            val y2 = priceToScreenY(p2.price)
            when {
                hypot((event.x - x1).toDouble(), (event.y - y1).toDouble()) <= handleGrabRadiusPx -> DragTarget.P1
                hypot((event.x - x2).toDouble(), (event.y - y2).toDouble()) <= handleGrabRadiusPx -> DragTarget.P2
                isNearTwoPointDrawingBody(drawing.tool, event.x, event.y, x1, y1, x2, y2) -> DragTarget.BODY
                else -> null
            }
        } ?: return false

        selectionDragTarget = target
        isDraggingSelectedLine = false
        dragGestureStartX = event.x
        dragGestureStartY = event.y
        dragStartP1 = drawing.p1
        dragStartP2 = p2
        return true
    }

    private fun applySelectionDrag(index: Int, target: DragTarget, x: Float, y: Float) {
        val drawing = drawings[index]
        when (target) {
            DragTarget.P1 -> drawing.p1 = DrawingPoint(screenToTime(x), screenToPrice(y))
            DragTarget.P2 -> drawing.p2 = DrawingPoint(screenToTime(x), screenToPrice(y))
            DragTarget.BODY -> {
                val startP1 = dragStartP1 ?: return
                val startP2 = dragStartP2 ?: return

                val deltaTime = screenToTimeRaw(x) - screenToTimeRaw(dragGestureStartX)
                val deltaPrice = screenToPrice(y) - screenToPrice(dragGestureStartY)
                drawing.p1 = DrawingPoint(startP1.time + deltaTime, startP1.price + deltaPrice)
                drawing.p2 = DrawingPoint(startP2.time + deltaTime, startP2.price + deltaPrice)
            }
        }
    }

    private fun resetSelectionDrag() {
        selectionDragTarget = null
        isDraggingSelectedLine = false
        dragStartP1 = null
        dragStartP2 = null
    }

    private var pinchPointerId0 = -1
    private var pinchPointerId1 = -1
    private var prevPinchDx = 0f
    private var prevPinchDy = 0f
    private val isPinching get() = pinchPointerId0 != -1 && pinchPointerId1 != -1

    private var pinchIsHorizontal: Boolean? = null

    private val minPinchAxisSeparationPx = dp(12f)

    private val pinchSensitivity = 0.45f

    private fun pinchSeparation(event: MotionEvent): Pair<Float, Float>? {
        val index0 = event.findPointerIndex(pinchPointerId0)
        val index1 = event.findPointerIndex(pinchPointerId1)
        if (index0 < 0 || index1 < 0) return null
        val dx = abs(event.getX(index0) - event.getX(index1))
        val dy = abs(event.getY(index0) - event.getY(index1))
        return dx to dy
    }

    private fun dampedRatio(rawRatio: Float): Float = 1f + (rawRatio - 1f) * pinchSensitivity

    private fun startPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        pinchPointerId0 = event.getPointerId(0)
        pinchPointerId1 = event.getPointerId(1)
        val (dx, dy) = pinchSeparation(event) ?: return
        prevPinchDx = dx
        prevPinchDy = dy
        pinchIsHorizontal = if (max(dx, dy) >= minPinchAxisSeparationPx) dx >= dy else null
    }

    private fun endPinch() {
        pinchPointerId0 = -1
        pinchPointerId1 = -1
        pinchIsHorizontal = null
    }

    private fun updatePinch(event: MotionEvent) {
        val (dx, dy) = pinchSeparation(event) ?: return
        val index0 = event.findPointerIndex(pinchPointerId0)
        val index1 = event.findPointerIndex(pinchPointerId1)
        if (index0 < 0 || index1 < 0) return

        when (pinchIsHorizontal) {
            true -> {
                if (prevPinchDx >= minPinchAxisSeparationPx && dx >= minPinchAxisSeparationPx) {
                    val focusX = (event.getX(index0) + event.getX(index1)) / 2f
                    applyTimeZoom(dampedRatio(dx / prevPinchDx), focusX)
                }
            }
            false -> {
                if (prevPinchDy >= minPinchAxisSeparationPx && dy >= minPinchAxisSeparationPx) {
                    val focusY = (event.getY(index0) + event.getY(index1)) / 2f
                    applyZoom(dampedRatio(dy / prevPinchDy), focusY)
                }
            }
            null -> Unit
        }

        prevPinchDx = dx
        prevPinchDy = dy
    }

    private val panGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {

                if (!isPinching && !isCrosshairActive) {
                    applyPan(distanceY)
                    applyTimePan(distanceX)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetViewport()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {

                if (isPinching) return false
                val hitIndex = findDrawingHit(e.x, e.y)
                if (hitIndex != selectedDrawingIndex) {
                    selectedDrawingIndex = hitIndex
                    invalidate()
                    notifySelectedDrawingChanged()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isPinching || candles.isEmpty()) return
                isCrosshairActive = true
                crosshairRawX = e.x
                crosshairRawY = e.y
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeDrawingTool != DrawingTool.NONE) {
            return handleDrawingTouch(event)
        }

        if (handleSelectionDragTouch(event)) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    startPinch(event)
                    if (isCrosshairActive) {
                        isCrosshairActive = false
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching) {
                    updatePinch(event)
                } else if (isCrosshairActive) {
                    crosshairRawX = event.getX(0)
                    crosshairRawY = event.getY(0)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPinching) endPinch()
                if (isCrosshairActive) {
                    isCrosshairActive = false
                    invalidate()
                }
            }
        }

        if (!isPinching) {
            panGestureDetector.onTouchEvent(event)
        }

        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (activeDrawingTool != DrawingTool.NONE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    drawingCrosshairX = event.x
                    drawingCrosshairY = event.y

                    if (placementPhase == AnchorPlacementPhase.POSITIONING_ANCHOR_2) {
                        pendingDrawing?.let { pending ->
                            pending.p2 = DrawingPoint(screenToTime(event.x), screenToPrice(event.y))
                        }
                    }
                    invalidate()
                }
                else -> Unit
            }
            return true
        }
        return super.onHoverEvent(event)
    }

    private fun handleDrawingTouch(event: MotionEvent): Boolean {
        if (!mapValid) return true
        val tool = activeDrawingTool

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawingCrosshairX = event.x
                drawingCrosshairY = event.y

                if (tool.pointsRequired <= 1) {
                    val point = DrawingPoint(screenToTime(event.x), screenToPrice(event.y))
                    drawings.add(Drawing(tool, point))
                    finishDrawingTool()
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                when (placementPhase) {
                    AnchorPlacementPhase.IDLE -> {

                        val anchor = DrawingPoint(screenToTime(event.x), screenToPrice(event.y))
                        pendingDrawing = Drawing(tool, anchor, anchor)
                        placementPhase = AnchorPlacementPhase.POSITIONING_ANCHOR_1
                    }
                    AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_1 -> {
                        val pending = pendingDrawing
                        if (pending == null) {
                            placementPhase = AnchorPlacementPhase.IDLE
                        } else if (isTouchOnAnchor(event.x, event.y, pending.p1)) {

                            placementPhase = AnchorPlacementPhase.POSITIONING_ANCHOR_1
                        } else {

                            val seed = DrawingPoint(screenToTime(event.x), screenToPrice(event.y))
                            pending.p2 = seed
                            placementPhase = AnchorPlacementPhase.POSITIONING_ANCHOR_2
                        }
                    }
                    AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_2 -> {
                        val pending = pendingDrawing
                        val p2 = pending?.p2
                        if (pending == null || p2 == null) {
                            placementPhase = AnchorPlacementPhase.IDLE
                        } else when {
                            isTouchOnAnchor(event.x, event.y, p2) -> {

                                placementPhase = AnchorPlacementPhase.POSITIONING_ANCHOR_2
                            }
                            isTouchOnAnchor(event.x, event.y, pending.p1) -> {

                                placementPhase = AnchorPlacementPhase.POSITIONING_ANCHOR_1
                            }
                            else -> {

                                drawings.add(pending)
                                pendingDrawing = null
                                placementPhase = AnchorPlacementPhase.IDLE
                                finishDrawingTool()
                            }
                        }
                    }

                    AnchorPlacementPhase.POSITIONING_ANCHOR_1,
                    AnchorPlacementPhase.POSITIONING_ANCHOR_2 -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                drawingCrosshairX = event.x
                drawingCrosshairY = event.y
                val pending = pendingDrawing ?: run { invalidate(); return true }
                val livePoint = DrawingPoint(screenToTime(event.x), screenToPrice(event.y))
                when (placementPhase) {
                    AnchorPlacementPhase.POSITIONING_ANCHOR_1 -> {

                        pending.p1 = livePoint
                        pending.p2 = livePoint
                    }
                    AnchorPlacementPhase.POSITIONING_ANCHOR_2 -> {
                        pending.p2 = livePoint
                    }
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val pending = pendingDrawing
                if (pending != null) {
                    val dropped = DrawingPoint(screenToTime(event.x), screenToPrice(event.y))
                    when (placementPhase) {
                        AnchorPlacementPhase.POSITIONING_ANCHOR_1 -> {

                            pending.p1 = dropped
                            pending.p2 = dropped
                            placementPhase = AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_1
                            drawingCrosshairX = event.x
                            drawingCrosshairY = event.y
                        }
                        AnchorPlacementPhase.POSITIONING_ANCHOR_2 -> {
                            pending.p2 = dropped
                            placementPhase = AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_2
                        }

                        else -> Unit
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                val pending = pendingDrawing
                when {
                    pending == null -> {
                        placementPhase = AnchorPlacementPhase.IDLE
                    }
                    placementPhase == AnchorPlacementPhase.POSITIONING_ANCHOR_1 -> {

                        pendingDrawing = null
                        placementPhase = AnchorPlacementPhase.IDLE
                    }
                    placementPhase == AnchorPlacementPhase.POSITIONING_ANCHOR_2 -> {

                        pending.p2 = pending.p1
                        placementPhase = AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_1
                    }
                    else -> {

                    }
                }
                invalidate()
            }
        }

        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun finishDrawingTool() {
        activeDrawingTool = DrawingTool.NONE
        isDrawingCrosshairActive = false
        hasSeededDrawingCrosshair = false
        placementPhase = AnchorPlacementPhase.IDLE
        onDrawingPlaced?.invoke()
    }

    private fun effectivePriceRange(): ChartPriceRange? = priceRangeOverride ?: ChartPriceRange.from(visibleCandles())

    private fun applyZoom(scaleFactor: Float, focusY: Float) {
        val current = effectivePriceRange() ?: return
        val priceAreaHeight = ChartLayoutMetrics.priceAreaHeightPx(height - timeAxisHeight)
        if (priceAreaHeight <= 0f) return

        val span = current.maxPrice - current.minPrice
        if (span <= 0.0) return
        val focusFractionFromTop = (focusY / priceAreaHeight).toDouble().coerceIn(0.0, 1.0)
        val focusPrice = current.maxPrice - focusFractionFromTop * span

        val newSpan = (span / scaleFactor)
        val newMax = focusPrice + focusFractionFromTop * newSpan
        val newMin = newMax - newSpan
        setOverride(newMin, newMax)
    }

    private fun applyPan(distanceYPx: Float) {
        val current = effectivePriceRange() ?: return
        val priceAreaHeight = ChartLayoutMetrics.priceAreaHeightPx(height - timeAxisHeight)
        if (priceAreaHeight <= 0f) return

        val span = current.maxPrice - current.minPrice
        val deltaPrice = (distanceYPx / priceAreaHeight).toDouble() * span

        setOverride(current.minPrice - deltaPrice, current.maxPrice - deltaPrice)
    }

    private fun setOverride(newMin: Double, newMax: Double) {
        if (newMax <= newMin) return
        val auto = ChartPriceRange.from(visibleCandles()) ?: return
        val autoSpan = auto.maxPrice - auto.minPrice
        if (autoSpan <= 0.0) return

        var span = newMax - newMin
        val minSpan = autoSpan * minZoomSpanFraction
        val maxSpan = autoSpan * maxZoomSpanFraction
        val center = (newMin + newMax) / 2.0
        span = span.coerceIn(minSpan, maxSpan)

        val clamped = ChartPriceRange(center - span / 2.0, center + span / 2.0)
        priceRangeOverride = clamped
        onViewportChange?.invoke(clamped)
        invalidate()
    }

    private fun resetViewport() {
        val hadPriceOverride = priceRangeOverride != null
        val hadTimeOverride = timeRangeOverride != null
        if (!hadPriceOverride && !hadTimeOverride) return

        if (hadPriceOverride) {
            priceRangeOverride = null
            onViewportChange?.invoke(null)
        }
        if (hadTimeOverride) {
            timeRangeOverride = null
            notifyTimeWindowChanged()
        }
        invalidate()
    }

    private fun chartWidthPx(): Float = (width - priceAxisWidth).coerceAtLeast(0f)

    private fun rightOffsetFor(barSpacingPx: Float): Double {
        if (barSpacingPx <= 0f) return 0.0
        return fixedRightOffsetBars ?: (chartWidthPx() * rightMarginFraction / barSpacingPx.toDouble())
    }

    private fun maxRightIndex(barSpacingPx: Float): Double {
        if (candles.isEmpty()) return 0.0
        return candles.lastIndex.toDouble() + rightOffsetFor(barSpacingPx)
    }

    private fun defaultTimeRange(): LogicalTimeRange? {
        if (candles.isEmpty()) return null
        val chartWidth = chartWidthPx()
        if (chartWidth <= 0f) return null

        val count = min(defaultVisibleCandleCount, candles.size).coerceAtLeast(1)
        val barSpacing = chartWidth / count
        return LogicalTimeRange(maxRightIndex(barSpacing), barSpacing)
    }

    private fun effectiveLogicalTimeRange(): LogicalTimeRange? {
        val range = timeRangeOverride ?: return defaultTimeRange()
        return clampTimeRange(range)
    }

    private fun effectiveTimeWindow(): TimeWindow {
        val total = candles.size
        if (total == 0) return TimeWindow(0, 0)

        val range = effectiveLogicalTimeRange() ?: return TimeWindow(0, total)
        val chartWidth = chartWidthPx()
        if (chartWidth <= 0f || range.barSpacingPx <= 0f) return TimeWindow(0, total)

        val leftIndex = range.rightIndex - chartWidth / range.barSpacingPx
        val start = floor(leftIndex).toInt().coerceIn(0, total - 1)
        val end = (ceil(range.rightIndex).toInt() + 1).coerceIn(start + 1, total)
        return TimeWindow(start, end)
    }

    private fun visibleIndexedCandles(range: LogicalTimeRange): List<Pair<Int, Kline>> {
        val window = effectiveTimeWindow()
        if (window.startIndex >= window.endIndexExclusive) return emptyList()
        return candles.subList(window.startIndex, window.endIndexExclusive).mapIndexed { index, candle ->
            window.startIndex + index to candle
        }.filter { (index, _) -> index.toDouble() <= range.rightIndex }
    }

    private fun applyTimeZoom(scaleFactor: Float, focusX: Float) {
        if (candles.isEmpty()) return
        val current = effectiveLogicalTimeRange() ?: return
        val chartWidth = chartWidthPx()
        if (chartWidth <= 0f || current.barSpacingPx <= 0f) return

        val leftIndex = current.rightIndex - chartWidth / current.barSpacingPx
        val focusIndex = leftIndex + focusX.coerceIn(0f, chartWidth) / current.barSpacingPx
        val unclampedSpacing = current.barSpacingPx * scaleFactor
        val nextSpacing = clampedBarSpacing(unclampedSpacing)
        val nextRight = focusIndex + (chartWidth - focusX.coerceIn(0f, chartWidth)) / nextSpacing
        setTimeRangeOverride(LogicalTimeRange(nextRight, nextSpacing))
    }

    private fun applyTimePan(distanceXPx: Float) {
        if (candles.isEmpty()) return
        val current = effectiveLogicalTimeRange() ?: return
        if (current.barSpacingPx <= 0f) return

        val deltaCandles = distanceXPx / current.barSpacingPx
        setTimeRangeOverride(current.copy(rightIndex = current.rightIndex + deltaCandles))
    }

    private fun setTimeRangeOverride(range: LogicalTimeRange) {
        timeRangeOverride = clampTimeRange(range)
        notifyTimeWindowChanged()
        invalidate()
    }

    private fun clampedBarSpacing(barSpacingPx: Float): Float {
        val chartWidth = chartWidthPx()
        if (candles.isEmpty() || chartWidth <= 0f) return barSpacingPx.coerceAtLeast(1f)

        val minVisible = min(minVisibleCandleCount, candles.size).coerceAtLeast(1).toDouble()
        val maxVisible = max(minVisible, candles.size * maxVisibleCandleCountFraction)
        val minSpacing = chartWidth / maxVisible.toFloat()
        val maxSpacing = chartWidth / minVisible.toFloat()
        return barSpacingPx.coerceIn(minSpacing, maxSpacing)
    }

    private fun clampTimeRange(range: LogicalTimeRange): LogicalTimeRange {
        val chartWidth = chartWidthPx()
        if (candles.isEmpty() || chartWidth <= 0f) return range

        val spacing = clampedBarSpacing(range.barSpacingPx)
        val visibleBars = chartWidth / spacing
        val maxRight = maxRightIndex(spacing)
        val minRight = min(maxRight, visibleBars.toDouble())
        val cappedRight = if (fixRightEdge) min(range.rightIndex, maxRight) else range.rightIndex
        return LogicalTimeRange(cappedRight.coerceAtLeast(minRight), spacing)
    }

    private fun clampTimeRangeOverride() {
        timeRangeOverride = timeRangeOverride?.let(::clampTimeRange)
    }

    private fun isAtRightEdge(range: LogicalTimeRange): Boolean =
        candles.isNotEmpty() && abs(range.rightIndex - maxRightIndex(range.barSpacingPx)) < 0.001

    private fun notifyTimeWindowChanged() {
        onTimeWindowChange?.invoke(visibleCandles())
    }

    private var barDurationMillis: Long = 0L

    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTicker = object : Runnable {
        override fun run() {
            invalidate()
            countdownHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        countdownHandler.post(countdownTicker)
        if (isSkeletonLoading) startSkeletonShimmer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        countdownHandler.removeCallbacks(countdownTicker)
        stopSkeletonShimmer()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildSkeletonShimmerShader(w, h)
        if (isSkeletonLoading) {
            stopSkeletonShimmer()
            startSkeletonShimmer()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartRight = width - priceAxisWidth
        val chartBottom = height - timeAxisHeight

        reusableRect.set(chartRight, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(reusableRect, bgPaint)
        reusableRect.set(0f, chartBottom, chartRight, height.toFloat())
        canvas.drawRect(reusableRect, bgPaint)

        val timeRange = effectiveLogicalTimeRange()
        val data = if (timeRange != null) visibleIndexedCandles(timeRange) else emptyList()
        val visibleCandles = data.map { it.second }
        if (data.isEmpty()) {
            mapValid = false

            val label = "Waiting for live candles…"
            val textWidth = emptyStatePaint.measureText(label)
            reusableRect.set(
                width / 2f - textWidth / 2f - dp(10f),
                height / 2f - dp(16f),
                width / 2f + textWidth / 2f + dp(10f),
                height / 2f + dp(8f),
            )
            canvas.drawRoundRect(reusableRect, dp(4f), dp(4f), bgPaint)
            canvas.drawText(label, width / 2f, height / 2f, emptyStatePaint)
            return
        }

        val priceAreaHeight = ChartLayoutMetrics.priceAreaHeightPx(chartBottom)
        val priceAreaBottom = priceAreaHeight
        val volumeAreaTop = priceAreaBottom + dp(6f)
        val volumeAreaBottom = chartBottom

        var maxVolume = 0.0
        for ((_, c) in data) {
            if (c.baseVolume > maxVolume) maxVolume = c.baseVolume
        }
        if (maxVolume <= 0.0) maxVolume = 1.0

        val (minPrice, maxPrice) = priceRangeOverride ?: ChartPriceRange.from(visibleCandles)!!

        fun priceToY(price: Double): Float {
            val ratio = ((maxPrice - price) / (maxPrice - minPrice)).toFloat()
            return ratio * priceAreaHeight
        }

        fun volumeToY(volume: Double): Float {
            val ratio = (volume / maxVolume).toFloat()
            return volumeAreaBottom - ratio * (volumeAreaBottom - volumeAreaTop)
        }

        drawPriceGrid(canvas, chartRight, priceAreaHeight, minPrice, maxPrice)

        val slotWidth = timeRange!!.barSpacingPx
        val leftIndex = timeRange.rightIndex - chartRight / slotWidth

        mapMinPrice = minPrice
        mapMaxPrice = maxPrice
        mapPriceAreaHeight = priceAreaHeight
        mapLeftIndex = leftIndex
        mapSlotWidth = slotWidth
        mapValid = true

        drawTimeAxis(canvas, data, timeRange, chartRight, chartBottom)
        val bodyWidth = max(bodyMinWidthPx, slotWidth * (1f - bodyGapRatio))

        data.forEach { (globalIndex, candle) ->
            val centerX = ((globalIndex - leftIndex) * slotWidth).toFloat()
            val isBull = candle.close >= candle.open
            val wickPaint = when {
                isSkeletonLoading -> skeletonWickPaint
                isBull -> bullWickPaint
                else -> bearWickPaint
            }
            val bodyPaint = when {
                isSkeletonLoading -> skeletonBodyPaint
                isBull -> bullBodyPaint
                else -> bearBodyPaint
            }
            val bodyStrokePaint = when {
                isSkeletonLoading -> skeletonBodyStrokePaint
                isBull -> bullBodyStrokePaint
                else -> bearBodyStrokePaint
            }
            val volumePaint = when {
                isSkeletonLoading -> skeletonVolumePaint
                isBull -> bullVolumePaint
                else -> bearVolumePaint
            }

            canvas.drawLine(
                centerX, priceToY(candle.high),
                centerX, priceToY(candle.low),
                wickShadowPaint,
            )
            canvas.drawLine(
                centerX, priceToY(candle.high),
                centerX, priceToY(candle.low),
                wickPaint,
            )

            val openY = priceToY(candle.open)
            val closeY = priceToY(candle.close)
            val top = min(openY, closeY)
            val bottom = max(openY, closeY)
            reusableRect.set(
                centerX - bodyWidth / 2f,
                top,
                centerX + bodyWidth / 2f,
                if (bottom - top < minBodyPx) top + minBodyPx else bottom,
            )

            reusableShadowRect.set(
                reusableRect.left - shadowPaddingPx,
                reusableRect.top - shadowPaddingPx,
                reusableRect.right + shadowPaddingPx,
                reusableRect.bottom + shadowPaddingPx,
            )
            canvas.drawRect(reusableShadowRect, bodyShadowPaint)
            canvas.drawRect(reusableRect, bodyPaint)
            canvas.drawRect(reusableRect, bodyStrokePaint)

            reusableRect.set(
                centerX - bodyWidth / 2f,
                volumeToY(candle.baseVolume),
                centerX + bodyWidth / 2f,
                volumeAreaBottom,
            )
            canvas.drawRect(reusableRect, volumePaint)
        }

        if (isSkeletonLoading) {
            drawSkeletonShimmer(canvas, chartRight, chartBottom)
        }

        val liveCandle = candles.last()
        val lastPriceLabelY =
            drawLastPriceLine(canvas, liveCandle, chartRight, minPrice, maxPrice, priceAreaHeight, ::priceToY)

        drawBarCloseCountdown(canvas, liveCandle, lastPriceLabelY, chartRight)

        drawUserDrawings(canvas, chartRight, chartBottom)

        drawCrosshair(canvas, timeRange, chartRight, chartBottom, priceAreaHeight, minPrice, maxPrice)
        drawDrawingModeCrosshair(canvas, timeRange, chartRight, chartBottom, priceAreaHeight, minPrice, maxPrice)
    }

    private fun rebuildSkeletonShimmerShader(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bandWidthPx = w * skeletonShimmerBandWidthFraction
        val diagonal = kotlin.math.hypot(w.toFloat(), h.toFloat())
        skeletonShimmerSpan = bandWidthPx + diagonal / 2f
        skeletonShimmerShader = LinearGradient(
            -bandWidthPx / 2f,
            0f,
            bandWidthPx / 2f,
            0f,
            intArrayOf(skeletonBodyColor, skeletonHighlightColor, skeletonBodyColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        skeletonShimmerPaint.shader = skeletonShimmerShader
        updateSkeletonShimmerMatrix()
    }

    private fun startSkeletonShimmer() {
        if (skeletonShimmerAnimator != null) return
        if (width > 0 && height > 0 && skeletonShimmerShader == null) {
            rebuildSkeletonShimmerShader(width, height)
        }
        val w = width.takeIf { it > 0 } ?: return
        val animator = ValueAnimator.ofFloat(-skeletonShimmerSpan, w + skeletonShimmerSpan).apply {
            duration = skeletonShimmerDurationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                skeletonShimmerOffsetX = animation.animatedValue as Float
                updateSkeletonShimmerMatrix()
                invalidate()
            }
        }
        skeletonShimmerAnimator = animator
        animator.start()
    }

    private fun stopSkeletonShimmer() {
        skeletonShimmerAnimator?.let {
            it.removeAllUpdateListeners()
            it.cancel()
        }
        skeletonShimmerAnimator = null
    }

    private fun updateSkeletonShimmerMatrix() {
        val shader = skeletonShimmerShader ?: return
        skeletonShimmerMatrix.reset()
        skeletonShimmerMatrix.postRotate(18f, width / 2f, height / 2f)
        skeletonShimmerMatrix.postTranslate(skeletonShimmerOffsetX - width / 2f, 0f)
        shader.setLocalMatrix(skeletonShimmerMatrix)
    }

    private fun drawSkeletonShimmer(canvas: Canvas, chartRight: Float, chartBottom: Float) {
        if (skeletonShimmerShader == null) rebuildSkeletonShimmerShader(width, height)
        val checkpoint = canvas.save()
        canvas.clipRect(0f, 0f, chartRight, chartBottom)
        canvas.drawRect(0f, 0f, chartRight, chartBottom, skeletonShimmerPaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawCrosshair(
        canvas: Canvas,
        timeRange: LogicalTimeRange,
        chartRight: Float,
        chartBottom: Float,
        priceAreaHeight: Float,
        minPrice: Double,
        maxPrice: Double,
    ) {
        val slotWidth = timeRange.barSpacingPx
        if (!isCrosshairActive || candles.isEmpty() || slotWidth <= 0f) return

        val y = crosshairRawY.coerceIn(0f, priceAreaHeight)
        canvas.drawLine(0f, y, chartRight, y, crosshairLinePaint)

        val leftIndex = timeRange.rightIndex - chartRight / slotWidth
        val index = round(crosshairRawX / slotWidth + leftIndex).toInt().coerceIn(0, candles.size - 1)
        val x = ((index - leftIndex) * slotWidth).toFloat()
        canvas.drawLine(x, 0f, x, chartBottom, crosshairLinePaint)

        val price = maxPrice - (y / priceAreaHeight) * (maxPrice - minPrice)
        val priceLabel = formatPrice(price)
        val priceTextWidth = crosshairPriceTextPaint.measureText(priceLabel)
        val priceLabelLeft = chartRight
        val priceLabelRight = chartRight + priceTextWidth + dp(12f)
        reusableRect.set(priceLabelLeft, y - dp(9f), priceLabelRight, y + dp(9f))
        canvas.drawRect(reusableRect, crosshairLabelBgPaint)
        canvas.drawText(
            priceLabel,
            priceLabelLeft + dp(6f),
            y + crosshairPriceTextPaint.textSize / 3f,
            crosshairPriceTextPaint,
        )

        val timeLabel = crosshairTimeFormat.format(java.util.Date(candles[index].startTime))
        val timeTextWidth = crosshairTimeTextPaint.measureText(timeLabel)
        val pillHalfWidth = timeTextWidth / 2f + dp(6f)
        reusableRect.set(x - pillHalfWidth, chartBottom, x + pillHalfWidth, chartBottom + timeAxisHeight)
        canvas.drawRect(reusableRect, crosshairLabelBgPaint)
        canvas.drawText(
            timeLabel,
            x,
            chartBottom + timeAxisHeight / 2f + crosshairTimeTextPaint.textSize / 3f,
            crosshairTimeTextPaint,
        )
    }

    private fun drawDrawingModeCrosshair(
        canvas: Canvas,
        timeRange: LogicalTimeRange,
        chartRight: Float,
        chartBottom: Float,
        priceAreaHeight: Float,
        minPrice: Double,
        maxPrice: Double,
    ) {
        val slotWidth = timeRange.barSpacingPx
        if (!isDrawingCrosshairActive || candles.isEmpty() || slotWidth <= 0f) return

        val y = drawingCrosshairY.coerceIn(0f, priceAreaHeight)
        canvas.drawLine(0f, y, chartRight, y, crosshairLinePaint)

        val leftIndex = timeRange.rightIndex - chartRight / slotWidth
        val index = round(drawingCrosshairX / slotWidth + leftIndex).toInt().coerceIn(0, candles.size - 1)
        val x = ((index - leftIndex) * slotWidth).toFloat()
        canvas.drawLine(x, 0f, x, chartBottom, crosshairLinePaint)

        canvas.drawCircle(x, y, dp(3f), drawingPointPaint)

        val price = maxPrice - (y / priceAreaHeight) * (maxPrice - minPrice)
        val priceLabel = formatPrice(price)
        val priceTextWidth = crosshairPriceTextPaint.measureText(priceLabel)
        reusableRect.set(chartRight, y - dp(9f), chartRight + priceTextWidth + dp(12f), y + dp(9f))
        canvas.drawRect(reusableRect, crosshairLabelBgPaint)
        canvas.drawText(
            priceLabel,
            chartRight + dp(6f),
            y + crosshairPriceTextPaint.textSize / 3f,
            crosshairPriceTextPaint,
        )

        val timeLabel = crosshairTimeFormat.format(java.util.Date(candles[index].startTime))
        val timeTextWidth = crosshairTimeTextPaint.measureText(timeLabel)
        val pillHalfWidth = timeTextWidth / 2f + dp(6f)
        reusableRect.set(x - pillHalfWidth, chartBottom, x + pillHalfWidth, chartBottom + timeAxisHeight)
        canvas.drawRect(reusableRect, crosshairLabelBgPaint)
        canvas.drawText(
            timeLabel,
            x,
            chartBottom + timeAxisHeight / 2f + crosshairTimeTextPaint.textSize / 3f,
            crosshairTimeTextPaint,
        )
    }

    private fun drawUserDrawings(canvas: Canvas, chartRight: Float, chartBottom: Float) {
        if (!mapValid) return
        var selectedBounds: RectF? = null
        drawings.forEachIndexed { index, drawing ->
            renderDrawing(canvas, drawing, chartRight, chartBottom, isPending = false)
            if (index == selectedDrawingIndex) {
                drawSelectionOverlay(canvas, drawing)
                selectedBounds = selectionScreenBounds(drawing)
            }
        }
        pendingDrawing?.let { pending ->
            renderDrawing(canvas, pending, chartRight, chartBottom, isPending = true)

            when (placementPhase) {
                AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_1 -> {
                    drawSelectionHandle(canvas, timeToScreenX(pending.p1.time), priceToScreenY(pending.p1.price))
                }
                AnchorPlacementPhase.AWAITING_CONFIRM_ANCHOR_2 -> {
                    pending.p2?.let { p2 ->
                        drawSelectionHandle(canvas, timeToScreenX(p2.time), priceToScreenY(p2.price))
                    }
                }
                else -> Unit
            }
        }

        if (selectedDrawingIndex != null) onSelectedDrawingBoundsChanged?.invoke(selectedBounds)
    }

    private fun drawSelectionOverlay(canvas: Canvas, drawing: Drawing) {
        val x1 = timeToScreenX(drawing.p1.time)
        val y1 = priceToScreenY(drawing.p1.price)
        val area = contentAreaPx()
        configureStylePaint(styleSelectedPaint, drawing, extraWidthDp = 1f)

        val p2 = drawing.p2
        if (p2 == null) {
            when (drawing.tool) {
                DrawingTool.HORIZONTAL_LINE -> canvas.drawLine(area.left, y1, area.right, y1, styleSelectedPaint)
                DrawingTool.HORIZONTAL_RAY -> canvas.drawLine(x1, y1, area.right, y1, styleSelectedPaint)
                DrawingTool.VERTICAL_LINE -> canvas.drawLine(x1, area.top, x1, area.bottom, styleSelectedPaint)
                DrawingTool.CROSS_LINE -> {
                    canvas.drawLine(area.left, y1, area.right, y1, styleSelectedPaint)
                    canvas.drawLine(x1, area.top, x1, area.bottom, styleSelectedPaint)
                }
                else -> Unit
            }
            drawSelectionHandle(canvas, x1, y1)
            return
        }

        val x2 = timeToScreenX(p2.time)
        val y2 = priceToScreenY(p2.price)
        when (drawing.tool) {
            DrawingTool.RAY -> {
                val (ex, ey) = extendToBoundary(x1, y1, x2, y2, area.right, area.bottom)
                canvas.drawLine(x1, y1, ex, ey, styleSelectedPaint)
            }
            DrawingTool.EXTENDED_LINE -> {
                val (fx, fy) = extendToBoundary(x1, y1, x2, y2, area.right, area.bottom)
                val (bx, by) = extendToBoundary(x2, y2, x1, y1, area.right, area.bottom)
                canvas.drawLine(bx, by, fx, fy, styleSelectedPaint)
            }
            else -> canvas.drawLine(x1, y1, x2, y2, styleSelectedPaint)
        }
        drawSelectionHandle(canvas, x1, y1)
        drawSelectionHandle(canvas, x2, y2)
    }

    private fun drawSelectionHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadiusPx, selectionHandleFillPaint)
        canvas.drawCircle(x, y, handleRadiusPx, selectionHandleStrokePaint)
    }

    private fun renderDrawing(canvas: Canvas, drawing: Drawing, chartRight: Float, chartBottom: Float, isPending: Boolean) {
        val p2 = drawing.p2 ?: drawing.p1
        val x1 = timeToScreenX(drawing.p1.time)
        val y1 = priceToScreenY(drawing.p1.price)
        val x2 = timeToScreenX(p2.time)
        val y2 = priceToScreenY(p2.price)

        val linePaint: Paint
        val dotPaint: Paint
        if (isPending) {
            linePaint = drawingPendingLinePaint
            dotPaint = drawingPointPaint
        } else {
            configureStylePaint(stylePaint, drawing)
            linePaint = stylePaint
            styleDotPaint.color = drawing.color
            styleDotPaint.alpha = (drawing.opacityPercent.coerceIn(0, 100) * 255 / 100)
            dotPaint = styleDotPaint
        }

        when (drawing.tool) {
            DrawingTool.TREND_LINE -> {
                canvas.drawLine(x1, y1, x2, y2, linePaint)
                drawDrawingDot(canvas, x1, y1, dotPaint)
                drawDrawingDot(canvas, x2, y2, dotPaint)
            }
            DrawingTool.RAY -> {
                val (ex, ey) = extendToBoundary(x1, y1, x2, y2, chartRight, chartBottom)
                canvas.drawLine(x1, y1, ex, ey, linePaint)
                drawDrawingDot(canvas, x1, y1, dotPaint)
            }
            DrawingTool.EXTENDED_LINE -> {
                val (fx, fy) = extendToBoundary(x1, y1, x2, y2, chartRight, chartBottom)
                val (bx, by) = extendToBoundary(x2, y2, x1, y1, chartRight, chartBottom)
                canvas.drawLine(bx, by, fx, fy, linePaint)
            }
            DrawingTool.INFO_LINE -> {
                canvas.drawLine(x1, y1, x2, y2, linePaint)
                drawDrawingDot(canvas, x1, y1, dotPaint)
                drawDrawingDot(canvas, x2, y2, dotPaint)
                if (!isPending || drawing.p2 != null) drawInfoLineLabel(canvas, drawing.p1, p2, x1, y1, x2, y2)
            }
            DrawingTool.TREND_ANGLE -> {
                canvas.drawLine(x1, y1, x2, y2, linePaint)
                drawDrawingDot(canvas, x1, y1, dotPaint)
                drawDrawingDot(canvas, x2, y2, dotPaint)
                if (!isPending || drawing.p2 != null) drawAngleLabel(canvas, x1, y1, x2, y2)
            }
            DrawingTool.HORIZONTAL_LINE -> {
                canvas.drawLine(0f, y1, chartRight, y1, linePaint)
                drawDrawingPriceTag(canvas, y1, drawing.p1.price, chartRight)
            }
            DrawingTool.HORIZONTAL_RAY -> {
                canvas.drawLine(x1, y1, chartRight, y1, linePaint)
                drawDrawingDot(canvas, x1, y1, dotPaint)
                drawDrawingPriceTag(canvas, y1, drawing.p1.price, chartRight)
            }
            DrawingTool.VERTICAL_LINE -> {
                canvas.drawLine(x1, 0f, x1, chartBottom, linePaint)
            }
            DrawingTool.CROSS_LINE -> {
                canvas.drawLine(0f, y1, chartRight, y1, linePaint)
                canvas.drawLine(x1, 0f, x1, chartBottom, linePaint)
                drawDrawingDot(canvas, x1, y1, dotPaint)
                drawDrawingPriceTag(canvas, y1, drawing.p1.price, chartRight)
            }
            DrawingTool.NONE -> Unit
        }
    }

    private fun extendToBoundary(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        right: Float,
        bottom: Float,
    ): Pair<Float, Float> {
        val dx = toX - fromX
        val dy = toY - fromY
        if (dx == 0f && dy == 0f) return toX to toY

        var tMax = Float.MAX_VALUE
        if (dx > 0f) tMax = min(tMax, (right - toX) / dx) else if (dx < 0f) tMax = min(tMax, (0f - toX) / dx)
        if (dy > 0f) tMax = min(tMax, (bottom - toY) / dy) else if (dy < 0f) tMax = min(tMax, (0f - toY) / dy)
        if (tMax == Float.MAX_VALUE || tMax < 0f) return toX to toY

        return (toX + dx * tMax) to (toY + dy * tMax)
    }

    private fun drawDrawingDot(canvas: Canvas, x: Float, y: Float, paint: Paint = drawingPointPaint) {
        canvas.drawCircle(x, y, dp(3f), paint)
    }

    private fun drawDrawingPriceTag(canvas: Canvas, y: Float, price: Double, chartRight: Float) {
        val label = formatPrice(price)
        val textWidth = drawingLabelTextPaint.measureText(label)
        val left = chartRight
        val right = chartRight + textWidth + dp(12f)
        reusableRect.set(left, y - dp(9f), right, y + dp(9f))
        canvas.drawRect(reusableRect, drawingLabelBgPaint)
        canvas.drawText(label, left + dp(6f), y + drawingLabelTextPaint.textSize / 3f, drawingLabelTextPaint)
    }

    private fun drawPillLabel(canvas: Canvas, centerX: Float, centerY: Float, label: String) {
        val textWidth = drawingLabelTextPaint.measureText(label)
        val halfHeight = dp(9f)
        reusableRect.set(
            centerX - textWidth / 2f - dp(6f),
            centerY - halfHeight,
            centerX + textWidth / 2f + dp(6f),
            centerY + halfHeight,
        )
        canvas.drawRoundRect(reusableRect, dp(4f), dp(4f), drawingLabelBgPaint)
        canvas.drawText(
            label,
            centerX - textWidth / 2f,
            centerY + drawingLabelTextPaint.textSize / 3f,
            drawingLabelTextPaint,
        )
    }

    private fun drawInfoLineLabel(
        canvas: Canvas,
        p1: DrawingPoint,
        p2: DrawingPoint,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        val priceDiff = p2.price - p1.price
        val pct = if (p1.price != 0.0) priceDiff / p1.price * 100.0 else 0.0
        val barDuration = currentBarDurationMillis()
        val bars = if (barDuration > 0L) (abs(p2.time - p1.time).toDouble() / barDuration).roundToInt() else 0
        val sign = if (priceDiff >= 0) "+" else ""
        val label = String.format(
            java.util.Locale.US,
            "%s%s (%s%.2f%%), %d bars",
            sign,
            formatPrice(priceDiff),
            sign,
            pct,
            bars,
        )
        drawPillLabel(canvas, (x1 + x2) / 2f, (y1 + y2) / 2f - dp(16f), label)
    }

    private fun drawAngleLabel(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angleDeg = Math.toDegrees(atan2((y1 - y2).toDouble(), (x2 - x1).toDouble()))
        val label = String.format(java.util.Locale.US, "%.1f°", angleDeg)
        drawPillLabel(canvas, (x1 + x2) / 2f, (y1 + y2) / 2f - dp(16f), label)
    }

    private fun dynamicGridLineCount(priceAreaHeight: Float, minPrice: Double, maxPrice: Double): Int {
        val currentSpan = maxPrice - minPrice
        if (currentSpan <= 0.0) return baseGridLineCount
        val auto = ChartPriceRange.from(visibleCandles())
        val autoSpan = if (auto != null && auto.maxPrice > auto.minPrice) auto.maxPrice - auto.minPrice else currentSpan
        val zoomRatio = autoSpan / currentSpan

        val zoomScaled = (baseGridLineCount * zoomRatio).roundToInt()
        val pixelCap = if (minGridLineSpacingPx > 0f) {
            (priceAreaHeight / minGridLineSpacingPx).toInt()
        } else {
            maxGridLineCount
        }
        val upperBound = min(maxGridLineCount, max(minGridLineCount, pixelCap))
        return zoomScaled.coerceIn(minGridLineCount, upperBound)
    }

    private fun drawPriceGrid(
        canvas: Canvas,
        chartRight: Float,
        priceAreaHeight: Float,
        minPrice: Double,
        maxPrice: Double,
    ) {
        val lineCount = dynamicGridLineCount(priceAreaHeight, minPrice, maxPrice)
        for (i in 0..lineCount) {
            val y = priceAreaHeight * i / lineCount
            canvas.drawLine(0f, y, chartRight, y, gridPaint)
            val price = maxPrice - (maxPrice - minPrice) * i / lineCount
            canvas.drawText(
                formatPrice(price),
                chartRight + dp(6f),
                y + axisTextPaint.textSize / 3f,
                axisTextPaint,
            )
        }
    }

    private fun drawTimeAxis(
        canvas: Canvas,
        data: List<Pair<Int, Kline>>,
        timeRange: LogicalTimeRange,
        chartRight: Float,
        chartBottom: Float,
    ) {
        val slotWidth = timeRange.barSpacingPx
        if (data.size < 2 || slotWidth <= 0f) return
        val barMillis = estimatedBarDurationMillis(data.map { it.second })
        if (barMillis <= 0L) return

        val intervalMillis = timeAxisIntervalMillis(barMillis, slotWidth)
        val labelBaseline = chartBottom + timeAxisHeight / 2f + timeAxisTextPaint.textSize / 3f
        val leftIndex = timeRange.rightIndex - chartRight / slotWidth

        var lastLabeledDayKey = Long.MIN_VALUE
        data.forEach { (globalIndex, candle) ->
            if (!isTickBoundary(candle.startTime, intervalMillis)) return@forEach

            val x = ((globalIndex - leftIndex) * slotWidth).toFloat()
            canvas.drawLine(x, 0f, x, chartBottom, gridPaint)

            val dayKey = candle.startTime / 86_400_000L
            val label = formatAxisTime(candle.startTime, intervalMillis, isNewDay = dayKey != lastLabeledDayKey)
            lastLabeledDayKey = dayKey
            canvas.drawText(label, x, labelBaseline, timeAxisTextPaint)
        }
    }

    private fun estimatedBarDurationMillis(data: List<Kline>): Long {
        if (barDurationMillis > 0L) return barDurationMillis
        val inferred = data[1].startTime - data[0].startTime
        return if (inferred > 0L) inferred else 0L
    }

    private fun timeAxisIntervalMillis(barMillis: Long, slotWidth: Float): Long {
        for (interval in niceTimeIntervalsMillis) {
            if (interval < barMillis) continue
            val candlesPerTick = interval / barMillis
            if (candlesPerTick < 1L) continue
            val pixelSpacing = candlesPerTick * slotWidth
            if (pixelSpacing >= minTimeLabelSpacingPx) return interval
        }
        return niceTimeIntervalsMillis.last()
    }

    private fun isTickBoundary(startTime: Long, intervalMillis: Long): Boolean =
        intervalMillis > 0L && startTime % intervalMillis == 0L

    private fun formatAxisTime(startTime: Long, intervalMillis: Long, isNewDay: Boolean): String {
        val date = java.util.Date(startTime)
        return when {
            intervalMillis >= 365L * 86_400_000L -> yearFormat.format(date)
            intervalMillis >= 86_400_000L -> dayMonthFormat.format(date)
            isNewDay -> dayMonthFormat.format(date)
            else -> timeOfDayFormat.format(date)
        }
    }

    private fun drawLastPriceLine(
        canvas: Canvas,
        lastCandle: Kline,
        chartRight: Float,
        minPrice: Double,
        maxPrice: Double,
        priceAreaHeight: Float,
        priceToY: (Double) -> Float,
    ): Float {
        val activeColor = if (lastCandle.close >= lastCandle.open) bullColor else bearColor
        lastPricePaint.color = activeColor
        lastPriceBgPaint.color = activeColor

        val clamped = lastCandle.close.coerceIn(minPrice, maxPrice)
        val y = priceToY(clamped)
        canvas.drawLine(0f, y, chartRight, y, lastPricePaint)

        val label = formatPrice(lastCandle.close)
        val textWidth = lastPriceTextPaint.measureText(label)
        val labelLeft = chartRight
        val labelRight = chartRight + textWidth + dp(12f)
        reusableRect.set(labelLeft, y - dp(9f), labelRight, y + dp(9f))
        canvas.drawRect(reusableRect, lastPriceBgPaint)
        canvas.drawText(label, labelLeft + dp(6f), y + lastPriceTextPaint.textSize / 3f, lastPriceTextPaint)
        return y
    }

    private fun drawBarCloseCountdown(
        canvas: Canvas,
        lastCandle: Kline,
        priceLabelY: Float,
        chartRight: Float,
    ) {
        val duration = barDurationMillis
        if (duration <= 0L) return

        val closeAt = lastCandle.startTime + duration
        val remainingMs = closeAt - System.currentTimeMillis()
        val label = formatCountdown(remainingMs)

        val urgent = remainingMs in 0..10_000L
        countdownBgPaint.color = if (urgent) countdownUrgentColor else lastPriceColor

        val textWidth = countdownTextPaint.measureText(label)
        val paddingH = dp(6f)
        val pillHalfHeight = dp(8f)
        val pillWidth = textWidth + paddingH * 2f

        val left = chartRight
        val right = chartRight + pillWidth
        val gap = dp(4f)
        val priceLabelHalfHeight = dp(9f)
        val centerY = priceLabelY + priceLabelHalfHeight + gap + pillHalfHeight

        reusableRect.set(left, centerY - pillHalfHeight, right, centerY + pillHalfHeight)
        canvas.drawRoundRect(reusableRect, dp(3f), dp(3f), countdownBgPaint)
        canvas.drawText(
            label,
            (left + right) / 2f,
            centerY + countdownTextPaint.textSize / 3f,
            countdownTextPaint,
        )
    }

    private fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = max(0L, remainingMs) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun formatPrice(price: Double): String {
        val abs = abs(price)
        val decimals = when {
            abs >= 1000 -> 1
            abs >= 1 -> 2
            else -> 5
        }
        return String.format("%,.${decimals}f", price)
    }
}
