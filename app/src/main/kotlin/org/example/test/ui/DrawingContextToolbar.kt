package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import org.example.test.R
import org.example.test.chart.CandlestickChartView
import org.example.test.chart.LinePattern

class DrawingContextToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Callbacks(
        val onColorChange: (Int) -> Unit,
        val onOpacityChange: (Int) -> Unit,
        val onWidthChange: (Float) -> Unit,
        val onPatternChange: (LinePattern) -> Unit,
        val onDelete: () -> Unit,
    )

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val iconIdleColor = Color.parseColor("#B2B5BE")
    private val accentColor = Color.parseColor("#2962FF")
    private val deleteColor = Color.parseColor("#F23645")

    private val widthOptionsDp = listOf(0.5f, 1f, 2f, 3f, 4f)

    private lateinit var colorSwatch: View
    private lateinit var widthLabel: TextView
    private lateinit var patternLabel: TextView

    private val colorPickerPopup by lazy { ColorPickerPopup(context) }
    private var widthPopup: PopupWindow? = null
    private var patternPopup: PopupWindow? = null

    private fun formatWidthLabel(widthDp: Float): String {
        val trimmed = if (widthDp == widthDp.toInt().toFloat()) {
            widthDp.toInt().toString()
        } else {
            widthDp.toString()
        }
        return "${trimmed}px"
    }

    private var callbacks: Callbacks? = null
    private var currentStyle: CandlestickChartView.SelectedDrawingStyle? = null

    private var chartView: CandlestickChartView? = null
    private var lastAnchorBoundsPx: RectF? = null

    private val positionMarginPx by lazy { dp(8) }
    private val anchorGapPx by lazy { dp(10) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), borderColor)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        elevation = dp(6).toFloat()

        addView(buildColorButton())
        addView(divider())
        addView(buildWidthButton())
        addView(divider())
        addView(buildPatternButton())
        addView(divider())
        addView(buildDeleteButton())
    }

    fun bind(chart: CandlestickChartView, callbacks: Callbacks) {
        this.chartView = chart
        this.callbacks = callbacks
        chart.onSelectedDrawingBoundsChanged = { bounds ->
            lastAnchorBoundsPx = bounds
            if (visibility == View.VISIBLE) updatePosition()
        }
        chart.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged && visibility == View.VISIBLE) updatePosition()
        }
    }

    fun showForStyle(style: CandlestickChartView.SelectedDrawingStyle) {
        currentStyle = style
        colorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(style.color)
            setStroke(dp(1), Color.parseColor("#3A3E4A"))
        }
        widthLabel.text = formatWidthLabel(style.lineWidthDp)
        visibility = View.VISIBLE
        lastAnchorBoundsPx = chartView?.selectedDrawingBoundsPx()
        updatePosition()
    }

    fun hide() {
        currentStyle = null
        lastAnchorBoundsPx = null
        colorPickerPopup.dismiss()
        widthPopup?.dismiss()
        widthPopup = null
        patternPopup?.dismiss()
        patternPopup = null
        visibility = View.GONE
    }

    private fun updatePosition() {
        val chart = chartView ?: return
        if (width == 0 || height == 0) {
            doOnPreDraw { if (visibility == View.VISIBLE) updatePosition() }
            return
        }
        val safeArea = chart.contentAreaPx()
        if (safeArea.width() <= 0f || safeArea.height() <= 0f) return

        val margin = positionMarginPx.toFloat()
        val minX = safeArea.left + margin
        val maxX = (safeArea.right - margin - width).coerceAtLeast(minX)
        val minY = safeArea.top + margin
        val maxY = (safeArea.bottom - margin - height).coerceAtLeast(minY)

        val anchor = lastAnchorBoundsPx
        val targetX: Float
        val targetY: Float
        if (anchor != null) {
            val centerX = (anchor.left + anchor.right) / 2f
            targetX = (centerX - width / 2f).coerceIn(minX, maxX)
            val above = anchor.top - anchorGapPx - height
            targetY = if (above >= minY) above else (anchor.bottom + anchorGapPx)
        } else {
            targetX = ((safeArea.left + safeArea.right) / 2f - width / 2f).coerceIn(minX, maxX)
            targetY = minY
        }
        x = targetX.coerceIn(minX, maxX)
        y = targetY.coerceIn(minY, maxY)
    }

    private fun chartSafeAreaScreen(): Rect? {
        val chart = chartView ?: return null
        if (chart.width == 0 || chart.height == 0) return null
        val local = chart.contentAreaPx()
        val loc = IntArray(2)
        chart.getLocationOnScreen(loc)
        return Rect(
            (loc[0] + local.left).toInt(),
            (loc[1] + local.top).toInt(),
            (loc[0] + local.right).toInt(),
            (loc[1] + local.bottom).toInt(),
        )
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(borderColor)
    }.also {
        addViewLayoutParamsDivider(it)
    }

    private fun addViewLayoutParamsDivider(view: View) {
        view.layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
    }

    private fun buildColorButton(): View {
        val size = dp(24)
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
                setStroke(dp(1), Color.parseColor("#3A3E4A"))
            }
        }
        colorSwatch = swatch

        val button = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(swatch)
        }
        button.setOnClickListener {
            val style = currentStyle ?: return@setOnClickListener
            val cb = callbacks ?: return@setOnClickListener
            colorPickerPopup.show(
                anchor = button,
                currentColor = style.color,
                currentOpacityPercent = style.opacityPercent,
                onColorChange = { color ->
                    currentStyle = currentStyle?.copy(color = color)
                    colorSwatch.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(1), Color.parseColor("#3A3E4A"))
                    }
                    cb.onColorChange(color)
                },
                onOpacityChange = { percent ->
                    currentStyle = currentStyle?.copy(opacityPercent = percent)
                    cb.onOpacityChange(percent)
                },
                safeAreaScreen = chartSafeAreaScreen(),
            )
        }
        return button
    }

    private fun buildWidthButton(): View {
        val label = TextView(context).apply {
            text = formatWidthLabel(1f)
            textSize = 12.5f
            setTextColor(labelColor)
        }
        widthLabel = label

        val chevron = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(2) }
            setImageResource(R.drawable.ic_chevron_down)
        }

        val button = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(label)
            addView(chevron)
        }
        button.setOnClickListener { showWidthPopup(button) }
        return button
    }

    private fun showWidthPopup(anchor: View) {
        widthPopup?.dismiss()
        val cb = callbacks
        val style = currentStyle

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        widthOptionsDp.forEach { widthDp ->
            val isActive = style != null && style.lineWidthDp == widthDp
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), dp(9), dp(18), dp(9))
            }
            val sample = LinePatternSwatchView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(16))
                pattern = LinePattern.SOLID
                lineColor = if (isActive) accentColor else iconIdleColor
            }

            sample.scaleY = (0.6f + widthDp * 0.25f)
            val text = TextView(context).apply {
                text = formatWidthLabel(widthDp)
                textSize = 13f
                setTextColor(if (isActive) accentColor else labelColor)
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(sample)
            row.addView(text)
            row.setOnClickListener {
                currentStyle = currentStyle?.copy(lineWidthDp = widthDp)
                widthLabel.text = formatWidthLabel(widthDp)
                cb?.onWidthChange(widthDp)
                widthPopup?.dismiss()
            }
            list.addView(row)
        }

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        list.measure(unspecified, unspecified)
        val popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        widthPopup = popup

        val gap = dp(6)
        val safeArea = chartSafeAreaScreen()
        if (safeArea != null) {
            val loc = PopupPlacement.below(anchor, list.measuredWidth, list.measuredHeight, safeArea, gap)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc.x, loc.y)
        } else {
            popup.showAsDropDown(anchor, 0, gap)
        }
    }

    private fun buildPatternButton(): View {
        val label = TextView(context).apply {
            text = "\u2014"
            textSize = 15f
            setTextColor(labelColor)
        }
        patternLabel = label

        val chevron = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(2) }
            setImageResource(R.drawable.ic_chevron_down)
        }

        val button = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(4), dp(8), dp(4))
            addView(label)
            addView(chevron)
        }
        button.setOnClickListener { showPatternPopup(button) }
        return button
    }

    private fun showPatternPopup(anchor: View) {
        patternPopup?.dismiss()
        val cb = callbacks
        val style = currentStyle

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        LinePattern.selectable.forEach { pattern ->
            val isActive = style != null && style.pattern == pattern
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), dp(9), dp(18), dp(9))
                background = if (isActive) {
                    GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#2A3A66"))
                    }
                } else {
                    null
                }
            }
            val sample = LinePatternSwatchView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(16))
                this.pattern = pattern
                lineColor = if (isActive) accentColor else iconIdleColor
            }
            val text = TextView(context).apply {
                text = pattern.label
                textSize = 13f
                setTextColor(if (isActive) accentColor else labelColor)
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(sample)
            row.addView(text)
            row.setOnClickListener {
                currentStyle = currentStyle?.copy(pattern = pattern)
                cb?.onPatternChange(pattern)
                patternPopup?.dismiss()
            }
            list.addView(row)
        }

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        list.measure(unspecified, unspecified)
        val popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        patternPopup = popup

        val gap = dp(6)
        val safeArea = chartSafeAreaScreen()
        if (safeArea != null) {
            val loc = PopupPlacement.below(anchor, list.measuredWidth, list.measuredHeight, safeArea, gap)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc.x, loc.y)
        } else {
            popup.showAsDropDown(anchor, 0, gap)
        }
    }

    private fun buildDeleteButton(): View {
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setImageResource(R.drawable.ic_toolbar_delete)
            setColorFilter(deleteColor)
        }
        val button = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(7), dp(6), dp(7), dp(6))
            addView(icon)
        }
        button.setOnClickListener { callbacks?.onDelete?.invoke() }
        return button
    }
}
