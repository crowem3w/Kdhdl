package org.example.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Material-blue accent for the selected Design/Prototype segment - the same accent as
// bg_tab_selected.xml and the Components panel's own Design/Prototype toggle (see
// PANEL_MODE_SELECTED in ComponentsPanel.kt), so "selected" reads consistently across panels.
// Also used for customButton's icon tint when ButtonObjectMode.CUSTOM is active, so all three
// mutually-exclusive selections in this row share one "selected" color language.
private val MODE_SELECTED_BLUE = Color.parseColor("#3D7EFF")
private val MODE_SELECTED_BLUE_PRESSED = Color.parseColor("#2E68DB")

// Unselected segment stays transparent, letting the shared white frame show through directly -
// only two frame colors exist on this toggle now (white frame, material-blue selected segment),
// no separate unselected fill.
private val MODE_UNSELECTED_FRAME = Color.TRANSPARENT
// A faint tap-feedback tint for the unselected segment (not a "frame" color of its own - just a
// momentary press cue), since it has no fill to darken the way the selected segment's blue does.
private val MODE_UNSELECTED_FRAME_PRESSED = Color.parseColor("#14000000")

private val MODE_TEXT_SELECTED = Color.WHITE
private val MODE_TEXT_UNSELECTED = Color.parseColor("#6B7280")

/** Handle back to a built Design/Prototype/customButton row, for syncing and positioning it. */
data class ButtonObjectPanelViews(
    val root: View,
    // Re-applies the given mode's selected/unselected styling without firing onModeChanged - used
    // when the panel is (re)opened on a Button part, to reflect that part's own persisted
    // objectPanelMode instead of leaving whatever was selected for the previously-edited part.
    val setActiveMode: (ButtonObjectMode) -> Unit,
    // customButton itself, so SketchActivity can locate its on-screen position/width to line up
    // the localized corner-shadow view it shows above the panel's top edge when this button is
    // selected - see updateButtonObjectPanelCornerState()/positionButtonObjectCornerShadow().
    val customButtonView: View,
)

/**
 * Builds the top row of the Button object panel (buttonObjectContentContainer - see
 * setupButtonObjectPanel()/openButtonObjectPanel() in SketchActivity): the Design/Prototype
 * segmented toggle plus customButton, a frameless icon button to its right. This row is the only
 * thing this panel shows for now; more controls are expected to be added below it later.
 *
 * The toggle's two segments share ONE outer frame: a single white, rounded-square container that
 * carries the drop shadow (via elevation against its own rounded-rect background - same technique
 * as the Components panel's own mode toggle, see modeSharedFrameBg/modeToggleRow in
 * ComponentsPanel.kt). Inside that shared frame, each segment is its own equally-wide (50/50) tap
 * target with no padding/gap against the frame or against each other: the unselected segment is
 * transparent (the white frame shows straight through it), while the selected segment fills its
 * entire half solid Material blue, its outer corners matched to the frame's own 12dp rounding
 * (so the blue reads as a continuous rounded shape with the frame) and its inner corners - at the
 * seam with the other segment - left square, so the two halves butt together with no visible gap
 * or seam. Just two frame colors exist here: the shared white frame, and the selected segment's
 * solid blue.
 *
 * customButton sits OUTSIDE that shared frame, to its right in the same row, and is frameless -
 * no background/frame of its own (same convention as e.g. backButton/closeButton in
 * ComponentsPanel.kt) - so it reads as a separate action from the toggle rather than a third
 * segment of it. The toggle frame takes the row's remaining width (weight=1f) after customButton's
 * own fixed width is reserved, so together the two still claim the row's full width - this row is
 * reserved for just these two things, so nothing here should ever be squeezed to make room for
 * anything else outside it.
 *
 * Selecting customButton is mutually exclusive with Design/Prototype (all three share the single
 * activeMode below), and additionally - see SketchActivity - flips the whole panel's top corners
 * to flat/square and reveals a small drop shadow localized to the edge under customButton.
 */
