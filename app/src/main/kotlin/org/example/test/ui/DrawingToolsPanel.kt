package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.example.test.chart.DrawingTool

class DrawingToolsPanel(private val context: Context) {

    private var popupWindow: PopupWindow? = null

    private val accentColor = Color.parseColor("#2962FF")
    private val iconIdleColor = Color.parseColor("#B2B5BE")
    private val labelIdleColor = Color.parseColor("#EAECEF")
    private val surfaceColor = Color.parseColor("#1E222D")
    private val dividerColor = Color.parseColor("#2A2E39")

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun show(anchor: View, activeTool: DrawingTool, onToolSelected: (DrawingTool) -> Unit) {
        dismiss()

        val panelWidth = dp(210)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), dividerColor)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val selectableItemBg = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        DrawingTool.selectable.forEachIndexed { index, tool ->
            val isActive = tool == activeTool

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(10), dp(14), dp(10))
                if (selectableItemBg != 0) setBackgroundResource(selectableItemBg)
            }

            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                setImageResource(tool.iconRes)
                setColorFilter(if (isActive) accentColor else iconIdleColor)
            }

            val label = TextView(context).apply {
                text = tool.label
                textSize = 13.5f
                setTextColor(if (isActive) accentColor else labelIdleColor)
                setPadding(dp(12), 0, 0, 0)
            }

            row.addView(icon)
            row.addView(label)
            row.setOnClickListener {
                onToolSelected(tool)
                dismiss()
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )

            if (index != DrawingTool.selectable.lastIndex) {
                val divider = View(context).apply { setBackgroundColor(dividerColor) }
                container.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(unspecified, unspecified)

        val popup = PopupWindow(container, panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        popupWindow = popup

        val xOffset = anchor.width - panelWidth
        val yOffset = -(container.measuredHeight + anchor.height + dp(8))
        popup.showAsDropDown(anchor, xOffset, yOffset)
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}
