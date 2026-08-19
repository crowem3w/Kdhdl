package org.example.test.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import org.example.test.chart.LinePattern

class LinePatternSwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    var pattern: LinePattern = LinePattern.SOLID
        set(value) {
            field = value
            invalidate()
        }

    var lineColor: Int = Color.parseColor("#EAECEF")
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = lineColor
        paint.pathEffect = when (pattern) {
            LinePattern.SOLID -> {
                paint.strokeCap = Paint.Cap.BUTT
                null
            }
            LinePattern.DASHED -> {
                paint.strokeCap = Paint.Cap.BUTT
                DashPathEffect(floatArrayOf(dp(5f), dp(3.5f)), 0f)
            }
            LinePattern.DOTTED -> {
                paint.strokeCap = Paint.Cap.ROUND
                DashPathEffect(floatArrayOf(dp(0.5f), dp(4f)), 0f)
            }
        }
        val y = height / 2f
        canvas.drawLine(dp(2f), y, width - dp(2f), y, paint)
    }
}
