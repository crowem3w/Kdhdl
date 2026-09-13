package org.example.test

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Minimalist, spatially-layered bottom navigation, now a continuous
 * carousel: the 5 destinations sit at a fractional "distance" from the
 * currently centered tab (0 = centered/largest, up to 2 = edge/smallest),
 * and that distance is a single float ([virtualCenter] vs a tab's index)
 * that can be driven either by a tap (animated) or by a horizontal drag
 * (tracks the finger 1:1, then spring-settles on release).
 *
 * Every visual property — frame size, icon size, elevation, lift, label
 * size/color/weight, horizontal offset, and outline/filled icon crossfade —
 * is interpolated continuously from that one distance value, so dragging
 * feels like physically sliding the strip rather than jumping between
 * discrete states.
 */
class BottomNavBar(context: Context) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpF(v: Float) = v * d

    private val palette = AppTheme.of(context)

    private val tabs = listOf(AppTab.HOME, AppTab.TEMPLATES, AppTab.PROJECTS, AppTab.PROFILE, AppTab.SETTINGS)
    private val n = tabs.size

    // Breakpoints at distance 0 (centered), 1 (medium), 2 (edge). Values in
    // between are linearly interpolated so the carousel reads as continuous
    // motion rather than 3 fixed states.
    private val bpFrameDp = floatArrayOf(66f, 48f, 38f)
    private val bpIconDp = floatArrayOf(22f, 18f, 14f)
    private val bpElevationDp = floatArrayOf(14f, 5f, 2f)
    private val bpLiftDp = floatArrayOf(14f, 4f, 0f)
    private val bpLabelSp = floatArrayOf(11f, 9f, 7f)
    private val bpCenterOffsetDp = floatArrayOf(0f, 53f, 92f)
    private val labelColors = intArrayOf(palette.navLabelCenter, palette.navLabelMedium, palette.navLabelEdge)

    private data class Slot(
        val root: LinearLayout,
        val frame: FrameLayout,
        val frameDrawable: GradientDrawable,
        val iconOutline: ImageView,
        val iconFilled: ImageView,
        val label: TextView,
    )

    // One persistent view-set per TAB (not per screen position) — the tab's
    // own views move/resize continuously as its distance from center changes.
    private val slots = tabs.map { buildSlot(it) }

    /** Continuous carousel position: an integer value means a tab is exactly
     * centered; fractional values happen mid-drag or mid-settle-animation. */
    private var virtualCenter = 0f
    private var selectedIndex = 0
    private var frontIndex = -1

    private var settleAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var dragStartCenter = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dragSensitivityPx = dp(70).toFloat() // px of finger travel per one tab step
    private val maxDragSteps = 2.4f

    var onTabSelected: ((AppTab) -> Unit)? = null

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
        setBackgroundColor(palette.navBarBackground)
        clipChildren = false
        clipToPadding = false

        val baseBottomMargin = dp(14)
        tabs.forEachIndexed { idx, tab ->
            val slot = slots[idx]
            addView(
                slot.root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = baseBottomMargin },
            )
            val onTap = { onTabTapped(tab) }
            slot.frame.setOnClickListener { onTap() }
            slot.root.setOnClickListener { onTap() }
        }

        updateBoldStates()
        layoutTabs()
    }

    private fun buildSlot(tab: AppTab): Slot {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val frameDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.navFrameFill)
        }

        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(66))
            background = frameDrawable
            isClickable = true
            isFocusable = true
        }

        // Outline and filled icons are stacked and cross-faded by alpha as
        // the tab's distance from center changes, instead of hard-swapping
        // the drawable resource.
        val iconOutline = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(tab.icon)
            setColorFilter(palette.navIconTint, PorterDuff.Mode.SRC_IN)
        }
        val iconFilled = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(tab.iconSelected)
            setColorFilter(palette.navIconTint, PorterDuff.Mode.SRC_IN)
            alpha = 0f
        }
        frame.addView(iconOutline)
        frame.addView(iconFilled)

        val label = TextView(context).apply {
            text = tab.label
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(9) }
        }

        root.addView(frame)
        root.addView(label)
        return Slot(root, frame, frameDrawable, iconOutline, iconFilled, label)
    }

    // --- Math helpers -------------------------------------------------

    /** Shortest signed distance from `center` to `value` around the n-tab circle,
     * e.g. for n=5 the result is always in (-2.5, 2.5]. */
    private fun wrappedDistance(value: Float, center: Float): Float {
        var diff = (value - center) % n
        if (diff > n / 2f) diff -= n
        if (diff <= -n / 2f) diff += n
        return diff
    }

    private fun floorMod(a: Int, m: Int): Int = ((a % m) + m) % m

    private fun lerpBp(bp: FloatArray, x: Float): Float {
        val cx = x.coerceIn(0f, 2f)
        val i = cx.toInt().coerceAtMost(1)
        val frac = cx - i
        return bp[i] + (bp[i + 1] - bp[i]) * frac
    }

    /** Like [lerpBp] but keeps extrapolating past distance 2 (same slope as the
     * 1→2 segment) so a rubber-band overdrag still slides smoothly. */
    private fun lerpBpExtrapolated(bp: FloatArray, x: Float): Float {
        if (x <= 2f) return lerpBp(bp, x)
        val slope = bp[2] - bp[1]
        return bp[2] + slope * (x - 2f)
    }

    private fun lerpColor(colors: IntArray, x: Float): Int {
        val cx = x.coerceIn(0f, 2f)
        val i = cx.toInt().coerceAtMost(1)
        val frac = cx - i
        return ColorUtils.blendARGB(colors[i], colors[i + 1], frac)
    }

    // --- Layout ---------------------------------------------------------

    /** Positions/sizes/colors every slot based on its continuous distance
     * from [virtualCenter]. Called on every drag move and every animation frame. */
    private fun layoutTabs() {
        var nearestIdx = 0
        var nearestAbs = Float.MAX_VALUE

        tabs.indices.forEach { idx ->
            val slot = slots[idx]
            val dist = wrappedDistance(idx.toFloat(), virtualCenter)
            val absD = abs(dist)
            if (absD < nearestAbs) {
                nearestAbs = absD
                nearestIdx = idx
            }

            val frameSizeDp = lerpBp(bpFrameDp, absD)
            val iconSizeDp = lerpBp(bpIconDp, absD)
            val elevationDp = lerpBp(bpElevationDp, absD)
            val liftDp = lerpBp(bpLiftDp, absD)
            val labelSp = lerpBp(bpLabelSp, absD)
            val labelColor = lerpColor(labelColors, absD)
            val offsetDp = lerpBpExtrapolated(bpCenterOffsetDp, absD.coerceAtMost(maxDragSteps))
            val signedOffsetPx = dpF(offsetDp) * dist.sign

            val frameSizePx = dp(frameSizeDp.roundToInt())
            (slot.frame.layoutParams as LinearLayout.LayoutParams).apply {
                if (width != frameSizePx || height != frameSizePx) {
                    width = frameSizePx
                    height = frameSizePx
                    slot.frame.layoutParams = this
                }
            }
            slot.frameDrawable.cornerRadius = frameSizeDp * 0.32f * d

            val iconSizePx = dp(iconSizeDp.roundToInt())
            listOf(slot.iconOutline, slot.iconFilled).forEach { icon ->
                val lp = icon.layoutParams as FrameLayout.LayoutParams
                if (lp.width != iconSizePx || lp.height != iconSizePx) {
                    lp.width = iconSizePx
                    lp.height = iconSizePx
                    icon.layoutParams = lp
                }
            }

            slot.root.translationX = signedOffsetPx
            slot.root.translationY = -dpF(liftDp)
            slot.frame.elevation = dpF(elevationDp)
            slot.label.textSize = labelSp
            slot.label.setTextColor(labelColor)

            val filledAlpha = (1f - absD).coerceIn(0f, 1f)
            slot.iconFilled.alpha = filledAlpha
            slot.iconOutline.alpha = 1f - filledAlpha
        }

        if (nearestIdx != frontIndex) {
            slots[nearestIdx].root.bringToFront()
            frontIndex = nearestIdx
        }
    }

    private fun updateBoldStates() {
        slots.forEachIndexed { idx, slot ->
            val bold = idx == selectedIndex
            slot.label.setTypeface(slot.label.typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // --- Selection / animation -------------------------------------------

    private fun animateVirtualCenterTo(target: Float, onEnd: () -> Unit) {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(virtualCenter, target).apply {
            duration = 380
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                virtualCenter = it.animatedValue as Float
                layoutTabs()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    // The nav bar's own settle animation is purely cosmetic — the page switch
    // fires immediately so the app doesn't feel like it's waiting on it.
    private fun onTabTapped(tab: AppTab) {
        if (isDragging) return
        val idx = tabs.indexOf(tab)
        if (idx == selectedIndex) {
            onTabSelected?.invoke(tab)
            return
        }
        val delta = wrappedDistance(idx.toFloat(), selectedIndex.toFloat())
        onTabSelected?.invoke(tab)
        animateVirtualCenterTo(virtualCenter + delta) {
            selectedIndex = idx
            virtualCenter = idx.toFloat()
            updateBoldStates()
            layoutTabs()
        }
    }

    /** Public API kept for callers that select a tab programmatically. */
    fun selectTab(tab: AppTab) {
        onTabTapped(tab)
    }

    // --- Touch handling: tap-to-select vs horizontal drag -----------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragStartCenter = virtualCenter
                isDragging = false
                settleAnimator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isDragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val dx = ev.x - downX
                    if (abs(dx) > touchSlop) isDragging = true else return true
                }
                val dx = ev.x - downX
                val deltaSteps = (-dx / dragSensitivityPx).coerceIn(-maxDragSteps, maxDragSteps)
                virtualCenter = dragStartCenter + deltaSteps
                layoutTabs()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    val nearest = virtualCenter.roundToInt()
                    val newIndex = floorMod(nearest, n)
                    if (newIndex != selectedIndex) onTabSelected?.invoke(tabs[newIndex])
                    animateVirtualCenterTo(nearest.toFloat()) {
                        selectedIndex = newIndex
                        virtualCenter = selectedIndex.toFloat()
                        updateBoldStates()
                        layoutTabs()
                    }
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(ev)
    }
}
