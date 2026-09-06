package org.example.syncora.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
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
import org.example.syncora.R

/**
 * Empty, centered "LogPanel" modal. Purely a dark-mode glassmorphism surface for now -
 * no content, text, icons, or buttons live inside it yet.
 */
class LogPanelDialog(context: Context) : Dialog(context, R.style.TradingModalTheme) {

    private companion object {
        const val CARD_CORNER_RADIUS_DP = 26
        const val GLOW_EXTRA_DP = 22
        const val CARD_HEIGHT_DP = 260
        const val BORDER_WIDTH_DP = 0.5f

        const val BACKDROP_BLUR_PERCENT = 0.85f
        const val MAX_BACKDROP_BLUR_DP = 100

        // Charcoal-black glass base with the faintest cool undertones.
        val GLASS_TOP_TINT = Color.parseColor("#2A2E3E")      // barely-there blue undertone
        val GLASS_BASE_TINT = Color.parseColor("#141519")     // frosted charcoal-black
        val GLASS_BOTTOM_TINT = Color.parseColor("#231B30")   // barely-there purple undertone

        const val GLASS_TOP_ALPHA = 0.55f
        const val GLASS_BASE_ALPHA = 0.72f
        const val GLASS_BOTTOM_ALPHA = 0.60f

        val BORDER_COLOR = Color.parseColor("#40FFFFFF")
        val GLOW_COLOR = Color.parseColor("#331C1A3D")
        val SCRIM_COLOR = Color.parseColor("#8A000000")
    }

    private fun dp(value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density).toInt()

    private fun dpf(value: Number): Float = value.toFloat() * context.resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        applyWindowBlur()
    }

    private fun applyWindowBlur() {
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        win.setGravity(Gravity.CENTER)
        win.setDimAmount(0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager?.isCrossWindowBlurEnabled == true) {
                win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val blurRadiusDp = (MAX_BACKDROP_BLUR_DP * BACKDROP_BLUR_PERCENT).toInt()
                win.attributes = win.attributes.apply { blurBehindRadius = dp(blurRadiusDp) }
            }
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun buildRootView(): View {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            setOnClickListener { dismiss() }
        }

        val cornerRadiusPx = dpf(CARD_CORNER_RADIUS_DP)
        val glowCornerRadiusPx = dpf(CARD_CORNER_RADIUS_DP + GLOW_EXTRA_DP / 2)

        // Ambient glow halo sitting behind the glass card for soft separation from the chart.
        val glow = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = glowCornerRadiusPx
                setColor(GLOW_COLOR)
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP + GLOW_EXTRA_DP),
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20) - dp(GLOW_EXTRA_DP / 2)
                marginEnd = dp(20) - dp(GLOW_EXTRA_DP / 2)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(dpf(18), dpf(18), Shader.TileMode.CLAMP))
            }
        }

        // The frosted glass card itself: a wide, empty, rounded-rectangle surface.
        val card = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    applyAlpha(GLASS_TOP_TINT, GLASS_TOP_ALPHA),
                    applyAlpha(GLASS_BASE_TINT, GLASS_BASE_ALPHA),
                    applyAlpha(GLASS_BOTTOM_TINT, GLASS_BOTTOM_ALPHA),
                ),
            ).apply {
                cornerRadius = cornerRadiusPx
                setStroke(dp(BORDER_WIDTH_DP).coerceAtLeast(1), BORDER_COLOR)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            elevation = dpf(20)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.parseColor("#1A1030")
                outlineSpotShadowColor = Color.parseColor("#1A1030")
            }
            isClickable = true
            setOnClickListener { /* absorb clicks, keep dialog open */ }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_HEIGHT_DP),
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

        root.addView(glow)
        root.addView(card)
        return root
    }
}
