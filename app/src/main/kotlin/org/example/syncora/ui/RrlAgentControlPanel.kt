package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import org.example.syncora.R
import java.util.Locale

/**
 * The RRL agent's UI layer, hosted inside [QuickTradePanel]'s scrollable content.
 *
 * Renders as two columns:
 *  - Column 1: a labelled row for each control, with a small glyph icon pinned to the
 *    right edge of the column (the import/export row uses the supplied maze icon;
 *    every other row uses its own icon, following the same right-aligned placement).
 *  - Column 2: the corresponding editable control for each row, in the same order,
 *    so rows line up horizontally across both columns.
 */
class RrlAgentControlPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Current values shown by the panel. */
    data class AgentSettingsState(
        val maxLeverage: Int,
        val riskAppetite: Double,
        val decay: Double,
        val ridgePenalty: Double,
        val ekfVarianceStabilizationEnabled: Boolean,
    )

    class Callbacks(
        val onExportRequested: () -> Unit = {},
        val onImportRequested: () -> Unit = {},
        val onMaxLeverageChanged: (Int) -> Unit = {},
        val onRiskAppetiteChanged: (Double) -> Unit = {},
        val onDecayChanged: (Double) -> Unit = {},
        val onRidgePenaltyChanged: (Double) -> Unit = {},
        val onEkfStabilizationToggled: (Boolean) -> Unit = {},
    )

    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val accentColor = Color.parseColor("#26A69A")

    private var callbacks: Callbacks? = null
    private var suppressCallbacks = false

    private lateinit var leverageInput: EditText
    private lateinit var riskAppetiteInput: EditText
    private lateinit var decayInput: EditText
    private lateinit var ridgePenaltyInput: EditText
    private lateinit var ekfStabilizationSwitch: Switch

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_LEVERAGE = 1
        private const val MAX_LEVERAGE = 125
        private const val ROW_MIN_HEIGHT = 56
    }

    init {
        orientation = VERTICAL
        background = cardBackground()
        setPadding(dp(14), dp(12), dp(14), dp(14))
        addView(buildHeader())
        addView(spacer(10))
        addView(buildTwoColumnLayout())
    }

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun render(state: AgentSettingsState) {
        suppressCallbacks = true
        leverageInput.setText(state.maxLeverage.toString())
        riskAppetiteInput.setText(formatCompact(state.riskAppetite))
        decayInput.setText(formatCompact(state.decay))
        ridgePenaltyInput.setText(formatCompact(state.ridgePenalty))
        ekfStabilizationSwitch.isChecked = state.ekfVarianceStabilizationEnabled
        suppressCallbacks = false
    }

    private fun formatCompact(value: Double): String {
        val text = String.format(Locale.US, "%.6f", value).trimEnd('0')
        return if (text.endsWith(".")) text + "0" else text
    }

    private fun buildHeader(): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = "Agent Settings"
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(labelColor)
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                TextView(context).apply {
                    text = "RRL \u00B7 ESN"
                    textSize = 10.5f
                    setTextColor(mutedColor)
                },
            )
        }

    private fun buildTwoColumnLayout(): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                buildColumnOne(),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(spacerHorizontal(12))
            addView(
                buildColumnTwo(),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    // ---- Column 1: labelled rows, icon pinned to the row's right edge ----

    private fun buildColumnOne(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(labelRow("Import / Export", "Backup or restore the agent's learned weights", R.drawable.ic_agent_import_export))
            addView(rowDivider())
            addView(labelRow("Max leverage", "Upper bound on position sizing", R.drawable.ic_agent_leverage))
            addView(rowDivider())
            addView(labelRow("Risk appetite \u03BB", "Reward/risk tradeoff in the quadratic utility", R.drawable.ic_agent_risk))
            addView(rowDivider())
            addView(labelRow("Decay \u03C4", "Memory length for EMA + EKF adaptation", R.drawable.ic_agent_decay))
            addView(rowDivider())
            addView(labelRow("Ridge penalty \u03B2", "EKF precision matrix regularizer", R.drawable.ic_agent_ridge))
            addView(rowDivider())
            addView(labelRow("EKF variance stabilization", "Numerical stabilization of the precision update", R.drawable.ic_agent_ekf))
        }

    private fun labelRow(title: String, subtitle: String, iconRes: Int): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_MIN_HEIGHT)

            val textColumn = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            textColumn.addView(
                TextView(context).apply {
                    text = title
                    textSize = 12.5f
                    setTextColor(labelColor)
                },
            )
            textColumn.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 10f
                    setTextColor(mutedColor)
                    setPadding(0, dp(2), dp(6), 0)
                },
            )
            addView(textColumn)

            // Icon pinned to the right side of column 1.
            addView(
                AppCompatImageView(context).apply {
                    setImageResource(iconRes)
                    layoutParams = LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) }
                },
            )
        }

    // ---- Column 2: the editable control for each row, same order as column 1 ----

    private fun buildColumnTwo(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(controlRow(importExportControls()))
            addView(rowDivider())
            addView(controlRow(leverageControl()))
            addView(rowDivider())
            addView(controlRow(riskAppetiteControl()))
            addView(rowDivider())
            addView(controlRow(decayControl()))
            addView(rowDivider())
            addView(controlRow(ridgePenaltyControl()))
            addView(rowDivider())
            addView(controlRow(ekfStabilizationControl()))
        }

    private fun controlRow(content: View): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minimumHeight = dp(ROW_MIN_HEIGHT)
            addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

    private fun importExportControls(): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                smallButton("Import", filled = false) { callbacks?.onImportRequested?.invoke() },
            )
            addView(
                smallButton("Export", filled = true) { callbacks?.onExportRequested?.invoke() }.apply {
                    (layoutParams as LayoutParams).marginStart = dp(8)
                },
            )
        }

    private fun smallButton(text: String, filled: Boolean = false, onClick: () -> Unit): Button =
        Button(context).apply {
            isAllCaps = false
            this.text = text
            textSize = 11.5f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setTextColor(if (filled) Color.WHITE else accentColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (filled) {
                    setColor(accentColor)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), accentColor)
                }
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    private fun leverageControl(): View =
        numericField(
            inputType = InputType.TYPE_CLASS_NUMBER,
            suffix = "x",
        ) { editText ->
            leverageInput = editText
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val leverage = editText.text?.toString()?.trim()?.toIntOrNull()
                        ?.coerceIn(MIN_LEVERAGE, MAX_LEVERAGE) ?: MIN_LEVERAGE
                    editText.setText(leverage.toString())
                    if (!suppressCallbacks) callbacks?.onMaxLeverageChanged?.invoke(leverage)
                }
            }
        }

    private fun riskAppetiteControl(): View =
        numericField(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) { editText ->
            riskAppetiteInput = editText
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val value = editText.text?.toString()?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    editText.setText(formatCompact(value))
                    if (!suppressCallbacks) callbacks?.onRiskAppetiteChanged?.invoke(value)
                }
            }
        }

    private fun decayControl(): View =
        numericField(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { editText ->
            decayInput = editText
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val value = editText.text?.toString()?.trim()?.toDoubleOrNull()
                        ?.coerceIn(0.0001, 1.0) ?: 0.999
                    editText.setText(formatCompact(value))
                    if (!suppressCallbacks) callbacks?.onDecayChanged?.invoke(value)
                }
            }
        }

    private fun ridgePenaltyControl(): View =
        numericField(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { editText ->
            ridgePenaltyInput = editText
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val value = editText.text?.toString()?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0
                    editText.setText(formatCompact(value))
                    if (!suppressCallbacks) callbacks?.onRidgePenaltyChanged?.invoke(value)
                }
            }
        }

    private fun ekfStabilizationControl(): View =
        Switch(context).apply {
            ekfStabilizationSwitch = this
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, isChecked ->
                if (!suppressCallbacks) callbacks?.onEkfStabilizationToggled?.invoke(isChecked)
            })
        }

    private fun numericField(inputType: Int, suffix: String? = null, configure: (EditText) -> Unit): View {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val editText = EditText(context).apply {
            this.inputType = inputType
            textSize = 12.5f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            background = null
            gravity = Gravity.END
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(dp(64), LayoutParams.WRAP_CONTENT)
        }
        configure(editText)
        container.addView(editText)
        if (suffix != null) {
            container.addView(
                TextView(context).apply {
                    text = suffix
                    textSize = 11.5f
                    setTextColor(mutedColor)
                    setPadding(dp(4), 0, 0, 0)
                },
            )
        }
        return container
    }

    private fun rowDivider(): View =
        View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            setBackgroundColor(borderColor)
        }

    private fun spacer(height: Int): View =
        View(context).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(height)) }

    private fun spacerHorizontal(width: Int): View =
        View(context).apply { layoutParams = LayoutParams(dp(width), LayoutParams.MATCH_PARENT) }

    private fun cardBackground(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), borderColor)
        }
}
