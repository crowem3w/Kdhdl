package org.example.test

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A position in the navigation cluster (left to right). Each position has a
 * fixed geometry / weight (small -> medium -> large -> medium -> small); only
 * the *content* bound to a position changes when the selected tab changes,
 * which is what makes the currently active destination always render in the
 * center, largest, most elevated slot.
 */
private enum class NavRole(
    val frameSizeDp: Int,
    val iconSizeDp: Int,
    val elevationDp: Int,
    val liftDp: Int,
    val labelSizeSp: Float,
    val fill: Int,
    val stroke: Int,
    val iconTint: Int,
    val labelColor: Int,
    val bold: Boolean,
) {
    EDGE(
        frameSizeDp = 42, iconSizeDp = 16, elevationDp = 0, liftDp = 0, labelSizeSp = 8f,
        fill = 0xFF161616.toInt(), stroke = 0xFF262626.toInt(),
        iconTint = 0xFF6E6A66.toInt(), labelColor = 0xFF6E6A66.toInt(), bold = false,
    ),
    MEDIUM(
        frameSizeDp = 54, iconSizeDp = 20, elevationDp = 3, liftDp = 5, labelSizeSp = 10f,
        fill = 0xFF1C1C1C.toInt(), stroke = 0xFF323232.toInt(),
        iconTint = 0xFFAFA9A3.toInt(), labelColor = 0xFF9C9691.toInt(), bold = false,
    ),
    CENTER(
        frameSizeDp = 74, iconSizeDp = 26, elevationDp = 14, liftDp = 16, labelSizeSp = 12f,
        fill = 0xFF211F27.toInt(), stroke = 0x66D0BCFF, // accent, low-alpha, thin
        iconTint = 0xFFD0BCFF.toInt(), labelColor = 0xFFF5F5F4.toInt(), bold = true,
    ),
}

/**
 * Minimalist, spatially-layered bottom navigation. Five destinations are
 * arranged in a single row with a deliberate scale progression
 * (small -> medium -> large -> medium -> small); the active destination is
 * always recentered into the large slot, visually raised above the rest
 * through elevation, translation and stronger contrast.
 */
class BottomNavBar(context: Context) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    // Canonical cycle of destinations. The selected tab is rotated into the
    // center; the others keep their relative order around it.
    private val canonicalOrder = listOf(
        AppTab.HOME, AppTab.TEMPLATES, AppTab.PROJECTS, AppTab.PROFILE, AppTab.SETTINGS,
    )
    private val roles = listOf(NavRole.EDGE, NavRole.MEDIUM, NavRole.CENTER, NavRole.MEDIUM, NavRole.EDGE)

    private var selected: AppTab = AppTab.HOME
    var onTabSelected: ((AppTab) -> Unit)? = null

    private data class Slot(val root: LinearLayout, val frame: FrameLayout, val icon: ImageView, val label: TextView)

    // Slots are indexed 0..4 left-to-right by POSITION (not by tab), matching `roles`.
    private val slots = mutableListOf<Slot>()

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
        setBackgroundColor(0xFF141414.toInt())
        clipChildren = false
        clipToPadding = false

        // Build all 5 slots first (order doesn't matter here); the CENTER
        // slot is added to the view tree *last* further below so it draws
        // above its two overlapping neighbours, regardless of its X position.
        roles.forEach { role -> slots.add(buildSlot(role)) }

        val baseBottomMargin = dp(14)
        val addOrder = listOf(0, 1, 3, 4, 2) // center (index 2) added last -> drawn on top
        addOrder.forEach { i ->
            val role = roles[i]
            addView(
                slots[i].root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = baseBottomMargin },
            )
            slots[i].root.translationY = -dp(role.liftDp).toFloat()
            slots[i].frame.elevation = dp(role.elevationDp).toFloat()
        }

        applyHorizontalOffsets()
        refresh()
    }

    /** Positions each slot horizontally so the row reads small->medium->large->medium->small,
     * with adjacent frames overlapping slightly for a stacked, dimensional feel. */
    private fun applyHorizontalOffsets() {
        val sizesDp = roles.map { it.frameSizeDp }
        val overlapDp = 10
        val centers = DoubleArray(5)
        centers[0] = 0.0
        for (i in 1 until 5) {
            centers[i] = centers[i - 1] + sizesDp[i - 1] / 2.0 + sizesDp[i] / 2.0 - overlapDp
        }
        val leftEdge = centers[0] - sizesDp[0] / 2.0
        val rightEdge = centers[4] + sizesDp[4] / 2.0
        val mid = (leftEdge + rightEdge) / 2.0
        for (i in 0 until 5) {
            slots[i].root.translationX = ((centers[i] - mid) * d).toFloat()
        }
    }

    private fun buildSlot(role: NavRole): Slot {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val frameDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = role.frameSizeDp * 0.32f * d
            setColor(role.fill)
            setStroke(dp(1), role.stroke)
        }

        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(role.frameSizeDp), dp(role.frameSizeDp))
            background = frameDrawable
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(role.iconSizeDp), dp(role.iconSizeDp), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(icon)

        val label = TextView(context).apply {
            textSize = role.labelSizeSp
            setTextColor(role.labelColor)
            setTypeface(typeface, if (role.bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }

        root.addView(frame)
        root.addView(label)
        return Slot(root, frame, icon, label)
    }

    private fun computeDisplayOrder(): List<AppTab> {
        val n = canonicalOrder.size
        val selIdx = canonicalOrder.indexOf(selected)
        return (0 until n).map { canonicalOrder[(selIdx - 2 + it + n) % n] }
    }

    private fun refresh() {
        val order = computeDisplayOrder()
        order.forEachIndexed { position, tab ->
            val role = roles[position]
            val slot = slots[position]
            val isCenter = role == NavRole.CENTER
            slot.icon.setImageResource(if (isCenter) tab.iconSelected else tab.icon)
            slot.icon.setColorFilter(role.iconTint, PorterDuff.Mode.SRC_IN)
            slot.label.text = tab.label
            slot.frame.setOnClickListener { selectTab(tab) }
            slot.root.setOnClickListener { selectTab(tab) }
        }
    }

    fun selectTab(tab: AppTab) {
        if (tab == selected) {
            onTabSelected?.invoke(tab)
            return
        }
        selected = tab
        refresh()
        onTabSelected?.invoke(tab)
    }
}
