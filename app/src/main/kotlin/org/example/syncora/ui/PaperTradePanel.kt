package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.example.syncora.bitget.FeeRates
import org.example.syncora.bitget.LatencyConfig
import org.example.syncora.bitget.PaperAccount
import org.example.syncora.bitget.PaperAccountBalance
import org.example.syncora.bitget.PaperPosition
import org.example.syncora.bitget.PendingLimitOrder
import org.example.syncora.bitget.PositionSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale












class PaperTradePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Callbacks(
        val onCreateAccount: (startingBalance: Double) -> Unit,
        val onDeposit: (amount: Double) -> Unit,
        val onResetAccount: () -> Unit,
        val onOpenPosition: (side: PositionSide, size: String, leverage: Int) -> Unit,
        val onClosePosition: (PaperPosition) -> Unit,
        val onCancelPendingOrder: (PendingLimitOrder) -> Unit = {},
        
        
        
        val onSetLatencyConfig: (LatencyConfig) -> Unit = {},
    )

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")
    private val fieldBackground = Color.parseColor("#131722")

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private var callbacks: Callbacks? = null
    private var currentAccount: PaperAccount? = null
    private var nextDepositAvailableAt: Long? = null

    private lateinit var accountText: TextView
    private lateinit var actionButton: TextView
    private lateinit var contentSwitcher: FrameLayout
    private lateinit var noAccountView: View
    private lateinit var tradingContent: View
    private lateinit var balanceText: TextView
    private lateinit var balancePnlText: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var positionsContainer: LinearLayout
    private lateinit var pendingOrdersHeader: TextView
    private lateinit var pendingOrdersContainer: LinearLayout
    private lateinit var sizeInput: EditText
    private lateinit var leverageInput: EditText
    private lateinit var submitOrderButton: Button
    private lateinit var feeRateText: TextView
    private lateinit var latencyText: TextView
    private var currentSide: PositionSide = PositionSide.LONG
    private var currentLatencyConfig: LatencyConfig = LatencyConfig.DEFAULT

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

        contentSwitcher = FrameLayout(context)
        noAccountView = buildNoAccountView()
        tradingContent = buildTradingContent()
        contentSwitcher.addView(noAccountView)
        addView(contentSwitcher)
    }

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    




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
        account: PaperAccount?,
        balance: PaperAccountBalance?,
        positions: List<PaperPosition>,
        pendingOrders: List<PendingLimitOrder> = emptyList(),
        lastError: String?,
        nextDepositAvailableAt: Long?,
        feeRates: FeeRates? = null,
        latencyConfig: LatencyConfig? = null,
    ) {
        currentAccount = account
        this.nextDepositAvailableAt = nextDepositAvailableAt

        if (feeRates != null) renderFeeRates(feeRates)
        if (latencyConfig != null) renderLatency(latencyConfig)

        if (account == null) {
            accountText.text = "No local account yet"
            actionButton.text = "+ Create Account"
            actionButton.setOnClickListener { showCreateAccountDialog() }
            swapContent(noAccountView)
            return
        }

        accountText.text = "Account #${account.id} \u00B7 opened ${dateFormat.format(Date(account.createdAt))}"
        actionButton.text = "+ Deposit"
        actionButton.setOnClickListener { showDepositDialog() }
        swapContent(tradingContent)

        submitOrderButton.isEnabled = true
        submitOrderButton.alpha = 1f

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
        renderPendingOrders(pendingOrders)
    }

    private fun swapContent(view: View) {
        if (contentSwitcher.childCount == 1 && contentSwitcher.getChildAt(0) === view) return
        contentSwitcher.removeAllViews()
        contentSwitcher.addView(view)
    }

    





    private fun renderFeeRates(feeRates: FeeRates) {
        val makerPct = feeRates.makerRate * 100.0
        val takerPct = feeRates.takerRate * 100.0
        val suffix = if (feeRates.isAccountSpecific) "your rate" else "standard rate"
        feeRateText.text = String.format(
            Locale.US,
            "Fees: %.3f%% maker / %.3f%% taker (%s)",
            makerPct,
            takerPct,
            suffix,
        )
    }

    







    private fun renderLatency(config: LatencyConfig) {
        currentLatencyConfig = config
        latencyText.text = if (!config.enabled) {
            "Latency: off (tap to configure)"
        } else if (config.jitterMs > 0L) {
            String.format(
                Locale.US,
                "Latency: %d\u2013%dms simulated (tap to configure)",
                config.baseDelayMs,
                config.baseDelayMs + config.jitterMs,
            )
        } else {
            String.format(Locale.US, "Latency: %dms simulated (tap to configure)", config.baseDelayMs)
        }
    }

    private fun renderPositions(positions: List<PaperPosition>) {
        positionsContainer.removeAllViews()
        emptyStateText.visibility = if (positions.isEmpty()) View.VISIBLE else View.GONE
        for (position in positions) {
            positionsContainer.addView(buildPositionRow(position))
        }
    }

    private fun renderPendingOrders(pendingOrders: List<PendingLimitOrder>) {
        pendingOrdersContainer.removeAllViews()
        pendingOrdersHeader.visibility = if (pendingOrders.isEmpty()) View.GONE else View.VISIBLE
        for (order in pendingOrders) {
            pendingOrdersContainer.addView(buildPendingOrderRow(order))
        }
    }

    private fun buildPendingOrderRow(order: PendingLimitOrder): View {
        val isLong = order.side == PositionSide.LONG
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
        infoColumn.addView(
            TextView(context).apply {
                text = if (isLong) "LONG limit ${order.leverage}x" else "SHORT limit ${order.leverage}x"
                textSize = 11f
                setTextColor(sideColor)
            },
        )
        infoColumn.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "%.4f @ %,.2f", order.sizeInBaseCoin, order.limitPrice)
                textSize = 11.5f
                setTextColor(mutedColor)
            },
        )

        val cancelButton = TextView(context).apply {
            text = "Cancel"
            textSize = 12f
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), borderColor)
            }
            setOnClickListener { callbacks?.onCancelPendingOrder?.invoke(order) }
        }

        row.addView(infoColumn)
        row.addView(cancelButton)
        return row
    }

    

    private fun buildHeaderRow(): View {
        val outer = LinearLayout(context).apply {
            orientation = VERTICAL
        }

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
        actionButton = TextView(context).apply {
            text = "+ Create Account"
            textSize = 12f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        row.addView(title)
        row.addView(actionButton)
        outer.addView(row)

        accountText = TextView(context).apply {
            text = "No local account yet"
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, dp(6))
            isLongClickable = true
            setOnLongClickListener {
                if (currentAccount != null) showResetAccountConfirmation()
                true
            }
        }
        outer.addView(accountText)

        return outer
    }

    

    private fun buildNoAccountView(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(18), dp(6), dp(18))
            addView(
                TextView(context).apply {
                    text = "Practice with a free, local paper trading account"
                    textSize = 12.5f
                    setTextColor(mutedColor)
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(context).apply {
                    text = "Runs entirely on this device - no exchange, no API key. Deposits are limited to once a month."
                    textSize = 11f
                    setTextColor(mutedColor)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(8), dp(14))
                },
            )
            addView(
                Button(context).apply {
                    isAllCaps = false
                    text = "Create Paper Trading Account"
                    setTextColor(Color.WHITE)
                    background = pillBackground(bullColor)
                    setOnClickListener { showCreateAccountDialog() }
                },
            )
        }

    

    private fun buildTradingContent(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
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

            addView(spacer(6))
            pendingOrdersHeader = TextView(context).apply {
                text = "Pending limit orders"
                textSize = 12f
                setTextColor(mutedColor)
                visibility = View.GONE
            }
            addView(pendingOrdersHeader)

            pendingOrdersContainer = LinearLayout(context).apply { orientation = VERTICAL }
            addView(pendingOrdersContainer)
        }

    private fun buildBalanceRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = "Virtual balance"
            textSize = 12f
            setTextColor(mutedColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

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

        row.addView(label)
        row.addView(balanceColumn)
        return row
    }

    private fun buildOrderEntryRow(): View {
        val container = LinearLayout(context).apply { orientation = VERTICAL }

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

        
        
        
        feeRateText = TextView(context).apply {
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(6), dp(2), dp(0))
        }

        
        
        
        latencyText = TextView(context).apply {
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(dp(2), dp(2), dp(2), dp(0))
            setOnClickListener { showLatencySettingsDialog() }
        }

        container.addView(row)
        container.addView(feeRateText)
        container.addView(latencyText)
        return container
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
        titleRow.addView(
            TextView(context).apply {
                text = "${position.symbol}  "
                textSize = 13f
                setTextColor(labelColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        titleRow.addView(
            TextView(context).apply {
                text = if (isLong) "LONG ${position.leverage}x" else "SHORT ${position.leverage}x"
                textSize = 11f
                setTextColor(sideColor)
            },
        )
        infoColumn.addView(titleRow)
        infoColumn.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "%.4f @ %,.2f", position.total, position.entryPrice)
                textSize = 11.5f
                setTextColor(mutedColor)
            },
        )
        if (position.fundingPaidSoFar != 0.0) {
            val funding = position.fundingPaidSoFar
            infoColumn.addView(
                TextView(context).apply {
                    text = String.format(
                        Locale.US,
                        if (funding >= 0) "Funding paid: %,.2f" else "Funding received: %,.2f",
                        kotlin.math.abs(funding),
                    )
                    textSize = 10.5f
                    setTextColor(mutedColor)
                },
            )
        }

        val pnlColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        pnlColumn.addView(
            TextView(context).apply {
                val pnl = position.unrealizedPnl
                text = String.format(Locale.US, "%s%,.2f", if (pnl >= 0) "+" else "", pnl)
                textSize = 13f
                setTextColor(if (pnl >= 0) bullColor else bearColor)
            },
        )
        pnlColumn.addView(
            TextView(context).apply {
                text = String.format(Locale.US, "%s%.1f%%", if (position.pnlPercentOfMargin >= 0) "+" else "", position.pnlPercentOfMargin)
                textSize = 11f
                setTextColor(mutedColor)
            },
        )

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

    

    private fun showCreateAccountDialog() {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val explainer = TextView(context).apply {
            text = "Pick a starting virtual balance. This is a local account only - " +
                "nothing here touches a real or exchange demo account."
            textSize = 11.5f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(10))
        }
        val amountField = dialogEditText("Starting balance (USDT)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10000")
        }
        container.addView(explainer)
        container.addView(amountField)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Create Paper Trading Account")
            .setView(container)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = amountField.text?.toString()?.trim()?.toDoubleOrNull()
                if (amount == null || amount <= 0.0) {
                    amountField.error = "Enter an amount greater than zero"
                    return@setOnClickListener
                }
                callbacks?.onCreateAccount?.invoke(amount)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    

    private fun showDepositDialog() {
        val nextAvailable = nextDepositAvailableAt
        if (nextAvailable != null) {
            AlertDialog.Builder(context)
                .setTitle("Deposit unavailable")
                .setMessage(
                    "You've already made a deposit this month. Your next deposit " +
                        "unlocks on ${dateFormat.format(Date(nextAvailable))}.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val explainer = TextView(context).apply {
            text = "Add virtual funds to this account. Only one deposit is allowed per calendar month."
            textSize = 11.5f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(10))
        }
        val amountField = dialogEditText("Deposit amount (USDT)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1000")
        }
        container.addView(explainer)
        container.addView(amountField)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Deposit")
            .setView(container)
            .setPositiveButton("Deposit", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = amountField.text?.toString()?.trim()?.toDoubleOrNull()
                if (amount == null || amount <= 0.0) {
                    amountField.error = "Enter an amount greater than zero"
                    return@setOnClickListener
                }
                callbacks?.onDeposit?.invoke(amount)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    

    







    private fun showLatencySettingsDialog() {
        val config = currentLatencyConfig
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val explainer = TextView(context).apply {
            text = "Simulates the network + engine delay a real order would face before " +
                "reaching the exchange - fills are priced against the order book after " +
                "this delay, not at the instant you tap Long/Short."
            textSize = 11.5f
            setTextColor(mutedColor)
            setPadding(0, 0, 0, dp(10))
        }
        val enabledToggle = CheckBox(context).apply {
            text = "Simulate latency"
            setTextColor(labelColor)
            isChecked = config.enabled
        }
        val delayField = dialogEditText("Base delay (ms)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.baseDelayMs.toString())
        }
        val jitterField = dialogEditText("Extra random jitter, up to (ms)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.jitterMs.toString())
        }
        val rangeHint = TextView(context).apply {
            text = String.format(
                Locale.US,
                "Delay: %d\u2013%dms \u00B7 Jitter: %d\u2013%dms",
                LatencyConfig.MIN_DELAY_MS,
                LatencyConfig.MAX_DELAY_MS,
                LatencyConfig.MIN_JITTER_MS,
                LatencyConfig.MAX_JITTER_MS,
            )
            textSize = 10.5f
            setTextColor(mutedColor)
            setPadding(0, dp(6), 0, 0)
        }
        container.addView(explainer)
        container.addView(enabledToggle)
        container.addView(delayField)
        container.addView(jitterField)
        container.addView(rangeHint)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Latency Simulation")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val baseDelay = delayField.text?.toString()?.trim()?.toLongOrNull()
                val jitter = jitterField.text?.toString()?.trim()?.toLongOrNull()
                if (baseDelay == null || baseDelay < 0L) {
                    delayField.error = "Enter a delay in milliseconds"
                    return@setOnClickListener
                }
                if (jitter == null || jitter < 0L) {
                    jitterField.error = "Enter a jitter amount in milliseconds"
                    return@setOnClickListener
                }
                val updated = LatencyConfig(
                    enabled = enabledToggle.isChecked,
                    baseDelayMs = baseDelay,
                    jitterMs = jitter,
                ).coerced()
                callbacks?.onSetLatencyConfig?.invoke(updated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    

    private fun showResetAccountConfirmation() {
        AlertDialog.Builder(context)
            .setTitle("Reset paper trading account?")
            .setMessage("This permanently deletes this local account, its balance, and any open positions so you can start over. This can't be undone.")
            .setPositiveButton("Reset") { _, _ -> callbacks?.onResetAccount?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dialogEditText(hint: String): EditText =
        EditText(context).apply {
            this.hint = hint
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