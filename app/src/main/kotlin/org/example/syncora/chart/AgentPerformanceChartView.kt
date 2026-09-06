package org.example.syncora.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import org.example.syncora.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A compact, modern 2D line chart for the RRL agent's performance metrics.
 *
 * - Smooth single-color line (Catmull-Rom spline through the samples).
 * - Soft gradient fill directly beneath the line, most opaque near the line and fading to
 *   transparent toward the bottom of the plot area (a subtle "glow" effect).
 * - Line/fill color is green when the latest performance value is non-negative and red when it's
 *   negative.
 * - X and Y axis lines are drawn with tick labels derived from the data, plus axis titles
 *   supplied by the caller. No chart title is rendered.
 */
class AgentPerformanceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")
    private val axisColor = Color.parseColor("#2A2E39")
    private val gridColor = Color.parseColor("#1E222D")
    private val tickTextColor = Color.parseColor("#787B86")
    private val axisTitleColor = Color.parseColor("#B2B5BE")
    private val emptyStateColor = Color.parseColor("#787B86")

    private var points: List<AgentPerformancePoint> = emptyList()
    private var xAxisLabel: String = ""
    private var yAxisLabel: String = ""

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = axisColor
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = gridColor
    }

    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tickTextColor
        textSize = sp(9f)
    }

    private val axisTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = axisTitleColor
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emptyStateColor
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    init {
        setWillNotDraw(false)
        ResourcesCompat.getFont(context, R.font.inter_regular)?.let {
            tickTextPaint.typeface = it
            emptyStatePaint.typeface = it
        }
        ResourcesCompat.getFont(context, R.font.inter_medium)?.let {
            axisTitlePaint.typeface = it
        }
        linePaint.strokeWidth = dp(2f)
        axisPaint.strokeWidth = dp(1f)
        gridPaint.strokeWidth = dp(1f)
    }

    /** Feeds new performance samples into the chart. [xAxisLabel]/[yAxisLabel] label the axes. */
    fun submit(points: List<AgentPerformancePoint>, xAxisLabel: String, yAxisLabel: String) {
        this.points = points.sortedBy { it.x }
        this.xAxisLabel = xAxisLabel
        this.yAxisLabel = yAxisLabel
        invalidate()
    }

    private fun formatValue(value: Float): String {
        val rounded = value.toDouble()
        return if (abs(rounded - rounded.roundToLong()) < 0.005) {
            rounded.roundToLong().toString()
        } else {
            String.format(Locale.US, "%.2f", rounded)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val samples = points
        if (samples.size < 2) {
            canvas.drawText("No performance data yet", w / 2f, h / 2f, emptyStatePaint)
            return
        }

        val leftMargin = dp(38f)
        val rightMargin = dp(6f)
        val topMargin = dp(6f)
        val bottomMargin = dp(30f)

        val plotLeft = leftMargin
        val plotRight = (w - rightMargin).coerceAtLeast(plotLeft + dp(1f))
        val plotTop = topMargin
        val plotBottom = (h - bottomMargin).coerceAtLeast(plotTop + dp(1f))

        val minX = samples.first().x
        val maxX = samples.last().x
        val xRange = (maxX - minX).takeIf { it > 0f } ?: 1f

        val rawMinY = samples.minOf { it.y }
        val rawMaxY = samples.maxOf { it.y }
        val span = (rawMaxY - rawMinY).takeIf { it > 0f } ?: (abs(rawMaxY).coerceAtLeast(1f))
        val padding = span * 0.15f
        val minY = rawMinY - padding
        val maxY = rawMaxY + padding
        val yRange = (maxY - minY).takeIf { it > 0f } ?: 1f

        fun xToPx(x: Float) = plotLeft + (x - minX) / xRange * (plotRight - plotLeft)
        fun yToPx(y: Float) = plotBottom - (y - minY) / yRange * (plotBottom - plotTop)

        // Determine trend color: green for non-negative latest performance, red for negative.
        val isPositive = samples.last().y >= 0f
        val lineColor = if (isPositive) bullColor else bearColor

        // Soft horizontal guide lines at min / mid / max.
        val yTickValues = listOf(rawMaxY, (rawMaxY + rawMinY) / 2f, rawMinY)
        for (value in yTickValues) {
            val py = yToPx(value)
            canvas.drawLine(plotLeft, py, plotRight, py, gridPaint)
        }

        // Axes.
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)

        // Smooth line through the samples.
        val pixelPoints = samples.map { PointF(xToPx(it.x), yToPx(it.y)) }
        val linePath = buildSmoothPath(pixelPoints)

        // Gradient fill beneath the line: opaque near the line, fading to transparent at bottom.
        val fillPath = Path(linePath).apply {
            lineTo(pixelPoints.last().x, plotBottom)
            lineTo(pixelPoints.first().x, plotBottom)
            close()
        }
        fillPaint.shader = LinearGradient(
            0f, plotTop, 0f, plotBottom,
            Color.argb(140, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
            Color.argb(0, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = lineColor
        canvas.drawPath(linePath, linePaint)

        // Y-axis tick labels (min / mid / max), right-aligned just left of the axis.
        tickTextPaint.textAlign = Paint.Align.RIGHT
        val yLabelX = plotLeft - dp(4f)
        for (value in yTickValues) {
            val py = yToPx(value)
            val textY = py.coerceIn(plotTop + tickTextPaint.textSize / 2f, plotBottom)
            canvas.drawText(formatValue(value), yLabelX, textY, tickTextPaint)
        }

        // X-axis tick labels (first / last), aligned under the axis ends.
        val firstLabel = formatValue(minX)
        val lastLabel = formatValue(maxX)
        val tickLabelY = plotBottom + dp(4f) + tickTextPaint.textSize
        tickTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(firstLabel, plotLeft, tickLabelY, tickTextPaint)
        tickTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(lastLabel, plotRight, tickLabelY, tickTextPaint)

        // Axis titles.
        if (xAxisLabel.isNotEmpty()) {
            canvas.drawText(xAxisLabel, (plotLeft + plotRight) / 2f, h - dp(2f), axisTitlePaint)
        }
        if (yAxisLabel.isNotEmpty()) {
            canvas.save()
            canvas.translate(dp(11f), (plotTop + plotBottom) / 2f)
            canvas.rotate(-90f)
            canvas.drawText(yAxisLabel, 0f, 0f, axisTitlePaint)
            canvas.restore()
        }
    }

    /** Builds a smooth Catmull-Rom spline (converted to cubic beziers) through [pts]. */
    private fun buildSmoothPath(pts: List<PointF>): Path {
        val path = Path()
        if (pts.isEmpty()) return path
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) return path
        if (pts.size == 2) {
            path.lineTo(pts[1].x, pts[1].y)
            return path
        }
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[i] else pts[i - 1]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = if (i + 2 < pts.size) pts[i + 2] else p2
            val cp1x = p1.x + (p2.x - p0.x) / 6f
            val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f
            val cp2y = p2.y - (p3.y - p1.y) / 6f
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
        return path
    }
}
