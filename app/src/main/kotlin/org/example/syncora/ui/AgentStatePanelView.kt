package org.example.syncora.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.example.syncora.rrl.RrlPerformanceSummary
import org.example.syncora.rrl.RrlStepResult
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/**
 * Right-hand column of [QuickTradePanel]: live view into what the RRL agent is
 * doing right now (position, expected return, utility, risk appetite, information
 * ratio) plus a rolling chart of the cumulative PnL decomposition (price return,
 * execution cost, funding carry, net reward), mirroring the paper's Figure 3 /
 * Table 1. Designed to update on every agent step so parameter changes made in
 * the left column are immediately visible here.
 */
class AgentStatePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelColor = Color.parseColor("#B2B5BE")
    private val valueColor = Color.parseColor("#EAECEF")
    private val positiveColor = Color.parseColor("#26A69A")
    private val negativeColor = Color.parseColor("#EF5350")

    private class StatCell(val labelView: TextView, val valueView: TextView)

    private val positionCell: StatCell
    private val infoRatioCell: StatCell
    private val expectedReturnCell: StatCell
    private val riskAppetiteCell: StatCell
    private val utilityCell: StatCell
    private val stepsCell: StatCell
    private val avgPositionCell: StatCell
    private val rewardCell: StatCell

    private val chart = CumulativePnlChartView(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(10), dp(14), dp(14))

        addView(
            TextView(context).apply {
                text = "Agent State"
                textSize = 12f
                setTextColor(labelColor)
                setPadding(0, 0, 0, dp(8))
            },
        )

        val grid = GridLayout(context).apply {
            columnCount = 2
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        positionCell = addStatCell(grid, "Position", row = 0, col = 0)
        infoRatioCell = addStatCell(grid, "Info Ratio", row = 0, col = 1)
        expectedReturnCell = addStatCell(grid, "Exp. Return", row = 1, col = 0)
        riskAppetiteCell = addStatCell(grid, "Risk Appetite", row = 1, col = 1)
        utilityCell = addStatCell(grid, "Utility", row = 2, col = 0)
        stepsCell = addStatCell(grid, "Steps", row = 2, col = 1)
        avgPositionCell = addStatCell(grid, "Avg Position", row = 3, col = 0)
        rewardCell = addStatCell(grid, "Last Reward", row = 3, col = 1)
        addView(grid)

        addView(
            TextView(context).apply {
                text = "Cumulative PnL Decomposition"
                textSize = 11f
                setTextColor(labelColor)
                setPadding(0, dp(14), 0, dp(4))
            },
        )
        addView(
            chart.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(140))
            },
        )
        addView(buildLegend())
    }

    private fun addStatCell(grid: GridLayout, label: String, row: Int, col: Int): StatCell {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(4), dp(14), dp(4))
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(labelColor)
        }
        val valueView = TextView(context).apply {
            text = "\u2014"
            textSize = 13f
            setTextColor(valueColor)
        }
        container.addView(labelView)
        container.addView(valueView)
        container.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(row),
            GridLayout.spec(col, 1f),
        )
        grid.addView(container)
        return StatCell(labelView, valueView)
    }

    private fun buildLegend(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(legendEntry("Reward", CumulativePnlChartView.rewardColor))
        row.addView(legendEntry("Price", CumulativePnlChartView.priceColor))
        row.addView(legendEntry("Funding", CumulativePnlChartView.fundingColor))
        row.addView(legendEntry("Exec Cost", CumulativePnlChartView.costColor))
        return row
    }

    private fun legendEntry(label: String, color: Int): View {
        val wrap = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        wrap.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(4) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            },
        )
        wrap.addView(
            TextView(context).apply {
                text = label
                textSize = 9f
                setTextColor(labelColor)
            },
        )
        return wrap
    }

    /** Call on every new agent step / performance update. Safe to call with a null [step] before warmup. */
    fun render(step: RrlStepResult?, performance: RrlPerformanceSummary) {
        positionCell.valueView.apply {
            text = step?.let { "%.3f".format(it.position) } ?: "\u2014"
            setTextColor(colorForSign(step?.position))
        }
        infoRatioCell.valueView.text = step?.let { "%.2f".format(it.informationRatio) } ?: "\u2014"
        expectedReturnCell.valueView.apply {
            text = step?.let { "%.5f".format(it.expectedReturn) } ?: "\u2014"
            setTextColor(colorForSign(step?.expectedReturn))
        }
        riskAppetiteCell.valueView.text = step?.let { formatRiskAppetite(it.riskAppetite) } ?: "\u2014"
        utilityCell.valueView.apply {
            text = step?.let { "%.6f".format(it.utility) } ?: "\u2014"
            setTextColor(colorForSign(step?.utility))
        }
        stepsCell.valueView.text = performance.steps.toString()
        avgPositionCell.valueView.text = "%.3f".format(performance.averagePosition)
        rewardCell.valueView.apply {
            text = step?.let { "%.5f".format(it.reward) } ?: "\u2014"
            setTextColor(colorForSign(step?.reward))
        }

        chart.pushSample(performance)
    }

    fun reset() {
        chart.reset()
    }

    private fun colorForSign(value: Double?): Int = when {
        value == null -> valueColor
        value > 0.0 -> positiveColor
        value < 0.0 -> negativeColor
        else -> valueColor
    }

    private fun formatRiskAppetite(value: Double): String =
        if (value != 0.0 && kotlin.math.abs(value) < 0.001) "%.2e".format(value) else "%.5f".format(value)
}