fun buildButtonObjectPanelContent(
    context: Context,
    initialMode: ButtonObjectMode,
    onModeChanged: (ButtonObjectMode) -> Unit,
    // Placeholder hook for customButton - its actual behavior beyond the selection/corner-state
    // change isn't defined yet.
    onCustomButtonClick: () -> Unit = {},
): ButtonObjectPanelViews {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    class Segment(val frame: FrameLayout, val label: TextView, val bg: GradientDrawable, val mode: ButtonObjectMode)

    val segments = mutableListOf<Segment>()
    var activeMode = initialMode

    // customButton's icon, built up front (before setActive/buildSegment below reference it) so
    // its tint can be driven by the same single activeMode as the two segments.
    val customButtonIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_add_grid)
        setColorFilter(MODE_TEXT_UNSELECTED)
        layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
    }

    fun applyState(seg: Segment, selected: Boolean, pressed: Boolean = false) {
        seg.bg.setColor(
            when {
                selected && pressed -> MODE_SELECTED_BLUE_PRESSED
                selected -> MODE_SELECTED_BLUE
                pressed -> MODE_UNSELECTED_FRAME_PRESSED
                else -> MODE_UNSELECTED_FRAME
            }
        )
        seg.label.setTextColor(if (selected) MODE_TEXT_SELECTED else MODE_TEXT_UNSELECTED)
        seg.label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    fun setActive(mode: ButtonObjectMode, notify: Boolean) {
        activeMode = mode
        segments.forEach { applyState(it, selected = it.mode == mode) }
        customButtonIcon.setColorFilter(if (mode == ButtonObjectMode.CUSTOM) MODE_SELECTED_BLUE else MODE_TEXT_UNSELECTED)
        if (notify) onModeChanged(mode)
    }

    // toggleFrame's own corner radius (12dp) - reused here so the selected segment's outer
    // corners match it exactly, reading as one continuous rounded shape with no gap between the
    // segment's blue fill and the frame's own edge.
    val frameCornerRadiusPx = dp(12).toFloat()

    // isLeft picks which pair of corners is "outer" (against the shared frame's rounded edge) vs
    // "inner" (at the flat center seam against the other segment): left segment is rounded on its
    // left corners/square on its right, right segment is the mirror image - so together the two
    // segments' outer edges continue the frame's own rounding with no gap anywhere.
    fun buildSegment(text: String, mode: ButtonObjectMode, isLeft: Boolean): Segment {
        val label = TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = if (isLeft) {
                floatArrayOf(
                    frameCornerRadiusPx, frameCornerRadiusPx, // top-left
                    0f, 0f,                                   // top-right
                    0f, 0f,                                   // bottom-right
                    frameCornerRadiusPx, frameCornerRadiusPx, // bottom-left
                )
            } else {
                floatArrayOf(
                    0f, 0f,                                   // top-left
                    frameCornerRadiusPx, frameCornerRadiusPx, // top-right
                    frameCornerRadiusPx, frameCornerRadiusPx, // bottom-right
                    0f, 0f,                                   // bottom-left
                )
            }
        }
        val frame = FrameLayout(context).apply {
            background = bg
            isClickable = true
            isFocusable = true
            // 0-width + weight=1f, shared 50/50 with the other segment, full toggleFrame height
            // (no padding/gap - see toggleFrame below) so the selected segment's blue fills its
            // entire half edge-to-edge.
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addView(
                label,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        val seg = Segment(frame, label, bg, mode)
        segments.add(seg)

        // On-tap visual feedback: a quick press-down scale (same 96% -> 100% used by the panel's
        // other tappable controls, e.g. addToCanvasButton in ComponentsPanel.kt) plus a darkened
        // shade of whichever color this segment currently has, so the press reads correctly
        // whether the segment is already the selected one or not. Returns false so the click
        // (which commits the actual selection) still fires.
        frame.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    applyState(seg, selected = seg.mode == activeMode, pressed = true)
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    applyState(seg, selected = seg.mode == activeMode, pressed = false)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
            }
            false
        }
        frame.setOnClickListener { setActive(mode, notify = true) }
        return seg
    }

    val designSegment = buildSegment("Design", ButtonObjectMode.DESIGN, isLeft = true)
    val prototypeSegment = buildSegment("Prototype", ButtonObjectMode.PROTOTYPE, isLeft = false)

    // The shared toggle frame: rounded-square, white, drop-shadowed via elevation against its own
    // rounded-rect outline (ViewOutlineProvider.BACKGROUND gives the shadow a shape to cast
    // against, so no separate manual shadow-layer view is needed).
    val sharedFrameBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = frameCornerRadiusPx
        setColor(Color.WHITE)
    }
    val toggleFrame = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // 0-width + weight=1f - claims whatever width the row has left after customButton's fixed
        // width and the gap before it, rather than the row's full width, now that it shares the
        // row with that button.
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        background = sharedFrameBg
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(4).toFloat()
        // No padding and no spacer between the two segments (contrast with the old inset+gap
        // look): each segment now runs edge-to-edge against the frame's outer border and flush
        // against the other segment's inner edge, so the selected segment's blue fills its whole
        // half with no white gap anywhere.
        clipToPadding = false
        clipChildren = false
        addView(designSegment.frame)
        addView(prototypeSegment.frame)
    }

    // customButton: frameless (no background of its own - see backButton/closeButton in
    // ComponentsPanel.kt for the same convention), same 40dp height as the toggle's segments so
    // the two line up, with a fixed 40dp width (icon plus a little tap-target padding).
    // Selecting it sets activeMode to CUSTOM via the same setActive() as the two segments, so it's
    // mutually exclusive with Design/Prototype automatically - no separate toggle logic needed.
    val customButton = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        contentDescription = "Custom"
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) }
        addView(customButtonIcon)
        setOnClickListener {
            setActive(ButtonObjectMode.CUSTOM, notify = true)
            onCustomButtonClick()
        }
    }

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Full width - this row is reserved for just the toggle frame and customButton beside it,
        // so together they should never be squeezed to make room for anything else.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clipToPadding = false
        clipChildren = false
        addView(toggleFrame)
        addView(customButton)
    }

    setActive(initialMode, notify = false)

    // Wrapping FrameLayout gives the row the panel's standard horizontal margin (dp(20), matching
    // the Buttons category panel's own root padding in ComponentsPanel.kt) while still letting the
    // row itself claim the full remaining width. Bottom padding is left at 0 so future content
    // added below can sit right underneath without a double gap.
    val root = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clipChildren = false
        clipToPadding = false
        setPadding(dp(20), dp(20), dp(20), 0)
        addView(row)
    }

    return ButtonObjectPanelViews(
        root = root,
        setActiveMode = { mode -> setActive(mode, notify = false) },
        customButtonView = customButton,
    )
}

