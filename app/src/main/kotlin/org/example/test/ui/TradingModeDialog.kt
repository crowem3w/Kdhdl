package org.example.test.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.example.test.R

/**
 * Full-screen modal launched from the § header button.
 *
 * First shows two vertically stacked options - Paper Trading / Live Trading
 * - then swaps its content area in place when one is picked. The app
 * content behind the modal is real-time blurred on Android 12+ (where the
 * device/user has cross-window blur enabled); on older devices it falls
 * back to a plain dark scrim, since window blur-behind isn't available
 * pre-S. The modal surface itself uses a translucent, bordered "glass" card
 * to read as dark-mode glassmorphism either way.
 */
class TradingModeDialog(
    context: Context,
    private val paperTradingContent: View,
) : Dialog(context, R.style.TradingModalTheme) {

    private enum class Screen { OPTIONS, PAPER, LIVE, AGENTIC }

    private companion object {
        // Android has no native 0-100% "blurriness" scale, so these two
        // knobs translate the requested percentages into concrete values:

        // Card fill: charcoal-black at 75% opacity (25% see-through), which
        // is what "blurriness" means for a flat glass panel - the more
        // translucent it is, the more it reads as frosted glass rather than
        // a solid card.
        const val CARD_FILL_OPACITY_PERCENT = 0.75f
        val CARD_BASE_COLOR_RGB = Color.parseColor("#1C1C1E") // charcoal-black

        // Backdrop (whole homepage/chart) blur radius behind the window,
        // Android 12+ only. We treat 100dp as a "fully blurred" ceiling and
        // scale the requested percentage against it.
        const val BACKDROP_BLUR_PERCENT = 0.80f
        const val MAX_BACKDROP_BLUR_DP = 100

        const val CARD_CORNER_RADIUS_DP = 14
    }

    private val cardColor = Color.argb(
        (255 * CARD_FILL_OPACITY_PERCENT).toInt(),
        Color.red(CARD_BASE_COLOR_RGB),
        Color.green(CARD_BASE_COLOR_RGB),
        Color.blue(CARD_BASE_COLOR_RGB),
    )
    private val cardHighlightTop = Color.parseColor("#4DFFFFFF")
    private val dividerColor = Color.parseColor("#26FFFFFF")
    private val labelColor = Color.parseColor("#EAECEF")
    private val mutedColor = Color.parseColor("#B2B5BE")
    private val scrimColor = Color.parseColor("#99000000")

    private lateinit var rootView: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: TextView
    private lateinit var contentContainer: FrameLayout
    private val optionsScreen by lazy { buildOptionsScreen() }
    private val liveTradingScreen by lazy { buildPlaceholderScreen("Live trading isn't available yet", "This mode will connect to your real Bitget account.") }
    private val agenticTradingScreen by lazy { buildPlaceholderScreen("Agentic trading isn't available yet", "This mode will let an AI agent trade on your behalf.") }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        applyWindowBlur()
        showScreen(Screen.OPTIONS)
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)
        win.setDimAmount(0.35f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val blurRadiusDp = (MAX_BACKDROP_BLUR_DP * BACKDROP_BLUR_PERCENT).toInt()
                win.attributes = win.attributes.apply { blurBehindRadius = dp(blurRadiusDp) }
            }
        }
    }

    // ---- Root scaffold: full-screen scrim + centered glass card ----

    private fun buildRootView(): View {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(scrimColor)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dp(CARD_CORNER_RADIUS_DP).toFloat()

        // Outer shell: clips all children (content + highlight strip) to the
        // same rounded rect so nothing pokes past the glass edge.
        val cardOuter = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = cornerRadiusPx
                setColor(cardColor)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dp(16).toFloat()
            isClickable = true
            setOnClickListener { /* consume: don't dismiss when tapping inside the card */ }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
        }
        cardContent.addView(buildHeaderRow())
        cardContent.addView(View(context).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(14); bottomMargin = dp(14)
            }
        })
        contentContainer = FrameLayout(context)
        cardContent.addView(contentContainer)

        // Thin gradient sheen along the top edge - the classic glassmorphism
        // "light catching the rim of the glass" cue.
        val highlightStrip = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, cardHighlightTop, Color.TRANSPARENT),
            )
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(CARD_CORNER_RADIUS_DP)
                rightMargin = dp(CARD_CORNER_RADIUS_DP)
                gravity = Gravity.TOP
            }
        }

        cardOuter.addView(cardContent)
        cardOuter.addView(highlightStrip)

        rootView.addView(cardOuter)
        return rootView
    }

    private fun buildHeaderRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        backButton = TextView(context).apply {
            text = "‹"
            textSize = 20f
            setTextColor(labelColor)
            isClickable = true
            isFocusable = true
            setPadding(0, 0, dp(10), 0)
            visibility = View.GONE
            setOnClickListener { showScreen(Screen.OPTIONS) }
        }

        titleText = TextView(context).apply {
            text = "Trading Mode"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(mutedColor)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { dismiss() }
        }

        row.addView(backButton)
        row.addView(titleText)
        row.addView(closeButton)
        return row
    }

    /** Opens the dialog straight to the paper trading order screen, skipping the mode picker. */
    fun showPaperTradingScreen() {
        showScreen(Screen.PAPER)
    }

    private fun showScreen(screen: Screen) {
        contentContainer.removeAllViews()
        when (screen) {
            Screen.OPTIONS -> {
                titleText.text = "Trading Mode"
                backButton.visibility = View.GONE
                contentContainer.addView(optionsScreen)
            }
            Screen.PAPER -> {
                titleText.text = "Paper Trading"
                backButton.visibility = View.VISIBLE
                (paperTradingContent.parent as? ViewGroup)?.removeView(paperTradingContent)
                val scroll = ScrollView(context).apply {
                    isFillViewport = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    addView(paperTradingContent)
                }
                contentContainer.addView(scroll)
            }
            Screen.LIVE -> {
                titleText.text = "Live Trading"
                backButton.visibility = View.VISIBLE
                contentContainer.addView(liveTradingScreen)
            }
            Screen.AGENTIC -> {
                titleText.text = "Agentic Trading"
                backButton.visibility = View.VISIBLE
                contentContainer.addView(agenticTradingScreen)
            }
        }
    }

    // ---- Screen 1: mode picker ----

    private fun buildOptionsScreen(): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val paperRow = buildOptionRow(
            title = "Paper Trading",
            subtitle = "Practice with virtual funds on Bitget's demo account",
            iconRes = R.drawable.ic_mode_paper,
            tintIcon = true,
            onClick = { showScreen(Screen.PAPER) },
        )
        val agenticRow = buildOptionRow(
            title = "Agentic Trading",
            subtitle = "Let an AI agent trade on your behalf",
            iconRes = R.drawable.ic_mode_agentic,
            tintIcon = true,
            onClick = { showScreen(Screen.AGENTIC) },
        )
        val liveRow = buildOptionRow(
            title = "Live Trading",
            subtitle = "Trade with real funds",
            iconRes = R.drawable.ic_mode_live,
            tintIcon = true,
            onClick = { showScreen(Screen.LIVE) },
        )

        container.addView(paperRow)
        container.addView(divider())
        container.addView(agenticRow)
        container.addView(divider())
        container.addView(liveRow)
        return container
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun buildOptionRow(
        title: String,
        subtitle: String,
        iconRes: Int,
        tintIcon: Boolean,
        onClick: () -> Unit,
    ): View {
        val rippleValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(rippleValue.resourceId)
            setPadding(dp(4), dp(14), dp(4), dp(14))
            setOnClickListener { onClick() }
        }

        val iconView = ImageView(context).apply {
            setImageResource(iconRes)
            if (tintIcon) setColorFilter(labelColor)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            text = title
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        })
        textColumn.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        })

        val chevron = TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(mutedColor)
        }

        row.addView(iconView)
        row.addView(textColumn)
        row.addView(chevron)
        return row
    }

    // ---- Screen: blank/placeholder content (Live Trading, Agentic Trading) ----

    private fun buildPlaceholderScreen(title: String, subtitle: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(28), dp(8), dp(28))
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(labelColor)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(mutedColor)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), 0)
            })
        }
}
