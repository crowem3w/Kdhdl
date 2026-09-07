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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.example.syncora.bitget.ClosedPaperTrade
import org.example.syncora.bitget.PaperAccount
import org.example.syncora.bitget.PaperAccountBalance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PaperTradingAccountPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Callbacks(
        val onCreateAccount: (startingBalance: Double) -> Unit,
        val onResetAccount: () -> Unit,
    )

    private val surfaceColor = Color.parseColor("#1E222D")
    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val bullColor = Color.parseColor("#26A69A")
    private val trackColor = Color.parseColor("#2A2E39")
    private val infoBorderColor = Color.parseColor("#2E6E63")
    private val infoBackgroundColor = Color.parseColor("#122320")

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private var callbacks: Callbacks? = null
    private var onOpenHistory: (() -> Unit)? = null
    private var onExportReport: (() -> Unit)? = null

    private var currentAccount: PaperAccount? = null

    private lateinit var contentSwitcher: FrameLayout
    private lateinit var noAccountView: View
    private lateinit var accountView: View

    private lateinit var accountIdText: TextView
    private lateinit var balanceText: TextView
    private lateinit var balanceUsdText: TextView
    private lateinit var ratioText: TextView
    private lateinit var ratioBarRow: LinearLayout
    private lateinit var ratioBarFilled: View
    private lateinit var ratioBarEmpty: View

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        contentSwitcher = FrameLayout(context)
        noAccountView = buildNoAccountView()
        accountView = buildAccountView()
        contentSwitcher.addView(noAccountView)
        addView(contentSwitcher)
    }

    fun bind(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun setNavigationCallbacks(
        onOpenHistory: () -> Unit,
        onExportReport: () -> Unit,
    ) {
        this.onOpenHistory = onOpenHistory
        this.onExportReport = onExportReport
    }

    fun render(
        account: PaperAccount?,
        balance: PaperAccountBalance?,
        closedTrades: List<ClosedPaperTrade>,
    ) {
        currentAccount = account

        if (account == null) {
            swapContent(noAccountView)
            return
        }
        swapContent(accountView)

        accountIdText.text = "Account #${account.id} \u00B7 opened ${dateFormat.format(Date(account.createdAt))}"

        val equity = balance?.equity ?: 0.0
        balanceText.text = String.format(Locale.US, "%,.2f USDT", equity)
        balanceUsdText.text = String.format(Locale.US, "\u2248 $%,.2f", equity)

        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val recentTrades = closedTrades.filter { it.closedAt >= cutoff }
        if (recentTrades.isEmpty()) {
            ratioText.text = "\u2014"
            setRatioBar(0f)
        } else {
            val wins = recentTrades.count { it.realizedPnl > 0 }
            val ratio = (wins.toDouble() / recentTrades.size) * 100.0
            ratioText.text = String.format(Locale.US, "%.1f%%", ratio)
            setRatioBar((ratio / 100.0).toFloat())
        }
    }

    private fun setRatioBar(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        (ratioBarFilled.layoutParams as LinearLayout.LayoutParams).weight = clamped
        (ratioBarEmpty.layoutParams as LinearLayout.LayoutParams).weight = 1f - clamped
        ratioBarRow.requestLayout()
    }

    private fun swapContent(view: View) {
        if (contentSwitcher.childCount == 1 && contentSwitcher.getChildAt(0) === view) return
        contentSwitcher.removeAllViews()
        contentSwitcher.addView(view)
    }

    private fun buildNoAccountView(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(18), dp(6), dp(18))
            addView(
                TextView(context).apply {
                    text = "No local paper trading account yet"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(labelColor)
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(context).apply {
                    text = "Create a free local account to start practicing - no exchange, no API key."
                    textSize = 11.5f
                    setTextColor(mutedColor)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(8), dp(16))
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

    private fun buildAccountView(): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(buildAccountRow())
            addView(spacer(14))
            addView(buildStatsCard())
            addView(spacer(12))
            addView(buildDisclaimerBanner())
            addView(spacer(12))
            addView(buildMenuCard())
        }

    private fun buildAccountRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(context).apply {
            text = "\uD83D\uDCC8"
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bullColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(
            TextView(context).apply {
                text = "Paper Trading Account"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(labelColor)
            },
        )
        accountIdText = TextView(context).apply {
            text = "Account \u00B7 \u2014"
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        }
        textColumn.addView(accountIdText)

        val resetButton = TextView(context).apply {
            text = "\u21BB Reset Account"
            textSize = 11.5f
            setTextColor(bullColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), bullColor)
            }
            setOnClickListener { showResetAccountConfirmation() }
        }

        row.addView(icon)
        row.addView(textColumn)
        row.addView(resetButton)
        return row
    }

    private fun buildStatsCard(): View {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = cardBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val balanceColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        balanceColumn.addView(
            TextView(context).apply {
                text = "Virtual Balance"
                textSize = 11.5f
                setTextColor(mutedColor)
            },
        )
        val balanceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        balanceText = TextView(context).apply {
            text = "\u2014"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        }
        val walletIcon = TextView(context).apply {
            text = "\uD83D\uDCB3"
            textSize = 14f
            setPadding(dp(6), 0, 0, 0)
        }
        balanceRow.addView(balanceText)
        balanceRow.addView(walletIcon)
        balanceColumn.addView(balanceRow)
        balanceUsdText = TextView(context).apply {
            text = "\u2248 $0.00"
            textSize = 11f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        }
        balanceColumn.addView(balanceUsdText)

        val divider = View(context).apply {
            setBackgroundColor(borderColor)
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
        }

        val ratioColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ratioColumn.addView(
            TextView(context).apply {
                text = "Profitable Ratio"
                textSize = 11.5f
                setTextColor(mutedColor)
            },
        )
        ratioText = TextView(context).apply {
            text = "\u2014"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            setPadding(0, dp(4), 0, 0)
        }
        ratioColumn.addView(ratioText)
        ratioColumn.addView(
            TextView(context).apply {
                text = "Last 30 days"
                textSize = 11f
                setTextColor(mutedColor)
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        ratioBarRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        ratioBarFilled = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(bullColor)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 0f)
        }
        ratioBarEmpty = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(trackColor)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f)
        }
        ratioBarRow.addView(ratioBarFilled)
        ratioBarRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(2), dp(4)) })
        ratioBarRow.addView(ratioBarEmpty)
        ratioColumn.addView(ratioBarRow)

        card.addView(balanceColumn)
        card.addView(divider)
        card.addView(ratioColumn)
        return card
    }

    private fun buildDisclaimerBanner(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(infoBackgroundColor)
                setStroke(dp(1), infoBorderColor)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val icon = TextView(context).apply {
            text = "\u24D8"
            textSize = 13f
            setTextColor(bullColor)
            setPadding(0, 0, dp(8), 0)
        }
        val text = TextView(context).apply {
            text = "This is a paper trading account. All trades are simulated and do not use real funds."
            textSize = 11.5f
            setTextColor(labelColor)
        }
        row.addView(icon)
        row.addView(text)
        return row
    }

    private fun buildMenuCard(): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBackground()
        }
        val historyRow = buildMenuRow(
            title = "Account History",
            subtitle = "View trade history and performance",
        ) { onOpenHistory?.invoke() }
        val exportRow = buildMenuRow(
            title = "Export Report",
            subtitle = "Download performance report",
        ) { onExportReport?.invoke() }
        card.addView(historyRow)
        card.addView(buildDivider())
        card.addView(exportRow)
        return card
    }

    private fun buildMenuRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(
            TextView(context).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(labelColor)
            },
        )
        textColumn.addView(
            TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(mutedColor)
                setPadding(0, dp(2), 0, 0)
            },
        )
        val chevron = TextView(context).apply {
            text = "\u203A"
            textSize = 18f
            setTextColor(mutedColor)
        }
        row.addView(textColumn)
        row.addView(chevron)
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
        val amountField = EditText(context).apply {
            hint = "Starting balance (USDT)"
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

    private fun showResetAccountConfirmation() {
        AlertDialog.Builder(context)
            .setTitle("Reset paper trading account?")
            .setMessage("This permanently deletes this local account, its balance, and any open positions so you can start over. This can't be undone.")
            .setPositiveButton("Reset") { _, _ -> callbacks?.onResetAccount?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(surfaceColor)
        setStroke(dp(1), borderColor)
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }
}
