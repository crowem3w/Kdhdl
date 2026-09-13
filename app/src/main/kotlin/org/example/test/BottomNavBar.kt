package org.example.test

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating frosted-glass navigation dock.
 *
 * A translucent pill (the "glass" dock) floats with a margin above the
 * screen's bottom edge and holds 5 evenly-spaced destinations, each a
 * thin-line icon + small label. The active destination doesn't just
 * recolor in place - a separate rounded-square button rises above it,
 * slightly overlapping the pill's top edge, with a brighter fill, a
 * stronger shadow, and the destination's filled icon. Its own row icon is
 * hidden (alpha 0) while it's active, since the raised button shows it
 * instead; its label stays in the row, promoted to a bold, high-contrast
 * color.
 *
 * Note on "blur": Android has no public API for a live backdrop blur of
 * whatever sits behind a view within the same window (real-time
 * background blur needs a separate window/surface, e.g. dialogs). The
 * frosted-glass read here is approximated with translucency + a subtle
 * edge highlight rather than an actual Gaussian blur of the content
 * underneath. A real blur is possible with a small blur library if that
 * matters more than the translucency approximation.
 */
class BottomNavBar(context: Context) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpF(v: Int) = v * d

    private val palette = AppTheme.of(context)

    // Requested destination order: Settings, Home, Templates, Projects, Profile.
    private val tabs = listOf(AppTab.SETTINGS, AppTab.HOME, AppTab.TEMPLATES, AppTab.PROJECTS, AppTab.PROFILE)
    private val n = tabs.size

    companion object {
        private const val PILL_HEIGHT_DP = 72
        private const val PILL_MARGIN_H_DP = 24
        private const val PILL_MARGIN_BOTTOM_DP = 20
        private const val BUTTON_SIZE_DP = 60
        private const val BUTTON_POKE_ABOVE_DP = 36 // how far the button rises above the pill's top edge
        private const val ROOT_HEIGHT_DP = 140
    }

    private data class Slot(
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    )

    private val slots = tabs.map { buildSlot(it) }

    private lateinit var pill: LinearLayout
    private lateinit var activeButton: FrameLayout
    private lateinit var activeIcon: ImageView

    private var selectedIndex = tabs.indexOf(AppTab.TEMPLATES).coerceAtLeast(0)
    private var buttonAnimator: ValueAnimator? = null

    var onTabSelected: ((AppTab) -> Unit)? = null

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROOT_HEIGHT_DP))
        clipChildren = false
        clipToPadding = false

        pill = buildPill()
        addView(
            pill,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PILL_HEIGHT_DP)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(PILL_MARGIN_H_DP)
                rightMargin = dp(PILL_MARGIN_H_DP)
                bottomMargin = dp(PILL_MARGIN_BOTTOM_DP)
            },
        )

        activeButton = buildActiveButton()
        addView(
            activeButton,
            FrameLayout.LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(PILL_MARGIN_BOTTOM_DP + PILL_HEIGHT_DP - (BUTTON_SIZE_DP - BUTTON_POKE_ABOVE_DP))
            },
        )

        updateSlotVisualStates()

        // Slot x-positions aren't known until after the first layout pass, so the button's
        // initial placement is deferred to it.
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (pill.width > 0) {
                    positionActiveButton(animate = false)
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }

    // --- Building blocks --------------------------------------------------

    private fun buildPill(): LinearLayout {
        val glassDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(PILL_HEIGHT_DP) / 2f
            setColor(palette.navGlassFill)
            setStroke(dp(1), palette.navGlassStroke)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassDrawable
            clipChildren = false
            clipToPadding = false
            // Soft, elevated shadow beneath the whole dock - the closest stand-in for a
            // neumorphic "raised" look Android's elevation system offers.
            elevation = dpF(10)
            outlineAmbientShadowColor = palette.navGlassShadow
            outlineSpotShadowColor = palette.navGlassShadow
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(PILL_HEIGHT_DP) / 2f)
                }
            }
            slots.forEach { slot ->
                addView(slot.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }
    }

    private fun buildSlot(tab: AppTab): Slot {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(tab.icon)
            setColorFilter(palette.navInactiveTint)
        }
        val label = TextView(context).apply {
            text = tab.label
            gravity = Gravity.CENTER
            textSize = 10.5f
            setTextColor(palette.navInactiveTint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        root.addView(icon)
        root.addView(label)
        root.setOnClickListener { onTabTapped(tabs.indexOf(tab)) }
        return Slot(root, icon, label)
    }

    private fun buildActiveButton(): FrameLayout {
        val activeDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(20)
            setColor(palette.navActiveButtonFill)
        }
        activeIcon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(tabs[selectedIndex].iconSelected)
            setColorFilter(palette.navActiveIconTint)
        }
        return FrameLayout(context).apply {
            background = activeDrawable
            isClickable = true
            isFocusable = true
            elevation = dpF(18)
            outlineAmbientShadowColor = palette.navActiveButtonShadow
            outlineSpotShadowColor = palette.navActiveButtonShadow
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpF(20))
                }
            }
            addView(activeIcon)
            setOnClickListener { onTabSelected?.invoke(tabs[selectedIndex]) }
        }
    }

    // --- Selection ----------------------------------------------------------

    private fun onTabTapped(index: Int) {
        if (index == selectedIndex) {
            onTabSelected?.invoke(tabs[index])
            return
        }
        selectedIndex = index
        updateSlotVisualStates()
        positionActiveButton(animate = true)
        onTabSelected?.invoke(tabs[index])
    }

    /** Public API kept for callers that select a tab programmatically. */
    fun selectTab(tab: AppTab) {
        onTabTapped(tabs.indexOf(tab))
    }

    private fun updateSlotVisualStates() {
        slots.forEachIndexed { idx, slot ->
            val active = idx == selectedIndex
            // The active slot's own row icon is hidden - its icon lives in the raised
            // button above instead - but its label is promoted to a bold, high-contrast
            // color so the destination is still legible at a glance in the row.
            slot.icon.alpha = if (active) 0f else 1f
            slot.label.setTextColor(if (active) palette.navActiveLabelTint else palette.navInactiveTint)
            slot.label.setTypeface(slot.label.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        activeIcon.setImageResource(tabs[selectedIndex].iconSelected)
    }

    private fun positionActiveButton(animate: Boolean) {
        if (pill.width == 0) return
        val slotWidth = pill.width.toFloat() / n
        val targetCenterX = pill.left + slotWidth * (selectedIndex + 0.5f)
        val targetX = targetCenterX - activeButton.width / 2f

        buttonAnimator?.cancel()
        if (!animate) {
            activeButton.translationX = targetX
            return
        }
        buttonAnimator = ValueAnimator.ofFloat(activeButton.translationX, targetX).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { activeButton.translationX = it.animatedValue as Float }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { positionActiveButton(animate = false) }
    }
}
