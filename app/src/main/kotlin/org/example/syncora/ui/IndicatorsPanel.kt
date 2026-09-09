package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Switch
import android.widget.TextView
import org.example.syncora.chart.BollingerBands
import java.util.Locale

/**
 * Anchored popup for toggling and tuning chart indicators. Currently just
 * Bollinger Bands, but laid out as a list so more indicators can be added
 * as additional rows later.
 */
class IndicatorsPanel(private val context: Context) {

    data class BollingerState(val enabled: Boolean, val period: Int, val stdDevMultiplier: Double)

    private var popupWindow: PopupWindow? = null

    private val accentColor = Color.parseColor("#FFB800")
    private val labelIdleColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val surfaceColor = Color.parseColor("#1E222D")
    private val dividerColor = Color.parseColor("#2A2E39")
    private val stepperBgColor = Color.parseColor("#2A2E39")

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /**
     * Shows the panel anchored under [anchor]. [initial] seeds the controls;
     * [onChanged] fires immediately on every toggle or stepper tap so the
     * chart updates live while the panel is open.
     */
    fun show(anchor: View, initial: BollingerState, onChanged: (BollingerState) -> Unit) {
        dismiss()

        var state = initial
        val panelWidth = dp(240)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), dividerColor)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        container.addView(TextView(context).apply {
            text = "Indicators"
            textSize = 12.5f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(10))
        })

        // ---- Bollinger Bands toggle row ----
        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleRow.addView(TextView(context).apply {
            text = "Bollinger Bands"
            textSize = 14f
            setTextColor(labelIdleColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val toggle = Switch(context).apply {
            isChecked = state.enabled
            thumbTintList = null
        }
        toggleRow.addView(toggle)
        container.addView(toggleRow)

        // ---- Settings block (period / std dev steppers), hidden while off ----
        val settingsBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            visibility = if (state.enabled) View.VISIBLE else View.GONE
        }

        lateinit var periodValueText: TextView
        lateinit var stdDevValueText: TextView

        fun notifyChanged() = onChanged(state)

        val periodStepper = buildStepperRow(
            label = "Period",
            valueText = { periodValueText = it },
            initialText = state.period.toString(),
            onDecrement = {
                val next = (state.period - 1).coerceIn(BollingerBands.MIN_PERIOD, BollingerBands.MAX_PERIOD)
                state = state.copy(period = next)
                periodValueText.text = next.toString()
                notifyChanged()
            },
            onIncrement = {
                val next = (state.period + 1).coerceIn(BollingerBands.MIN_PERIOD, BollingerBands.MAX_PERIOD)
                state = state.copy(period = next)
                periodValueText.text = next.toString()
                notifyChanged()
            },
        )
        settingsBlock.addView(periodStepper)
        settingsBlock.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(8)) })

        val stdDevStepper = buildStepperRow(
            label = "Std Dev",
            valueText = { stdDevValueText = it },
            initialText = formatStdDev(state.stdDevMultiplier),
            onDecrement = {
                val next = ((state.stdDevMultiplier - 0.5).coerceIn(
                    BollingerBands.MIN_STD_DEV_MULTIPLIER,
                    BollingerBands.MAX_STD_DEV_MULTIPLIER,
                ))
                state = state.copy(stdDevMultiplier = next)
                stdDevValueText.text = formatStdDev(next)
                notifyChanged()
            },
            onIncrement = {
                val next = ((state.stdDevMultiplier + 0.5).coerceIn(
                    BollingerBands.MIN_STD_DEV_MULTIPLIER,
                    BollingerBands.MAX_STD_DEV_MULTIPLIER,
                ))
                state = state.copy(stdDevMultiplier = next)
                stdDevValueText.text = formatStdDev(next)
                notifyChanged()
            },
        )
        settingsBlock.addView(stdDevStepper)

        container.addView(settingsBlock)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            state = state.copy(enabled = isChecked)
            settingsBlock.visibility = if (isChecked) View.VISIBLE else View.GONE
            notifyChanged()
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

    private fun formatStdDev(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun buildStepperRow(
        label: String,
        valueText: (TextView) -> Unit,
        initialText: String,
        onDecrement: () -> Unit,
        onIncrement: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 13.5f
            setTextColor(labelIdleColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val stepperContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(stepperBgColor)
            }
        }

        stepperContainer.addView(buildStepButton("−", onDecrement))

        val valueLabel = TextView(context).apply {
            text = initialText
            textSize = 13.5f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            minWidth = dp(30)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        valueText(valueLabel)
        stepperContainer.addView(valueLabel)

        stepperContainer.addView(buildStepButton("+", onIncrement))

        row.addView(stepperContainer)
        return row
    }

    private fun buildStepButton(symbol: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = symbol
            textSize = 15f
            setTextColor(labelIdleColor)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}
