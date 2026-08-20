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
import androidx.core.content.res.ResourcesCompat
import org.example.test.R

/**
 * Full-screen modal launched from the bottom-bar "data" button.
 *
 * Mirrors [TradingModeDialog]'s glass-card shell (same blurred backdrop,
 * translucent bordered card, header row with back/close controls) but opens
 * straight onto a vertical list of data categories - Order Book, OHLCV, Open
 * Interest, Funding Rates - each row showing a small leading icon plus a
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
        // Same glass-panel math as TradingModeDialog, kept in sync so the
        // two modals read as one consistent design language.
        const val CARD_FILL_OPACITY_PERCENT = 0.75f
        val CARD_BASE_COLOR_RGB = Color.parseColor("#1C1C1E")

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

    private val thinFont by lazy { ResourcesCompat.getFont(context, R.font.inter_thin) }

    private lateinit var rootView: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var backButton: TextView
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
            text = "Historical Data"
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

    // ---- Screen 1: vertical list of icon + title/description rows ----

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
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(4); bottomMargin = dp(4)
                    }
                })
            }
        }
        return list
    }

    private fun buildTile(category: Category): View {
        val rippleValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleValue, true)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(rippleValue.resourceId)
            setPadding(dp(10), dp(12), dp(10), dp(12))
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
                marginEnd = dp(12)
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

    // ---- Screens 2-5: bare placeholders, content intentionally left empty ----

    private fun buildEmptyScreen(): View = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(160),
        )
    }
}
