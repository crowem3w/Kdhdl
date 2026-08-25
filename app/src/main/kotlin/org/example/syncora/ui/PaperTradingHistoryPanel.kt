package org.example.syncora.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.example.syncora.bitget.ClosedPaperTrade
import org.example.syncora.bitget.PositionSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Account History" screen: a plain reverse-chronological list of every
 * closed paper trade on this device (see [ClosedPaperTrade]), each row
 * showing side/leverage, size @ entry -> exit, and realized P&L.
 */
class PaperTradingHistoryPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val borderColor = Color.parseColor("#2A2E39")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val bullColor = Color.parseColor("#26A69A")
    private val bearColor = Color.parseColor("#EF5350")

    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)

    private lateinit var emptyStateText: TextView
    private lateinit var listContainer: LinearLayout

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        emptyStateText = TextView(context).apply {
            text = "No closed trades yet"
            textSize = 12.5f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
        }
        listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        addView(emptyStateText)
        addView(listContainer)
    }

    fun render(trades: List<ClosedPaperTrade>) {
        listContainer.removeAllViews()
        emptyStateText.visibility = if (trades.isEmpty()) View.VISIBLE else View.GONE
        trades.forEachIndexed { index, trade ->
            listContainer.addView(buildTradeRow(trade))
            if (index != trades.lastIndex) listContainer.addView(buildDivider())
        }
    }

    private fun buildTradeRow(trade: ClosedPaperTrade): View {
        val isLong = trade.side == PositionSide.LONG
        val sideColor = if (isLong) bullColor else bearColor
        val pnl = trade.realizedPnl

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
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
                text = "${trade.symbol}  "
                textSize = 12.5f
                setTextColor(labelColor)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        titleRow.addView(
            TextView(context).apply {
                text = if (isLong) "LONG ${trade.leverage}x" else "SHORT ${trade.leverage}x"
                textSize = 11f
                setTextColor(sideColor)
            },
        )
        infoColumn.addView(titleRow)
        infoColumn.addView(
            TextView(context).apply {
                text = String.format(
                    Locale.US,
                    "%.4f @ %,.2f \u2192 %,.2f",
                    trade.size,
                    trade.entryPrice,
                    trade.exitPrice,
                )
                textSize = 11f
                setTextColor(mutedColor)
                setPadding(0, dp(2), 0, 0)
            },
        )
        infoColumn.addView(
            TextView(context).apply {
                text = dateFormat.format(Date(trade.closedAt))
                textSize = 10.5f
                setTextColor(mutedColor)
                setPadding(0, dp(2), 0, 0)
            },
        )
        if (trade.totalFeesPaid > 0.0) {
            infoColumn.addView(
                TextView(context).apply {
                    text = String.format(Locale.US, "Fees: %,.2f USDT", trade.totalFeesPaid)
                    textSize = 10.5f
                    setTextColor(mutedColor)
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
        if (trade.totalFundingPaid != 0.0) {
            val funding = trade.totalFundingPaid
            infoColumn.addView(
                TextView(context).apply {
                    text = String.format(
                        Locale.US,
                        if (funding >= 0) "Funding: -%,.2f USDT" else "Funding: +%,.2f USDT",
                        kotlin.math.abs(funding),
                    )
                    textSize = 10.5f
                    setTextColor(mutedColor)
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }

        val pnlText = TextView(context).apply {
            text = String.format(Locale.US, "%s%,.2f USDT", if (pnl >= 0) "+" else "", pnl)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (pnl >= 0) bullColor else bearColor)
        }

        row.addView(infoColumn)
        row.addView(pnlText)
        return row
    }

    private fun buildDivider(): View = View(context).apply {
        setBackgroundColor(borderColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }
}
