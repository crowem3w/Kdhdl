package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.example.syncora.R
import org.example.syncora.perf.PerformanceSnapshot

class PerformanceHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private companion object {
        val COLOR_INITIAL = Color.WHITE
        val COLOR_HEALTHY = Color.parseColor("#66FF00")
        val COLOR_DEGRADED = Color.parseColor("#FF2400")
        const val TEXT_SIZE_SP = 9f
        const val ROW_SPACING_DP = 10f
    }

    private val fpsRow: TextView
    private val latencyRow: TextView
    private val cpuFrameRow: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.START

        fpsRow = makeRow(marginStartDp = 0f)
        latencyRow = makeRow(marginStartDp = ROW_SPACING_DP)
        cpuFrameRow = makeRow(marginStartDp = ROW_SPACING_DP)

        addView(fpsRow)
        addView(latencyRow)
        addView(cpuFrameRow)
    }

    private fun makeRow(marginStartDp: Float): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        typeface = ResourcesCompat.getFont(context, R.font.inter_thin)
        text = "—"
        val marginStartPx = (marginStartDp * resources.displayMetrics.density).toInt()
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = marginStartPx
        }
    }

    private fun colorize(label: String, healthy: Boolean): SpannableString {
        val spannable = SpannableString(label)
        val splitIndex = label.indexOf(' ')
        val valueColor = if (healthy) COLOR_HEALTHY else COLOR_DEGRADED
        if (splitIndex == -1) {
            spannable.setSpan(
                ForegroundColorSpan(COLOR_INITIAL),
                0,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        } else {
            spannable.setSpan(
                ForegroundColorSpan(COLOR_INITIAL),
                0,
                splitIndex,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                ForegroundColorSpan(valueColor),
                splitIndex + 1,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    fun render(snapshot: PerformanceSnapshot) {
        fpsRow.text = colorize(snapshot.fpsLabel, snapshot.fpsHealthy)
        latencyRow.text = colorize(snapshot.latencyLabel, snapshot.latencyHealthy)
        cpuFrameRow.text = colorize(snapshot.cpuFrameLabel, snapshot.cpuFrameHealthy)
    }
}
