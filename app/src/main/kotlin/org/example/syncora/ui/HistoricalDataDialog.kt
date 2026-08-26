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
import androidx.core.content.res.ResourcesCompat
import org.example.syncora.R

/**
 * Full-screen modal launched from the bottom-bar "data" button.
 *
 * Mirrors [TradingModeDialog]'s glass-shell frame (same strong blurred
 * backdrop, bottom-weighted floating shell, floating circular back/close
 * controls, stacked frosted-glass row panels) but opens straight onto a
 * vertical list of data categories - Order Book, OHLCV, Open Interest,
 * Funding Rates - each row showing a small leading icon plus a
 * title/description text block. Each row drills into its own bare screen;
 * the content for those screens is intentionally left empty for now.
 */
class HistoricalDataDialog(context: Context) : Dialog(context, R.style.TradingModalTheme) {

    private enum class Screen { OPTIONS, ORDER_BOOK, OHLCV, OPEN_INTEREST, FUNDING_RATES }

    private data class Category(
        val screen: Screen,
        val title: String,
        val description: String,
        val iconRes: Int,
    )

    private val categories = listOf(
        Category(
            Screen.ORDER_BOOK,
            "Order Book",
            "Live bid and ask depth by price level.",
            R.drawable.ic_data_orderbook,
        ),
        Category(
            Screen.OHLCV,
            "OHCLV Data",
            "Open, high, low, close, and volume candles.",
            R.drawable.ic_data_ohlcv,
        ),
        Category(
            Screen.OPEN_INTEREST,
            "Open Interest",
            "Total outstanding derivative contracts.",
            R.drawable.ic_data_open_interest,
        ),
        Category(
            Screen.FUNDING_RATES,
            "Funding Rates",
            "Perpetual futures funding rate history.",
            R.drawable.ic_data_funding_rates,
        ),
    )

    private companion object {
        // Same glass-shell math as TradingModeDialog, kept in sync so the
        // two modals read as one consistent design language.
        const val SHELL_FILL_ALPHA_PERCENT = 0.12f
        const val ROW_FILL_ALPHA_PERCENT = 0.06f

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

    private val thinFont by lazy { ResourcesCompat.getFont(context, R.font.inter_thin) }

    private lateinit var rootView: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: View
    private lateinit var contentContainer: FrameLayout
    private val optionsScreen by lazy { buildOptionsScreen() }
    private var currentScreen: Screen = Screen.OPTIONS

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

        backButton = circleControl(glyph = "‹") { showScreen(Screen.OPTIONS) }.apply {
            visibility = View.GONE
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
        }

        titleText = TextView(context).apply {
            text = "Historical Data"
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

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        contentContainer.removeAllViews()
        if (screen == Screen.OPTIONS) {
            titleText.text = "Historical Data"
            backButton.visibility = View.GONE
            contentContainer.addView(optionsScreen)
        } else {
            val category = categories.first { it.screen == screen }
            titleText.text = category.title
            backButton.visibility = View.VISIBLE
            contentContainer.addView(scrollableCopy(buildEmptyScreen()))
        }
    }

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

    // ---- Screen 1: vertical list of icon + title/description rows, as stacked glass panels ----

    private fun buildOptionsScreen(): View {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        categories.forEachIndexed { index, category ->
            list.addView(buildTile(category))
            if (index != categories.lastIndex) {
                list.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
                })
            }
        }
        return list
    }

    private fun buildTile(category: Category): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = rowGlassDrawable()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { showScreen(category.screen) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(ImageView(context).apply {
            setImageResource(category.iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(14)
            }
        })

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textContainer.addView(TextView(context).apply {
            text = category.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
        })

        textContainer.addView(TextView(context).apply {
            text = category.description
            textSize = 11.5f
            typeface = thinFont ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(mutedColor)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)

        return row
    }

    /** A single stacked "pane" of glass: soft fill, ultra-thin border, radius matched to the shell. */
    private fun rowGlassDrawable(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ROW_CORNER_RADIUS_DP).toFloat()
        setColor(rowColor)
        setStroke(dp(1), rowBorderColor)
    }

    // ---- Screens 2-5: bare placeholders, content intentionally left empty ----

    private fun buildEmptyScreen(): View = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(160),
        )
    }
}