/**
 * A small blurred drop-shadow strip - draws an (invisible) horizontal line and shows only its
 * Paint.setShadowLayer blur, the same software-layer blurred-shadow technique as
 * SidebarEdgeShadowView in ComponentsPanel.kt, just for a plain straight segment rather than a
 * path that bends around a corner.
 *
 * Positioned by SketchActivity (see positionButtonObjectCornerShadow()) directly above the Button
 * object panel's top edge, width-matched to customButton, and shown only while
 * ButtonObjectMode.CUSTOM is active (i.e. while the panel's top corners are flat/square - see
 * updateButtonObjectPanelCornerState()) - so that corner still reads as casting a shadow onto the
 * canvas above it, localized to just the segment of the edge under customButton rather than
 * suggesting the whole top edge has a shadow it doesn't.
 */
private class ButtonObjectCornerShadowView(
    context: Context,
    private val shadowRadiusPx: Float,
    private val shadowColor: Int,
    private val shadowDy: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        strokeCap = Paint.Cap.ROUND
        color = Color.TRANSPARENT
    }

    init {
        // Paint.setShadowLayer only renders on a software-rendered layer.
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.setShadowLayer(shadowRadiusPx, 0f, shadowDy, shadowColor)
        val y = height / 2f
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
    }
}

/**
 * Builds the (initially hidden) corner-shadow strip described above. Its width is set here to
 * customButton's own fixed 40dp width so the two always match without SketchActivity needing to
 * duplicate that constant; positionButtonObjectCornerShadow() only ever translates it, never
 * resizes it.
 */
fun buildButtonObjectCornerShadowView(context: Context): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    return ButtonObjectCornerShadowView(
        context = context,
        shadowRadiusPx = dp(6).toFloat(),
        shadowColor = Color.parseColor("#40000000"),
        shadowDy = dp(2).toFloat(),
    ).apply {
        layoutParams = ViewGroup.LayoutParams(dp(40), dp(16))
        visibility = View.GONE
    }
}