/**
 * Small rolling multi-line chart of cumulative price return, execution cost,
 * funding carry and net reward, fed one point per agent step via [pushSample].
 */
private class CumulativePnlChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        const val MAX_POINTS = 300
        val rewardColor: Int = Color.parseColor("#EAECEF")
        val priceColor: Int = Color.parseColor("#5B8DEF")
        val fundingColor: Int = Color.parseColor("#26A69A")
        val costColor: Int = Color.parseColor("#EF5350")
    }

    private data class Sample(val price: Double, val cost: Double, val funding: Double, val reward: Double)

    private val history = ArrayDeque<Sample>()

    private val zeroLinePaint = Paint().apply {
        color = Color.parseColor("#2A3542")
        strokeWidth = 1f
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun pushSample(performance: RrlPerformanceSummary) {
        if (history.size >= MAX_POINTS) history.removeFirst()
        history.addLast(
            Sample(
                price = performance.cumulativePriceReturn,
                cost = -performance.cumulativeExecutionCost,
                funding = performance.cumulativeFundingCarry,
                reward = performance.cumulativeReward,
            ),
        )
        invalidate()
    }

    fun reset() {
        history.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (history.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()

        var minValue = 0.0
        var maxValue = 0.0
        for (sample in history) {
            minValue = min(minValue, min(sample.price, min(sample.cost, min(sample.funding, sample.reward))))
            maxValue = max(maxValue, max(sample.price, max(sample.cost, max(sample.funding, sample.reward))))
        }
        if (maxValue == minValue) {
            maxValue += 1.0
            minValue -= 1.0
        }
        val range = (maxValue - minValue).coerceAtLeast(1e-9)

        fun yOf(value: Double): Float = (h - ((value - minValue) / range) * h).toFloat()
        fun xOf(index: Int): Float = (index.toFloat() / (history.size - 1)) * w

        canvas.drawLine(0f, yOf(0.0), w, yOf(0.0), zeroLinePaint)

        drawSeries(canvas, history.map { it.price }, priceColor, 2.5f, ::xOf, ::yOf)
        drawSeries(canvas, history.map { it.cost }, costColor, 2.5f, ::xOf, ::yOf)
        drawSeries(canvas, history.map { it.funding }, fundingColor, 2.5f, ::xOf, ::yOf)
        drawSeries(canvas, history.map { it.reward }, rewardColor, 3.5f, ::xOf, ::yOf)
    }

    private fun drawSeries(
        canvas: Canvas,
        values: List<Double>,
        color: Int,
        strokeWidthPx: Float,
        xOf: (Int) -> Float,
        yOf: (Double) -> Float,
    ) {
        linePaint.color = color
        linePaint.strokeWidth = strokeWidthPx
        val path = Path()
        values.forEachIndexed { index, value ->
            val px = xOf(index)
            val py = yOf(value)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)
    }
}
