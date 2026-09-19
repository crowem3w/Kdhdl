package org.example.test

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import kotlin.math.roundToInt

/**
 * The Elements sidebar: a Material 3 *expandable navigation rail* docked to the left edge of the
 * sketch screen, replacing the old "Elements" bottom-nav tab.
 *
 * Layout (top to bottom):
 *  - Header: the Elements icon + "Elements" label.
 *  - The component categories ("All", Buttons, Layout, ...), each an M3 navigation item with a
 *    pill-shaped, green-tonal active indicator and a state-layer ripple. Geometry carries its
 *    existing sub-category accordion inline while it's selected and the rail is expanded.
 *  - Footer: expand / collapse toggle. Collapsed = icon-only rail, expanded = icon + label.
 *
 * Tapping a category only reports [onCategoryClick]; the host decides what to open (the category's
 * content shows in the Elements bottom sheet) and reports the selection back with
 * [setSelectedCategory].
 *
 * The edge that faces the canvas is neumorphic: the surface reads as *raised* from the canvas,
 * lit from the top-left - a soft light halo on the lit side, a soft dark shadow on the far side,
 * a thin bright rim and a slightly darker lip on the surface itself so the relief still reads over
 * the app's black workspace, where a dark shadow alone would vanish. The shadows are drawn into
 * this view's own padding (the "bleed"), so nothing depends on parents leaving room to overdraw.
 *
 * This view is deliberately not clickable itself: only the surface eats touches, so the transparent
 * shadow bleed lets touches through to the canvas.
 */
class ElementsSidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** A row was tapped. [id] is [ALL_CATEGORY]'s id or one of [COMPONENT_CATEGORIES]' ids. */
    var onCategoryClick: ((String) -> Unit)? = null

    /**
     * How many px of screen width the sidebar's surface currently occupies from the left edge
     * (0 when hidden). Fires every animation frame so the host can keep neighbouring UI clear of it.
     */
    var onOccupiedWidthChanged: ((Int) -> Unit)? = null

    /** The rail was expanded/collapsed by the user (not for programmatic restores). */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    var isExpanded: Boolean = false
        private set

    /** The user's show/hide preference (top-bar toggle). See also [setSuppressed]. */
    var isShown: Boolean = true
        private set

    /** Current px width of the sidebar surface at full slide-in (animates while expanding). */
    val surfaceWidthPx: Int get() = currentWidthPx.roundToInt()

    // ---- Metrics ---------------------------------------------------------------------------
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private fun dpf(v: Float) = v * d

    private val collapsedWidth = dpf(64f)
    private val expandedWidth = dpf(228f)
    private val sidePad = dp(8)
    private val cornerRadius = dpf(24f)
    private val bleed = dp(20)
    private val itemHeight = dp(48)

    // ---- State -----------------------------------------------------------------------------
    private var currentWidthPx = collapsedWidth
    private var slideProgress = 1f          // 0 = off-screen left, 1 = docked
    private var suppressed = false
    private var selectedId: String? = null
    private var dimmedIds: Set<String>? = null    // ids still matching the sheet's search (null = no query)
    private var widthAnimator: ValueAnimator? = null
    private var slideAnimator: ValueAnimator? = null

    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)   // M3 "emphasized" easing

    // ---- Views -----------------------------------------------------------------------------
    private val surface = FrameLayout(context)
    private val titleView: TextView
    private val labelViews = mutableListOf<TextView>()
    private val rows = linkedMapOf<String, Row>()
    private val geometryAccordion: View
    private val geometryAccordionReset: () -> Unit
    private val toggleIcon: ImageView
    private val toggleRow: LinearLayout

    private class Row(
        val id: String,
        val view: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val indicator: GradientDrawable,
    ) {
        var selected = false
        var colorAnimator: ValueAnimator? = null
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        // Left is 0: the surface is flush against the screen edge. The other three sides hold the
        // neumorphic shadows.
        setPadding(0, bleed, bleed, bleed)

        // -- Surface: raised, light, with a soft lit-from-top-left gradient.
        val radii = floatArrayOf(0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f)
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(SURFACE_LIT, SURFACE_SHADED),
        ).apply { cornerRadii = radii }
        val rim = GradientDrawable().apply {
            // Bright catch-light around the lit edges; the half of the stroke that falls outside
            // the surface simply merges into the halo.
            setColor(Color.TRANSPARENT)
            setStroke(dp(1).coerceAtLeast(1), RIM_LIGHT)
            cornerRadii = radii
        }
        surface.background = LayerDrawable(arrayOf<Drawable>(base, rim))
        surface.isClickable = true          // eats taps so they don't reach the canvas behind
        surface.isFocusable = false
        surface.layoutParams = FrameLayout.LayoutParams(collapsedWidth.roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        addView(surface)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePad, 0, sidePad, 0)
        }
        surface.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Slightly darker "lip" hugging the canvas-facing edge - the surface curving away.
        val lip = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, LIP_SHADE),
            ).apply { cornerRadii = floatArrayOf(0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f) }
            isClickable = false
        }
        surface.addView(lip, FrameLayout.LayoutParams(dp(10), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        // -- Header: icon + "Elements".
        val headerIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_tool_elements)
            setColorFilter(PANEL_ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        titleView = TextView(context).apply {
            text = "Elements"
            setTextColor(PANEL_PRIMARY_TEXT)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        labelViews.add(titleView)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
            isFocusable = true
            contentDescription = "Elements"
            addView(headerIcon)
            addView(titleView)
        }
        column.addView(header)
        column.addView(buildGroove())

        // -- Category list (scrolls: 13 rows don't fit on a phone).
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        column.addView(scroll)

        val (accordion, accordionReset) = buildGeometrySidebarTree(context)
        geometryAccordion = accordion.apply { visibility = View.GONE }
        geometryAccordionReset = accordionReset

        for (cat in listOf(ALL_CATEGORY) + COMPONENT_CATEGORIES) {
            val row = buildRow(cat.id, cat.label, cat.iconRes)
            rows[cat.id] = row
            list.addView(row.view)
            if (cat.id == "shapes") list.addView(geometryAccordion)
        }

        // -- Footer: expand / collapse.
        column.addView(buildGroove())
        toggleIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(ICON_IDLE)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val toggleLabel = buildLabel("Collapse").also { labelViews.add(it) }
        toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            background = ripple(GradientDrawable().apply { cornerRadius = dpf(24f); setColor(Color.TRANSPARENT) })
            isClickable = true
            isFocusable = true
            addView(toggleIcon)
            addView(toggleLabel)
            setOnClickListener { setExpanded(!isExpanded, animate = true, fromUser = true) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        column.addView(toggleRow)

        applyWidth(collapsedWidth)
        updateToggle()
    }

    // ---- Row construction ------------------------------------------------------------------

    private fun buildLabel(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ICON_IDLE)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true
        // Width collapses to 0 with the rail; never wrap or ellipsize mid-animation.
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
    }

    private fun ripple(indicator: GradientDrawable): RippleDrawable {
        val mask = GradientDrawable().apply { cornerRadius = dpf(24f); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(STATE_LAYER), indicator, mask)
    }

    private fun buildRow(id: String, label: String, iconRes: Int): Row {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(ICON_IDLE)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val labelView = buildLabel(label).also { labelViews.add(it) }
        val indicator = GradientDrawable().apply {
            cornerRadius = dpf(24f)
            setColor(Color.TRANSPARENT)
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 12dp start puts the 24dp icon's centre exactly on the collapsed rail's centre line
            // (8dp side padding + 12dp + 12dp = 32dp = half of 64dp), so icons don't shift when
            // the rail expands - only the labels appear.
            setPadding(dp(12), 0, 0, 0)
            background = ripple(indicator)
            isClickable = true
            isFocusable = true
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight).apply {
                bottomMargin = dp(2)
            }
            addView(icon)
            addView(labelView)
        }
        val row = Row(id, view, icon, labelView, indicator)
        view.setOnClickListener { onCategoryClick?.invoke(id) }
        return row
    }

    /** A 2dp "groove": a dark line over a light line - the neumorphic version of a divider. */
    private fun buildGroove() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        }
        addView(View(context).apply {
            setBackgroundColor(GROOVE_DARK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        addView(View(context).apply {
            setBackgroundColor(GROOVE_LIGHT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
    }

    // ---- Public API ------------------------------------------------------------------------

    /** Highlights [id] as the active category (null clears the selection). Does not fire [onCategoryClick]. */
    fun setSelectedCategory(id: String?) {
        if (id == selectedId) return
        val previous = selectedId
        selectedId = id
        previous?.let { rows[it] }?.let { setRowSelected(it, false) }
        id?.let { rows[it] }?.let { setRowSelected(it, true) }
        if (previous == "shapes" && id != "shapes") geometryAccordionReset()
        updateAccordionVisibility()
    }

    /**
     * Dims rows whose category no longer matches the sheet's search query ([matching] = ids that
     * still match; null = no active query, everything full strength).
     */
    fun setSearchMatches(matching: Set<String>?) {
        dimmedIds = matching
        for ((id, row) in rows) {
            row.view.alpha = if (matching == null || id == ALL_CATEGORY.id || id in matching) 1f else 0.38f
        }
    }

    fun setExpanded(expanded: Boolean, animate: Boolean = true, fromUser: Boolean = false) {
        if (expanded == isExpanded && widthAnimator == null) {
            applyWidth(if (expanded) expandedWidth else collapsedWidth)
            return
        }
        isExpanded = expanded
        val target = if (expanded) expandedWidth else collapsedWidth
        widthAnimator?.cancel()
        widthAnimator = null
        if (animate && isLaidOut) {
            widthAnimator = ValueAnimator.ofFloat(currentWidthPx, target).apply {
                duration = 260L
                interpolator = emphasized
                addUpdateListener { applyWidth(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (widthAnimator === animation) widthAnimator = null
                    }
                })
                start()
            }
        } else {
            applyWidth(target)
        }
        updateToggle()
        updateAccordionVisibility()
        if (fromUser) onExpandedChanged?.invoke(expanded)
    }

    /** The user's show/hide preference (top-bar toggle). */
    fun setShown(shown: Boolean, animate: Boolean = true) {
        isShown = shown
        applyEffectiveVisibility(animate)
    }

    /** Temporarily hides the sidebar without touching the user's preference (e.g. zoomed-out overview). */
    fun setSuppressed(value: Boolean, animate: Boolean = true) {
        suppressed = value
        applyEffectiveVisibility(animate)
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun applyEffectiveVisibility(animate: Boolean) {
        val target = if (isShown && !suppressed) 1f else 0f
        slideAnimator?.cancel()
        slideAnimator = null
        if (target == 1f) visibility = View.VISIBLE
        if (animate && isLaidOut) {
            slideAnimator = ValueAnimator.ofFloat(slideProgress, target).apply {
                duration = 240L
                interpolator = emphasized
                addUpdateListener { applySlide(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!cancelled && target == 0f) visibility = View.GONE
                    }
                })
                start()
            }
        } else {
            applySlide(target)
            visibility = if (target == 0f) View.GONE else View.VISIBLE
        }
    }

    private fun applySlide(progress: Float) {
        slideProgress = progress
        // Slides out past the left edge by the surface width (the shadow bleed is on the right,
        // so it leaves with it).
        translationX = -(1f - progress) * (currentWidthPx + bleed)
        alpha = progress.coerceIn(0f, 1f)
        reportOccupiedWidth()
    }

    private fun reportOccupiedWidth() {
        onOccupiedWidthChanged?.invoke((currentWidthPx * slideProgress).roundToInt())
    }

    private fun applyWidth(widthPx: Float) {
        currentWidthPx = widthPx
        val lp = surface.layoutParams
        lp.width = widthPx.roundToInt()
        surface.layoutParams = lp

        // Labels fade in over the second half of the expansion (and out over the first half of
        // the collapse) so text never squashes against the shrinking pill.
        val f = ((widthPx - collapsedWidth) / (expandedWidth - collapsedWidth)).coerceIn(0f, 1f)
        val labelAlpha = ((f - 0.5f) / 0.5f).coerceIn(0f, 1f)
        for (l in labelViews) l.alpha = labelAlpha
        toggleIcon.rotation = 180f * (1f - f)
        reportOccupiedWidth()
        invalidate()
    }

    private fun updateToggle() {
        val label = if (isExpanded) "Collapse sidebar" else "Expand sidebar"
        toggleRow.contentDescription = label
        for (row in rows.values) {
            TooltipCompat.setTooltipText(row.view, if (isExpanded) null else row.label.text)
        }
        TooltipCompat.setTooltipText(toggleRow, if (isExpanded) null else label)
    }

    private fun updateAccordionVisibility() {
        val show = isExpanded && selectedId == "shapes"
        geometryAccordion.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setRowSelected(row: Row, selected: Boolean) {
        if (row.selected == selected) return
        row.selected = selected
        val toBg = if (selected) INDICATOR_ACTIVE else Color.TRANSPARENT
        val toFg = if (selected) ON_INDICATOR_ACTIVE else ICON_IDLE
        row.colorAnimator?.cancel()
        val fromBg = (row.indicator.color?.defaultColor) ?: Color.TRANSPARENT
        val fromFg = if (selected) ICON_IDLE else ON_INDICATOR_ACTIVE
        val eval = ArgbEvaluator()
        row.colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            addUpdateListener {
                val t = it.animatedFraction
                row.indicator.setColor(eval.evaluate(t, fromBg, toBg) as Int)
                val fg = eval.evaluate(t, fromFg, toFg) as Int
                row.icon.setColorFilter(fg)
                row.label.setTextColor(fg)
            }
            start()
        }
    }

    // ---- Neumorphic shadows ----------------------------------------------------------------

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPath = Path()
    private val shadowRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (surface.width == 0 || surface.height == 0) return
        val l = surface.left.toFloat()
        val t = surface.top.toFloat()
        val r = surface.right.toFloat()
        val b = surface.bottom.toFloat()
        val spread = dpf(13f)
        // Light source top-left: a soft light halo up/left, a soft dark shadow down/right.
        drawSoftShadow(canvas, l, t, r, b, spread, -dpf(4f), -dpf(4f), HALO_LIGHT, 0.75f)
        drawSoftShadow(canvas, l, t, r, b, spread, dpf(5f), dpf(5f), SHADOW_DARK, 0.42f)
    }

    /**
     * Approximates a blurred drop shadow with stacked, slightly-growing translucent rounded rects.
     * That stays hardware-accelerated and cheap enough to redraw on every frame of the width
     * animation (a real blur would need a software layer or a re-rendered bitmap per frame).
     */
    private fun drawSoftShadow(
        canvas: Canvas,
        l: Float, t: Float, r: Float, b: Float,
        spread: Float, dx: Float, dy: Float,
        color: Int, peakAlpha: Float,
    ) {
        val steps = 14
        // Per-layer alpha such that `steps` stacked layers add up to peakAlpha at the core.
        val perLayer = 1f - Math.pow((1f - peakAlpha).toDouble(), 1.0 / steps).toFloat()
        shadowPaint.color = (color and 0x00FFFFFF) or ((perLayer * 255f).roundToInt().coerceIn(1, 255) shl 24)
        for (i in 0 until steps) {
            val grow = spread * (i + 1) / steps
            shadowRect.set(l + dx - grow, t + dy - grow, r + dx + grow, b + dy + grow)
            val rr = cornerRadius + grow
            shadowPath.rewind()
            // Left corners stay square: they sit against the screen edge.
            shadowPath.addRoundRect(shadowRect, floatArrayOf(0f, 0f, rr, rr, rr, rr, 0f, 0f), Path.Direction.CW)
            canvas.drawPath(shadowPath, shadowPaint)
        }
    }

    private companion object {
        // Material 3 tonal palette derived from the app's hunter-green accent (PANEL_ACCENT).
        val INDICATOR_ACTIVE = Color.parseColor("#D3E6D5")        // secondary container
        val ON_INDICATOR_ACTIVE = Color.parseColor("#12331A")     // on secondary container
        val ICON_IDLE = Color.parseColor("#4B5563")               // on surface variant
        val STATE_LAYER = Color.parseColor("#1F355E3B")           // primary @ 12%

        // Neumorphic surface: cool light grey, lit from the top-left.
        val SURFACE_LIT = Color.parseColor("#F6F7FA")
        val SURFACE_SHADED = Color.parseColor("#E7EAF0")
        val RIM_LIGHT = Color.parseColor("#F2FFFFFF")
        val LIP_SHADE = Color.parseColor("#1A1F2937")
        val HALO_LIGHT = Color.parseColor("#FFFFFF")
        val SHADOW_DARK = Color.parseColor("#000000")
        val GROOVE_DARK = Color.parseColor("#D3D7DE")
        val GROOVE_LIGHT = Color.parseColor("#FFFFFF")
    }
}
