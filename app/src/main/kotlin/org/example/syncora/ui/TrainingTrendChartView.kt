package org.example.syncora.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.example.syncora.agent.TrainingRunOutcome
import org.example.syncora.agent.TrainingRunRecord

/**
 * Bar chart of the last N [TrainingRunRecord.pboProbability] values, oldest to newest left to
 * right - the trend a single "PBO 0.04" number on its own can't show: is the gate passing more
 * or less often lately, and is PBO drifting toward the reject threshold even on runs that still
 * pass. [TrainingRunOutcome.PASSED]/[TrainingRunOutcome.REJECTED] bars are colored by outcome and
 * scaled to their PBO probability (0..1); a skip/failure that never reached the gate (no PBO
 * value) draws as a short neutral tick at the baseline instead of an empty gap, so a run that
 * happened is still visible in the sequence. A dashed line marks the 0.10 gate threshold
 * ([org.example.syncora.agent.CpcvPboValidationGate]'s default `alpha`) so "how close to
 * rejecting" is visible at a glance even on passing runs.
 */
class TrainingTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    var passColor: Int = Color.parseColor("#0ECB81")
    var rejectColor: Int = Color.parseColor("#F6465D")
    var neutralColor: Int = Color.parseColor("#5B6673")
    var thresholdColor: Int = Color.parseColor("#8A94A6")
    var thresholdAlpha: Double = 0.10

    /** Oldest-first; [render] takes care of that ordering so callers can just pass whatever [org.example.syncora.agent.TrainingRunHistoryStore.recent] returns. */
    private var entries: List<TrainingRunRecord> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }

    private val barRect = RectF()

    /** [recentNewestFirst] is [org.example.syncora.agent.TrainingRunHistoryStore.recent]'s newest-first order - reversed here so the chart reads oldest (left) to newest (right), matching a normal time axis. */
    fun render(recentNewestFirst: List<TrainingRunRecord>) {
        entries = recentNewestFirst.asReversed()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (entries.isEmpty() || w <= 0f || h <= 0f) return

        val gap = dp(3f)
        val barWidth = ((w - gap * (entries.size - 1)) / entries.size).coerceAtLeast(dp(2f))
        val neutralTickHeight = dp(4f)
        val usableHeight = h - dp(2f)

        entries.forEachIndexed { index, entry ->
            val left = index * (barWidth + gap)
            val pbo = entry.pboProbability
            val (barHeight, color) = when {
                pbo != null && entry.outcome == TrainingRunOutcome.PASSED ->
                    (pbo.toFloat().coerceIn(0.02f, 1f) * usableHeight) to passColor
                pbo != null && entry.outcome == TrainingRunOutcome.REJECTED ->
                    (pbo.toFloat().coerceIn(0.02f, 1f) * usableHeight) to rejectColor
                else -> neutralTickHeight to neutralColor
            }
            barRect.set(left, h - barHeight, left + barWidth, h)
            barPaint.color = color
            canvas.drawRoundRect(barRect, dp(1.5f), dp(1.5f), barPaint)
        }

        thresholdPaint.color = thresholdColor
        val thresholdY = h - (thresholdAlpha.toFloat().coerceIn(0f, 1f) * usableHeight)
        canvas.drawLine(0f, thresholdY, w, thresholdY, thresholdPaint)
    }
}
