package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.example.syncora.bitget.DepthLevel
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PositionSide
import org.example.syncora.rrl.RrlPerformanceSummary
import org.example.syncora.rrl.RrlStepResult

class QuickTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class OrderType { MARKET, LIMIT }

    enum class MarginMode { CROSS, ISOLATED }

    enum class FuturesUnit(val label: String, val unitSuffix: String) {
        QUANTITY("Quantity", "BTC"),
        COST("Cost", "USDT"),
        VALUE("Value", "USDT"),
    }

    class Callbacks(
        val onOpenPosition: (
            side: PositionSide,
            sizeUsdt: String,
            leverage: Int,
            orderType: OrderType,
            limitPrice: String?,
            takeProfitPrice: String?,
            stopLossPrice: String?,
        ) -> Unit,
        val onClosePosition: (PaperPosition) -> Unit = {},
        val onCancelPendingOrder: (PendingLimitOrder) -> Unit = {},
    )

    
    private data class AgentParamField(
        val key: String,
        val label: String,
        val defaultValue: String,
        val description: String,
        val inputType: Int,
    )

    private val borderColor = android.graphics.Color.parseColor("#1B2530")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val fieldBackground = Color.parseColor("#131722")

    var onHandleDrag: ((phase: ScrollRevealContainer.DragPhase, deltaY: Float) -> Unit)? = null

    private var handleDownY = 0f

    private lateinit var scrollView: View
    private lateinit var agentStatePanel: AgentStatePanelView

    
    private val agentParamFields = LinkedHashMap<String, EditText>()

    val scrollableContent: View
        get() = scrollView

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    
    fun agentParamValue(key: String): String? = agentParamFields[key]?.text?.toString()

    private val agentParamDefs: List<AgentParamField> = listOf(
        AgentParamField(
            key = "nHidden",
            label = "Hidden Units",
            defaultValue = "100",
            description = "Number of neurons in the echo state network's dynamic reservoir. " +
                "A larger reservoir gives a richer feature space for the agent to learn from, " +
                "at the cost of more compute per update.",
            inputType = InputType.TYPE_CLASS_NUMBER,
        ),
        AgentParamField(
            key = "nBack",
            label = "Feedback Window",
            defaultValue = "10",
            description = "Number of past agent positions fed back into the reservoir as " +
                "recurrent input, so the agent is aware of its own recent trading history.",
            inputType = InputType.TYPE_CLASS_NUMBER,
        ),
        AgentParamField(
            key = "sparsity",
            label = "Sparsity",
            defaultValue = "0.75",
            description = "Fraction of reservoir connection weights randomly zeroed out when " +
                "the reservoir is initialised. Higher values mean a sparser, more loosely " +
                "connected reservoir.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "spectralRadius",
            label = "Spectral Radius",
            defaultValue = "0.9",
            description = "Scaling applied to the reservoir weight matrix. Should stay below " +
                "1.0 to guarantee the echo state property, i.e. a bounded, fading memory.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "signFlipProbability",
            label = "Sign Flip Probability",
            defaultValue = "0.5",
            description = "Fraction of reservoir weights whose sign is flipped negative after " +
                "scaling, so the reservoir has both excitatory and inhibitory connections.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "ridgePenalty",
            label = "Ridge Penalty",
            defaultValue = "1.0",
            description = "Initial precision scaling for the extended Kalman filter's weight " +
                "covariance matrix, used to regularise early updates before enough data has " +
                "been observed.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "kalmanDecay",
            label = "Kalman Decay",
            defaultValue = "0.999",
            description = "Exponential forgetting factor used by the extended Kalman filter " +
                "weight update. Values closer to 1 make the filter forget older observations " +
                "more slowly.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "emaDecay",
            label = "Return EMA Decay",
            defaultValue = "0.999",
            description = "Decay factor for the online exponentially-weighted estimates of the " +
                "mean and variance of returns used in the agent's risk-adjusted utility.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "riskAppetiteMode",
            label = "Risk Appetite Mode",
            defaultValue = "FIXED",
            description = "FIXED uses a constant risk-aversion value. INFORMATION_RATIO instead " +
                "recomputes risk aversion on every step from the agent's trailing information " +
                "ratio.",
            inputType = InputType.TYPE_CLASS_TEXT,
        ),
        AgentParamField(
            key = "fixedRiskAppetite",
            label = "Risk Appetite",
            defaultValue = "0.00001",
            description = "Risk-aversion constant used in the quadratic utility function. Only " +
                "takes effect when Risk Appetite Mode is FIXED; higher values make the agent " +
                "more conservative.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "annualisationFactor",
            label = "Annualisation Factor",
            defaultValue = "15.8745",
            description = "Multiplier applied when annualising the Sharpe / information ratio. " +
                "The default of \u221a252 assumes roughly 252 return observations per year " +
                "(e.g. daily bars) - adjust it if the agent trades on a different bar frequency.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "benchmarkReturn",
            label = "Benchmark Return",
            defaultValue = "0.0",
            description = "Baseline return subtracted from the strategy's return when computing " +
                "the risk-adjusted information ratio.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
        ),
        AgentParamField(
            key = "exchangeFeeRate",
            label = "Exchange Fee Rate",
            defaultValue = "0.0005",
            description = "Taker exchange fee charged per unit of notional traded, applied on " +
                "top of half the bid/ask spread when the agent changes its position.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        AgentParamField(
            key = "gateOnExpectedReturn",
            label = "Gate On Expected Return",
            defaultValue = "true",
            description = "When true, the agent may only open new positions while its online " +
                "estimate of expected return is non-negative; otherwise it flattens and waits " +
                "for conditions to improve.",
            inputType = InputType.TYPE_CLASS_TEXT,
        ),
        AgentParamField(
            key = "seed",
            label = "Random Seed",
            defaultValue = "42",
            description = "Seed for the reservoir's random weight initialisation. Fixing it " +
                "makes backtests and runs reproducible; agent performance can be somewhat " +
                "sensitive to this choice.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
        ),
        AgentParamField(
            key = "maxLeverage",
            label = "Max Leverage",
            defaultValue = "10",
            description = "Upper bound on leverage the agent is allowed to request when sizing " +
                "a position. This is a live/paper trading safety cap and isn't part of the " +
                "original agent design.",
            inputType = InputType.TYPE_CLASS_NUMBER,
        ),
    )

    init {
        orientation = VERTICAL
        addView(buildGrabHandle())
        scrollView = buildTwoColumnBody()
        addView(scrollView.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        })
    }

    fun bind(callbacks: Callbacks) {
    }

    fun render(balance: PaperAccountBalance?) = Unit

    fun renderMarkPrice(price: Double?) = Unit

    fun renderOrderBook(bids: List<DepthLevel>, asks: List<DepthLevel>) = Unit

    fun renderOpenPositions(positions: List<PaperPosition>) = Unit

    fun renderPendingOrders(orders: List<PendingLimitOrder>) = Unit

    /** Live agent step + cumulative performance, rendered into the right-hand column. */
    fun renderAgentState(step: RrlStepResult?, performance: RrlPerformanceSummary) {
        agentStatePanel.render(step, performance)
    }

    /** Clears the accumulated chart history, e.g. when the agent is reset/re-parameterised. */
    fun resetAgentState() {
        agentStatePanel.reset()
    }

    /** Tap on the checkpoint icon/label in the right-hand column: export or import per current mode. */
    var onCheckpointAction: ((AgentStatePanelView.CheckpointAction) -> Unit)?
        get() = agentStatePanel.onCheckpointAction
        set(value) { agentStatePanel.onCheckpointAction = value }

    /** Long-press on the checkpoint control while in Import mode: restore the last autosave. */
    var onRestoreLastAutosave: (() -> Unit)?
        get() = agentStatePanel.onRestoreLastAutosave
        set(value) { agentStatePanel.onRestoreLastAutosave = value }

    private fun buildTwoColumnBody(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }

        val leftColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(14), dp(10), dp(8), dp(14))
        }
        agentParamDefs.forEach { field -> leftColumn.addView(buildAgentParamRow(field)) }

        agentStatePanel = AgentStatePanelView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(leftColumn)
        row.addView(agentStatePanel)

        return ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            addView(
                row.apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }
    }

    private fun buildAgentParamRow(field: AgentParamField): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val label = TextView(context).apply {
            text = field.label
            textSize = 12f
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val infoButton = TextView(context).apply {
            text = "\u24D8"
            textSize = 13f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(4), dp(8), dp(4))
            setOnClickListener {
                RrlParameterInfoDialog(
                    context = context,
                    paramLabel = field.label,
                    paramDescription = field.description,
                    paramDefault = field.defaultValue,
                ).show()
            }
        }

        val input = EditText(context).apply {
            setText(field.defaultValue)
            inputType = field.inputType
            textSize = 12.5f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        agentParamFields[field.key] = input

        row.addView(label)
        row.addView(infoButton)
        row.addView(input)
        return row
    }

    private fun buildGrabHandle(): View =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
            isClickable = true
            isFocusable = true
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(borderColor)
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(36), dp(4)).apply {
                        gravity = Gravity.CENTER
                    }
                },
            )
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleDownY = event.rawY
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.START, 0f)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.MOVE, event.rawY - handleDownY)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.END, event.rawY - handleDownY)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        onHandleDrag?.invoke(ScrollRevealContainer.DragPhase.CANCEL, event.rawY - handleDownY)
                        true
                    }
                    else -> false
                }
            }
        }
}