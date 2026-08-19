package org.example.test.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.ScrollView
import android.widget.TextView

/**
 * Terminal-style readout view. Its data source - the on-device agent's
 * decision loop and its log bus - has been removed, so this view no
 * longer collects or renders anything by default; it just shows a static
 * placeholder. Kept as UI scaffolding in case a future data source is
 * wired in via [appendLine]/[clearDisplay].
 */
class AgentTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    private val placeholderColor = Color.parseColor("#8B93A7")
    private val textView: TextView

    init {
        setBackgroundColor(Color.parseColor("#0D1117"))
        isFillViewport = true
        textView = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(placeholderColor)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = "Agent implementation removed."
        }
        addView(textView)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Appends a single plain-text line. No-op data source is wired to this by default. */
    fun appendLine(text: String) {
        textView.append("\n$text")
    }

    /** Removes every line currently shown. */
    fun clearDisplay() {
        textView.text = ""
    }
}
