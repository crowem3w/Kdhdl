package org.example.syncora.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import org.example.syncora.R

/**
 * Full-screen modal launched from the § header button.
 *
 * First shows two vertically stacked options - Paper Trading / Live Trading
 * - then swaps its content area in place when one is picked. The app
 * content behind the modal is real-time blurred on Android 12+ (where the
 * device/user has cross-window blur enabled); on older devices it falls
 * back to a plain dark scrim, since window blur-behind isn't available
 * pre-S.
 *
 * Visual language: a full-bleed immersive backdrop (strong window blur)
 * with the modal itself floating above it as a bottom-weighted, layered
 * stack of frosted-glass panels - a near-black bg/10 outer shell holding
 * lighter bg/5-8 "glass" panels per row, ultra-thin soft borders, large
 * consistent corner radii, and floating circular controls instead of a
 * conventional title bar. Shadows stay minimal; depth comes from
 * transparency and blur rather than elevation.
 */
class TradingModeDialog(
    context: Context,
    private val paperAccountContent: PaperTradingAccountPanel,
    private val paperHistoryContent: View,
    private val liveTradingContent: View,
    private val onExportReport: () -> Unit,
) : Dialog(context, R.style.TradingModalTheme) {

    private enum class Screen { OPTIONS, PAPER_ACCOUNT, PAPER_HISTORY, LIVE }

    private companion object {
        // Android has no native 0-100% "blurriness" scale, so these knobs
        // translate the requested percentages into concrete values:

        // Outer shell fill: black at ~12% opacity - a "bg-black/10" glass
        // surface that lets the blurred backdrop read straight through it.
        const val SHELL_FILL_ALPHA_PERCENT = 0.12f

        // Inner row panels sit a touch lighter (white, low alpha) so each
        // one reads as its own separate pane of glass stacked on the shell.
        const val ROW_FILL_ALPHA_PERCENT = 0.06f

        // Backdrop (whole homepage/chart) blur radius behind the window,
        // Android 12+ only. Pushed close to the ceiling for a strong,
        // cinematic blur that still leaves shapes/motion legible.
        const val BACKDROP_BLUR_PERCENT = 0.92f
        const val MAX_BACKDROP_BLUR_DP = 100

        const val SHELL_CORNER_RADIUS_DP = 32
        const val ROW_CORNER_RADIUS_DP = 20
        const val CONTROL_DIAMETER_DP = 40
        const val EDGE_SPACING_DP = 18
        const val BOTTOM_SPACING_DP = 28
    }

    private val shellColor = Color.argb((255 * SHELL_FILL_ALPHA_PERCENT).toInt(), 0, 0, 0)
    private val rowColor = Color.argb((255 * ROW_FILL_ALPHA_PERCENT).toInt(), 255, 255, 255)
    private val controlColor = Color.argb((255 * ROW_FILL_ALPHA_PERCENT).toInt(), 255, 255, 255)
    private val borderColor = Color.parseColor("#24FFFFFF") // ultra-thin soft border
    private val rowBorderColor = Color.parseColor("#14FFFFFF")
    private val cardHighlightTop = Color.parseColor("#33FFFFFF")
    private val dividerColor = Color.parseColor("#14FFFFFF")
    private val labelColor = Color.parseColor("#F5F6F7")
    private val mutedColor = Color.parseColor("#AEB2BD")
    private val scrimColor = Color.parseColor("#40000000") // light scrim - backdrop stays visible

    private lateinit var rootView: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: View
    private lateinit var contentContainer: FrameLayout
    private val optionsScreen by lazy { buildOptionsScreen() }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        applyWindowBlur()
        paperAccountContent.setNavigationCallbacks(
            onOpenHistory = { showScreen(Screen.PAPER_HISTORY) },
            onExportReport = onExportReport,
        )
        showScreen(Screen.OPTIONS)
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)
        win.setDimAmount(0.18f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val blurRadiusDp = (MAX_BACKDROP_BLUR_DP * BACKDROP_BLUR_PERCENT).toInt()
                win.attributes = win.attributes.apply { blurBehindRadius = dp(blurRadiusDp) }
            }
        }
    }

    // ---- Root scaffold: full-bleed scrim + bottom-weighted floating glass shell ----

    private fun buildRootView(): View {
        rootView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(scrimColor)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dp(SHELL_CORNER_RADIUS_DP).toFloat()

        // Outer shell: a single large-radius frosted panel, floated off all
        // four edges and weighted toward the bottom of the screen so the
        // backdrop stays visible above it.
        val cardOuter = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = cornerRadiusPx
                setColor(shellColor)
                setStroke(dp(1), borderColor)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dp(6).toFloat() // minimal shadow - depth reads from transparency, not elevation
            isClickable = true
            setOnClickListener { /* consume: don't dismiss when tapping inside the shell */ }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = dp(EDGE_SPACING_DP)
                marginEnd = dp(EDGE_SPACING_DP)
                bottomMargin = dp(BOTTOM_SPACING_DP)
            }
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        cardContent.addView(buildHeaderRow())
        cardContent.addView(View(context).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(18); bottomMargin = dp(18)
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
                leftMargin = dp(SHELL_CORNER_RADIUS_DP)
                rightMargin = dp(SHELL_CORNER_RADIUS_DP)
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

        backButton = circleControl(glyph = "‹") { showScreen(backTargetFor(currentScreen)) }.apply {
            visibility = View.GONE
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
        }

        titleText = TextView(context).apply {
            text = "Trading Mode"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = circleControl(glyph = "✕", glyphSizeSp = 13f) { dismiss() }

        row.addView(backButton)
        row.addView(titleText)
        row.addView(closeButton)
        return row
    }

    /** A small floating circular glass control - the "floating circular controls" affordance used for back/close. */
    private fun circleControl(glyph: String, glyphSizeSp: Float = 15f, onClick: () -> Unit): View {
        val diameter = dp(CONTROL_DIAMETER_DP)
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(controlColor)
                setStroke(dp(1), rowBorderColor)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(diameter, diameter)
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = glyph
                textSize = glyphSizeSp
                setTextColor(labelColor)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
    }

    /** Opens the dialog straight to the paper trading account screen, skipping the mode picker. */
    fun showPaperTradingScreen() {
        showScreen(Screen.PAPER_ACCOUNT)
    }

    /** Opens the dialog straight to the live trading order screen, skipping the mode picker. */
    fun showLiveTradingScreen() {
        showScreen(Screen.LIVE)
    }

    private var currentScreen: Screen = Screen.OPTIONS

    /** Where the back chevron returns to from each screen - a shallow, one-level-deep back stack. */
    private fun backTargetFor(screen: Screen): Screen = when (screen) {
        Screen.PAPER_HISTORY -> Screen.PAPER_ACCOUNT
        else -> Screen.OPTIONS
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        contentContainer.removeAllViews()
        when (screen) {
            Screen.OPTIONS -> {
                titleText.text = "Trading Mode"
                backButton.visibility = View.GONE
                contentContainer.addView(optionsScreen)
            }
            Screen.PAPER_ACCOUNT -> {
                titleText.text = "Paper Trading"
                backButton.visibility = View.VISIBLE
                contentContainer.addView(scrollableCopy(paperAccountContent))
            }
            Screen.PAPER_HISTORY -> {
                titleText.text = "Account History"
                backButton.visibility = View.VISIBLE
                contentContainer.addView(scrollableCopy(paperHistoryContent))
            }
            Screen.LIVE -> {
                titleText.text = "Live Trading"
                backButton.visibility = View.VISIBLE
                contentContainer.addView(scrollableCopy(liveTradingContent))
            }
        }
    }

    /** Wraps [content] in a fresh scroll container, detaching it from any previous parent first. */
    private fun scrollableCopy(content: View): View {
        (content.parent as? ViewGroup)?.removeView(content)
        return ScrollView(context).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(content)
        }
    }

    // ---- Screen 1: mode picker, rendered as stacked frosted-glass row panels ----

    private fun buildOptionsScreen(): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val paperRow = buildOptionRow(
            title = "Paper Trading",
            subtitle = "Practice with a free local account - no exchange required",
            iconRes = R.drawable.ic_mode_paper,
            tintIcon = true,
            onClick = { showScreen(Screen.PAPER_ACCOUNT) },
        )
        val liveRow = buildOptionRow(
            title = "Live Trading",
            subtitle = "Trade with real funds",
            iconRes = R.drawable.ic_mode_live,
            tintIcon = true,
            onClick = { showScreen(Screen.LIVE) },
        )

        container.addView(paperRow)
        container.addView(rowSpacer())
        container.addView(liveRow)
        return container
    }

    /** Generous, uncluttered spacing between stacked glass panels instead of a flat divider line. */
    private fun rowSpacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
    }

    private fun buildOptionRow(
        title: String,
        subtitle: String,
        iconRes: Int,
        tintIcon: Boolean,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = rowGlassDrawable()
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener { onClick() }
        }

        val iconView = ImageView(context).apply {
            setImageResource(iconRes)
            if (tintIcon) setColorFilter(labelColor)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) }
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
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
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(iconView)
        row.addView(textColumn)
        row.addView(chevron)
        return row
    }

    /** A single stacked "pane" of glass: soft fill, ultra-thin border, radius matched to the shell. */
    private fun rowGlassDrawable(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ROW_CORNER_RADIUS_DP).toFloat()
        setColor(rowColor)
        setStroke(dp(1), rowBorderColor)
    }

    // ---- Screen: blank/placeholder content (Live Trading) ----

}
