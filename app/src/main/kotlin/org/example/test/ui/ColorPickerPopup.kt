package org.example.test.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class ColorPickerPopup(private val context: Context) {

    private var popupWindow: PopupWindow? = null

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val captionColor = Color.parseColor("#787B86")
    private val labelColor = Color.parseColor("#EAECEF")
    private val accentColor = Color.parseColor("#2962FF")

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun caption(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(captionColor)
    }

    private fun sectionLabel(text: String): TextView = caption(text).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun swatchDrawable(color: Int, selected: Boolean, ringOnLight: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            val stroke = if (selected) borderColorFor(true) else borderColorFor(false, ringOnLight)
            setStroke(dp(if (selected) 2 else 1), stroke)
        }

    private fun borderColorFor(selected: Boolean, ringOnLight: Boolean = false): Int =
        if (selected) accentColor else if (ringOnLight) Color.parseColor("#3A3E4A") else borderColor

    private fun swatchView(color: Int, selected: Boolean, size: Int = dp(28), marginEnd: Int = dp(10), onClick: () -> Unit): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                this.marginEnd = marginEnd
            }
            background = swatchDrawable(color, selected)
            tag = color
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun show(
        anchor: View,
        currentColor: Int,
        currentOpacityPercent: Int,
        onColorChange: (Int) -> Unit,
        onOpacityChange: (Int) -> Unit,
        safeAreaScreen: Rect? = null,
    ) {
        dismiss()

        val panelWidth = dp(252)
        val gridSwatchSize = dp(18)
        val gridSwatchMargin = dp(5)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        fun gridRow(colors: List<Int>): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
            colors.forEach { color ->
                addView(swatchView(color, color == currentColor, gridSwatchSize, gridSwatchMargin) {
                    onColorChange(color)
                    rememberRecent(color)
                    refreshSelection(container, color)
                })
            }
        }

        container.addView(caption("PALETTE"))
        container.addView(gridRow(grayscaleRow))
        hues.forEach { hue ->
            container.addView(gridRow(shadeRowForHue(hue)))
        }

        container.addView(sectionLabel("CUSTOM"))
        val customRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        fun rebuildCustomRow() {
            customRow.removeAllViews()
            customColors.forEach { color ->
                val view = swatchView(color, color == currentColor) {
                    onColorChange(color)
                    rememberRecent(color)
                    refreshSelection(container, color)
                }
                view.setOnLongClickListener {
                    customColors.remove(color)
                    rebuildCustomRow()
                    true
                }
                customRow.addView(view)
            }
            customRow.addView(customSwatchView { picked ->
                onColorChange(picked)
                rememberRecent(picked)
                rememberCustom(picked)
                rebuildCustomRow()
                refreshSelection(container, picked)
            })
        }
        rebuildCustomRow()
        container.addView(customRow)

        if (recentColors.isNotEmpty()) {
            container.addView(sectionLabel("RECENT"))
            val recentRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            }
            recentColors.forEach { color ->
                recentRow.addView(swatchView(color, color == currentColor) {
                    onColorChange(color)
                    refreshSelection(container, color)
                })
            }
            container.addView(recentRow)
        }

        container.addView(View(context).apply {
            setBackgroundColor(borderColor)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12); bottomMargin = dp(10)
        })

        val opacityHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        opacityHeaderRow.addView(caption("OPACITY"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val opacityValueText = TextView(context).apply {
            text = "$currentOpacityPercent%"
            textSize = 11.5f
            setTextColor(labelColor)
        }
        opacityHeaderRow.addView(opacityValueText)
        container.addView(opacityHeaderRow)

        val seekBar = SeekBar(context).apply {
            max = 100
            progress = currentOpacityPercent
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    opacityValueText.text = "$progress%"
                    if (fromUser) onOpacityChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        container.addView(seekBar)

        val scrollHost = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val exactWidth = View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY)
        val unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(exactWidth, unspecifiedHeight)

        val maxPanelHeight = safeAreaScreen?.let { (it.height() - dp(24)).coerceAtLeast(dp(200)) } ?: dp(420)
        val panelHeight = container.measuredHeight.coerceAtMost(maxPanelHeight)

        val popup = PopupWindow(scrollHost, panelWidth, panelHeight, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        popupWindow = popup

        val gap = dp(8)
        if (safeAreaScreen != null) {
            val loc = PopupPlacement.below(anchor, panelWidth, panelHeight, safeAreaScreen, gap)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc.x, loc.y)
        } else {
            popup.showAsDropDown(anchor, 0, gap)
        }
    }

    private fun refreshSelection(root: View, selectedColor: Int) {
        val tag = root.tag
        if (tag is Int) {
            root.background = swatchDrawable(tag, tag == selectedColor)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                refreshSelection(root.getChildAt(i), selectedColor)
            }
        }
    }

    private fun customSwatchView(onPicked: (Int) -> Unit): View {
        val size = dp(28)
        val plus = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            text = "+"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(captionColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), borderColor)
            }
            isClickable = true
            isFocusable = true
        }
        plus.setOnClickListener { showHexInputDialog(onPicked) }
        return plus
    }

    private fun showHexInputDialog(onPicked: (Int) -> Unit) {
        val input = EditText(context).apply {
            hint = "#RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(labelColor)
            setHintTextColor(captionColor)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(context)
            .setTitle("Add custom color")
            .setView(input)
            .setPositiveButton("Add") { dialog, _ ->
                val parsed = parseHexColor(input.text.toString())
                if (parsed != null) onPicked(parsed)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun parseHexColor(raw: String): Int? {
        val hex = raw.trim().let { if (it.startsWith("#")) it else "#$it" }
        return try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun rememberRecent(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > maxRecentColors) recentColors.removeAt(recentColors.size - 1)
    }

    private fun rememberCustom(color: Int) {
        customColors.remove(color)
        customColors.add(0, color)
        while (customColors.size > maxCustomColors) customColors.removeAt(customColors.size - 1)
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    companion object {
        private const val maxRecentColors = 6
        private const val maxCustomColors = 12

        private val recentColors: MutableList<Int> = mutableListOf()

        private val customColors: MutableList<Int> = mutableListOf()

        private val hues: List<Float> = listOf(0f, 30f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f)

        private val shadeSteps: List<Pair<Float, Float>> = listOf(
            0.15f to 1.00f,
            0.28f to 0.98f,
            0.40f to 0.95f,
            0.52f to 0.91f,
            0.63f to 0.87f,
            0.73f to 0.81f,
            0.81f to 0.74f,
            0.87f to 0.64f,
            0.91f to 0.52f,
            0.93f to 0.40f,
        )

        private val grayscaleRow: List<Int> = listOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#D1D4DC"),
            Color.parseColor("#B2B5BE"),
            Color.parseColor("#9598A1"),
            Color.parseColor("#787B86"),
            Color.parseColor("#5D6069"),
            Color.parseColor("#434651"),
            Color.parseColor("#2A2E39"),
            Color.parseColor("#1A1D26"),
            Color.parseColor("#131722"),
        )

        private fun shadeRowForHue(hue: Float): List<Int> = shadeSteps.map { (s, v) ->
            Color.HSVToColor(floatArrayOf(hue, s, v))
        }

        val presetColors: List<Int> = listOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#787B86"),
            Color.parseColor("#F23645"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#FFEB3B"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#26A69A"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#2962FF"),
            Color.parseColor("#651FFF"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#E91E63"),
        )
    }
}
