package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.example.test.bitget.BitgetCredentials
import org.example.test.bitget.PaperAccountBalance
import org.example.test.bitget.PaperPosition
import org.example.test.bitget.PaperTradingConnectionState
import org.example.test.bitget.PositionSide
import java.util.Locale

/**
 * Self-contained "paper trading" panel: demo account balance, open
 * positions with live PnL, and controls to open/close positions with market
 * orders against Bitget's Demo Trading API.
 *
 * This view holds no trading state itself - it just renders whatever
 * [PaperTradingRepository] gives it and forwards user actions back out
 * through [Callbacks]. The actual balances/positions live on Bitget's
 * servers under the user's Demo API Key.
 */
class PaperTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Callbacks(
        val onCredentialsSubmitted: (BitgetCredentials) -> Unit,
        val onCredentialsCleared: () -> Unit,
        val onOpenPosition: (side: PositionSide, size: String, leverage: Int) -> Unit,
        val onClosePosition: (PaperPosition) -> Unit,
    )

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")
    private val fieldBackground = Color.parseColor("#131722")

    private var callbacks: Callbacks? = null
    private var savedCredentials: BitgetCredentials? = null

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var settingsButton: TextView
    private lateinit var balanceText: TextView
    private lateinit var balancePnlText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateText: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var sizeInput: EditText
    private lateinit var leverageInput: EditText
    private lateinit var submitOrderButton: Button
    private var currentSide: PositionSide = PositionSide.LONG

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(surfaceColor)
            setStroke(dp(1), borderColor)
        }
        setPadding(dp(14), dp(12), dp(14), dp(14))

        addView(buildHeaderRow())
        addView(buildBalanceRow())
        addView(spacer(10))
        addView(buildOrderEntryRow())
        addView(spacer(10))
        addView(buildDivider())
        addView(spacer(8))
        addView(buildPositionsHeader())

        emptyStateText = TextView(context).apply {
            text = "No open positions"
            textSize = 12.5f
            setTextColor(mutedColor)
            setPadding(0, dp(6), 0, dp(2))
        }
        addView(emptyStateText)

        positionsContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(positionsContainer)
    }

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * Preselects which side the order-entry submit button will open, e.g.
     * from the chart's Long/Short quick-action buttons. Updates the submit
     * button's label and color to match.
     */
    fun setSide(side: PositionSide) {
        currentSide = side
        applySideStyle()
    }

    private fun applySideStyle() {
        if (!::submitOrderButton.isInitialized) return
        val isLong = currentSide == PositionSide.LONG
        submitOrderButton.text = if (isLong) "Open Long" else "Open Short"
        submitOrderButton.background = pillBackground(if (isLong) bullColor else bearColor)
    }

    fun render(
        connectionState: PaperTradingConnectionState,
        balance: PaperAccountBalance?,
        positions: List<PaperPosition>,
        lastError: String?,
        credentials: BitgetCredentials?,
    ) {
        savedCredentials = credentials

        val (dotColor, label) = when (connectionState) {
            PaperTradingConnectionState.NOT_CONFIGURED -> mutedColor to "Not connected"
            PaperTradingConnectionState.LOADING -> mutedColor to "Connecting…"
            PaperTradingConnectionState.LIVE -> bullColor to "Demo account live"
            PaperTradingConnectionState.ERROR -> bearColor to (lastError ?: "Error")
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
        statusText.text = label
        statusText.setTextColor(if (connectionState == PaperTradingConnectionState.ERROR) bearColor else mutedColor)

        progressBar.visibility = if (connectionState == PaperTradingConnectionState.LOADING) View.VISIBLE else View.GONE

        val ordersEnabled = connectionState == PaperTradingConnectionState.LIVE
        submitOrderButton.isEnabled = ordersEnabled
        submitOrderButton.alpha = if (ordersEnabled) 1f else 0.5f

        if (balance != null) {
            balanceText.text = String.format(Locale.US, "%,.2f USDT", balance.equity)
            val pnl = balance.unrealizedPnl
            balancePnlText.text = String.format(Locale.US, "%s%,.2f uPnL", if (pnl >= 0) "+" else "", pnl)
            balancePnlText.setTextColor(if (pnl >= 0) bullColor else bearColor)
        } else {
            balanceText.text = "—"
            balancePnlText.text = ""
        }

        renderPositions(positions)
    }

    private fun renderPositions(positions: List<PaperPosition>) {
        positionsContainer.removeAllViews()
        emptyStateText.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        for (position in positions) {
            positionsContainer.addView(buildPositionRow(position))
        }
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Paper Trading"
            textSize = 14.5f
            setTextColor(labelColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        settingsButton = TextView(context).apply {
            text = "⚙ Demo API Key"
            textSize = 12f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showCredentialsDialog() }
        }
        row.addView(title)
        row.addView(settingsButton)
        return row
    }

    private fun buildBalanceRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val statusColumn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        }
        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(mutedColor)
        }
        progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        statusColumn.addView(statusDot)
        statusColumn.addView(statusText)
        statusColumn.addView(progressBar)

        val balanceColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        balanceText = TextView(context).apply {
            textSize = 15f
            setTextColor(labelColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "—"
        }
        balancePnlText = TextView(context).apply {
            textSize = 11.5f
            setTextColor(mutedColor)
        }
        balanceColumn.addView(balanceText)
        balanceColumn.addView(balancePnlText)

        row.addView(statusColumn)
        row.addView(balanceColumn)
        return row
    }

    private fun buildOrderEntryRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        sizeInput = fieldEditText(hint = "Size (BTC)", inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            setText("0.01")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply { marginEnd = dp(6) }
        }
        leverageInput = fieldEditText(hint = "Lev.", inputType = InputType.TYPE_CLASS_NUMBER).apply {
            setText("5")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply { marginEnd = dp(8) }
        }

        submitOrderButton = Button(context).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { submitOrder(currentSide) }
        }
        applySideStyle()

        row.addView(sizeInput)
        row.addView(leverageInput)
        row.addView(submitOrderButton)
        return row
    }

    private fun submitOrder(side: PositionSide) {
        val size = sizeInput.text?.toString()?.trim().orEmpty()
        val leverage = leverageInput.text?.toString()?.trim()?.toIntOrNull() ?: 5
        if (size.toDoubleOrNull() == null || size.toDouble() <= 0.0) {
            sizeInput.error = "Enter a size"
            return
        }
        callbacks?.onOpenPosition?.invoke(side, size, leverage.coerceIn(1, 125))
    }

    private fun buildPositionsHeader(): View =
        TextView(context).apply {
            text = "Open positions"
            textSize = 12f
            setTextColor(mutedColor)
        }

    private fun buildPositionRow(position: PaperPosition): View {
        val isLong = position.side == PositionSide.LONG
        val sideColor = if (isLong) bullColor else bearColor

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "${position.symbol}  "
            textSize = 13f
            setTextColor(labelColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        titleRow.addView(TextView(context).apply {
            text = if (isLong) "LONG ${position.leverage}x" else "SHORT ${position.leverage}x"
            textSize = 11f
            setTextColor(sideColor)
        })
        infoColumn.addView(titleRow)
        infoColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%.4f @ %,.2f", position.total, position.entryPrice)
            textSize = 11.5f
            setTextColor(mutedColor)
        })

        val pnlColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        pnlColumn.addView(TextView(context).apply {
            val pnl = position.unrealizedPnl
            text = String.format(Locale.US, "%s%,.2f", if (pnl >= 0) "+" else "", pnl)
            textSize = 13f
            setTextColor(if (pnl >= 0) bullColor else bearColor)
        })
        pnlColumn.addView(TextView(context).apply {
            text = String.format(Locale.US, "%s%.1f%%", if (position.pnlPercentOfMargin >= 0) "+" else "", position.pnlPercentOfMargin)
            textSize = 11f
            setTextColor(mutedColor)
        })

        val closeButton = TextView(context).apply {
            text = "Close"
            textSize = 12f
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
            setOnClickListener { callbacks?.onClosePosition?.invoke(position) }
        }

        row.addView(infoColumn)
        row.addView(pnlColumn)
        row.addView(closeButton)
        return row
    }

    private fun showCredentialsDialog() {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val apiKeyField = dialogEditText("API Key").apply { setText(savedCredentials?.apiKey.orEmpty()) }
        val secretField = dialogEditText("Secret Key", isPassword = true).apply { setText(savedCredentials?.secretKey.orEmpty()) }
        val passphraseField = dialogEditText("Passphrase", isPassword = true).apply { setText(savedCredentials?.passphrase.orEmpty()) }
        val helpText = TextView(context).apply {
            text = "Create this under Bitget app → switch to Demo mode → " +
                "Personal Center → API Key Management → Create Demo API Key."
            textSize = 11.5f
            setTextColor(mutedColor)
            setPadding(0, dp(4), 0, dp(4))
        }

        container.addView(apiKeyField)
        container.addView(secretField)
        container.addView(passphraseField)
        container.addView(helpText)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Bitget Demo API Key")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val credentials = BitgetCredentials(
                    apiKey = apiKeyField.text?.toString()?.trim().orEmpty(),
                    secretKey = secretField.text?.toString()?.trim().orEmpty(),
                    passphrase = passphraseField.text?.toString()?.trim().orEmpty(),
                )
                if (!credentials.isComplete) {
                    apiKeyField.error = if (credentials.apiKey.isBlank()) "Required" else null
                    secretField.error = if (credentials.secretKey.isBlank()) "Required" else null
                    passphraseField.error = if (credentials.passphrase.isBlank()) "Required" else null
                    return@setOnClickListener
                }
                callbacks?.onCredentialsSubmitted?.invoke(credentials)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                callbacks?.onCredentialsCleared?.invoke()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun dialogEditText(hint: String, isPassword: Boolean = false): EditText =
        EditText(context).apply {
            this.hint = hint
            if (isPassword) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

    private fun fieldEditText(hint: String, inputType: Int): EditText =
        EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            textSize = 13f
            setTextColor(labelColor)
            setHintTextColor(mutedColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fieldBackground)
                setStroke(dp(1), borderColor)
            }
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }

    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }
}
