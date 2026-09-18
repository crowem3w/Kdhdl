package org.example.test

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

// Unselected segment stays transparent, letting the shared neomorphic frame surface show through
// directly - it has no fill of its own, only the material-blue selected segment does.
private val MODE_UNSELECTED_FRAME = Color.TRANSPARENT
// A faint tap-feedback tint for the unselected segment (not a "frame" color of its own - just a
// momentary press cue), since it has no fill to darken the way the selected segment's blue does.
private val MODE_UNSELECTED_FRAME_PRESSED = Color.parseColor("#14000000")

private val MODE_TEXT_SELECTED = Color.WHITE
private val MODE_TEXT_UNSELECTED = Color.parseColor("#6B7280")

// --- Neomorphism (soft UI) for the toggle frame only -----------------------------------------
// The frame's fill matches the panel's own background (bg_bottom_panel.xml's #F3F4F6) rather than
// a separate white card, so it reads as carved/pressed out of the same surface rather than an
// object floating on top of it - that shared color is what makes the light/dark shadow pair below
// look like relief instead of a plain drop shadow.
private val NEO_SURFACE = Color.parseColor("#F3F4F6")
// Light "highlight" shadow - stands in for a light source from the top-left.
private val NEO_LIGHT = Color.WHITE
// Dark "shade" shadow - a muted slate a few steps darker than NEO_SURFACE, standard companion tone
// for a light-gray soft-UI surface (paired with NEO_LIGHT above).
private val NEO_DARK = Color.parseColor("#A9AFBC")

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
 * Draws the toggle frame's raised neomorphic shadow pair: a light blurred rounded-square nudged
 * up-left and a dark one nudged down-right, both behind and straddling the frame's edge (this view
 * sits underneath segmentsRow at the same bounds - see buildButtonObjectPanelContent()). Together,
 * against the shared NEO_SURFACE fill, they read as the frame gently popping out of the panel
 * rather than a separate card with a normal drop shadow.
 *
 * BlurMaskFilter (like Paint.setShadowLayer used elsewhere in this file) only renders on a
 * software-rendered layer, hence setLayerType(LAYER_TYPE_SOFTWARE) below.
 */
private class NeomorphicRaisedShadowView(
    context: Context,
    private val cornerRadiusPx: Float,
    private val shadowRadiusPx: Float,
    private val offsetPx: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Dark shade, nudged down-right: draw the rounded-square offset by +offset,+offset so its
        // blurred edge spills out along the frame's bottom-right side.
        paint.color = NEO_DARK
        paint.alpha = 140
        paint.maskFilter = BlurMaskFilter(shadowRadiusPx, BlurMaskFilter.Blur.NORMAL)
        rect.set(offsetPx, offsetPx, w + offsetPx, h + offsetPx)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

        // Light highlight, nudged up-left: same shape mirrored to the opposite corner, so the two
        // together read as one light source from the top-left.
        paint.color = NEO_LIGHT
        paint.alpha = 200
        rect.set(-offsetPx, -offsetPx, w - offsetPx, h - offsetPx)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
    }
}

/**
 * Draws a subtle inset/pressed shadow pair inside a single segment - shown only while that segment
 * is the selected (blue) one, see applyState()/Segment.insetShadow below. Uses the mirror-image of
 * the trick above: each phantom shape is nudged AWAY from the corner it's meant to darken/lighten,
 * then the canvas is clipped to this view's own rounded-rect path (matching the segment's own
 * corner radius) so only the sliver of blur that falls inside near that corner survives - giving a
 * soft "recessed" edge instead of a shadow spilling outward.
 */
private class NeomorphicInsetShadowView(
    context: Context,
    private val cornerRadiusPx: Float,
    private val shadowRadiusPx: Float,
    private val offsetPx: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val clipPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(RectF(0f, 0f, w, h), cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Dark edge at the top-left: phantom shape nudged down-right leaves the top-left sliver of
        // this view's own bounds outside it, so only that sliver's blur shows once clipped.
        paint.color = NEO_DARK
        paint.alpha = 90
        paint.maskFilter = BlurMaskFilter(shadowRadiusPx, BlurMaskFilter.Blur.NORMAL)
        rect.set(offsetPx, offsetPx, w + offsetPx, h + offsetPx)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

        // Light edge at the bottom-right: mirrored the other way, kept faint ("subtle") so it
        // doesn't wash out the segment's white label text.
        paint.color = NEO_LIGHT
        paint.alpha = 70
        rect.set(-offsetPx, -offsetPx, w - offsetPx, h - offsetPx)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

        canvas.restore()
    }
}

/**
 * Builds the top row of the Button object panel (buttonObjectContentContainer - see
 * setupButtonObjectPanel()/openButtonObjectPanel() in SketchActivity): the Design/Prototype
 * segmented toggle plus customButton, a frameless icon button to its right. This row is the only
 * thing this panel shows for now; more controls are expected to be added below it later.
 *
 * The toggle's two segments share ONE outer frame: a rounded-square container, styled as
 * neomorphism (soft UI) rather than a white card with a normal drop shadow - its fill matches the
 * surrounding panel background, and a light/dark blurred shadow pair painted behind it
 * (toggleFrame/neoShadow/segmentsRow below, see NeomorphicRaisedShadowView) makes it read as
 * raised straight out of that surface. Inside that shared frame, each segment is its own
 * equally-wide (50/50) tap target with no padding/gap against the frame: the unselected segment is
 * transparent (the frame's surface shows straight through it), while the selected segment fills
 * its entire half solid Material blue plus its own subtle pressed-in shadow (see
 * NeomorphicInsetShadowView/Segment.insetShadow), as a full rounded-square shape - all four
 * corners (both the outer pair against the frame's own edge, and the inner pair at the seam with
 * the other segment) share the frame's own 12dp radius, so the blue reads as one consistent
 * rounded shape rather than only being rounded on one side.
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

    class Segment(
        val frame: FrameLayout,
        val label: TextView,
        val bg: GradientDrawable,
        // Only shown while this segment is selected - see applyState() below - to give the
        // selected (blue) segment a subtle pressed-in look, distinct from the toggle frame's own
        // raised look (see NeomorphicRaisedShadowView/segmentsRow).
        val insetShadow: NeomorphicInsetShadowView,
        val mode: ButtonObjectMode,
    )

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
        // Pressed-in neomorphic edge only makes sense on the selected (filled-blue) segment - the
        // unselected one is transparent, so there's nothing for it to read as recessed into.
        seg.insetShadow.visibility = if (selected) View.VISIBLE else View.GONE
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

    // Segment corners are uniformly rounded on all four corners to frameCornerRadiusPx, so a
    // selected segment reads as a full rounded-square shape - both the outer pair (against the
    // shared frame's own edge) and the inner pair (at the seam with the other segment) match the
    // frame's 12dp radius, rather than only being rounded on the outer side.
    fun buildSegment(text: String, mode: ButtonObjectMode): Segment {
        val label = TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = frameCornerRadiusPx
        }
        // Inset shadow overlay for this segment's own pressed-in look while selected (see
        // NeomorphicInsetShadowView) - same corner radius as the segment's own fill so its clip
        // matches exactly, smaller blur/offset than the outer frame's since it lives inside a
        // single 40dp-tall segment rather than spanning the whole toggle.
        val insetShadowView = NeomorphicInsetShadowView(
            context = context,
            cornerRadiusPx = frameCornerRadiusPx,
            shadowRadiusPx = dp(4).toFloat(),
            offsetPx = dp(2).toFloat(),
        ).apply { visibility = View.GONE }

        val frame = FrameLayout(context).apply {
            background = bg
            isClickable = true
            isFocusable = true
            // 0-width + weight=1f, shared 50/50 with the other segment, full toggleFrame height
            // (no padding/gap - see toggleFrame below) so the selected segment's blue fills its
            // entire half edge-to-edge.
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            // Shadow first (drawn behind), label on top so it stays fully legible.
            addView(insetShadowView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                label,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        val seg = Segment(frame, label, bg, insetShadowView, mode)
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

    val designSegment = buildSegment("Design", ButtonObjectMode.DESIGN)
    val prototypeSegment = buildSegment("Prototype", ButtonObjectMode.PROTOTYPE)

    // The shared toggle frame - now neomorphic (soft UI) rather than a white card with a normal
    // elevation shadow: its fill (NEO_SURFACE) matches the panel background it sits on, and depth
    // comes entirely from the light/dark shadow pair painted by neoShadow behind it (see
    // NeomorphicRaisedShadowView above) so it reads as raised straight out of that surface.
    val sharedFrameBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = frameCornerRadiusPx
        setColor(NEO_SURFACE)
    }
    // segmentsRow: unchanged from before other than the background swap above - still the plain
    // horizontal row of the two 50/50 segments, edge-to-edge with no padding/gap between them.
    val segmentsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        background = sharedFrameBg
        clipToPadding = false
        clipChildren = false
        addView(designSegment.frame)
        addView(prototypeSegment.frame)
    }
    // neoShadow: sized to always match segmentsRow exactly (same MATCH_PARENT bounds within
    // toggleFrame below), drawn first so it sits behind it. Its own view bounds straddle the
    // frame's edge with room to spare (clipChildren=false on toggleFrame, below) so the blur isn't
    // cut off right at the edge.
    val neoShadow = NeomorphicRaisedShadowView(
        context = context,
        cornerRadiusPx = frameCornerRadiusPx,
        shadowRadiusPx = dp(8).toFloat(),
        offsetPx = dp(4).toFloat(),
    )
    val toggleFrame = FrameLayout(context).apply {
        // 0-width + weight=1f - claims whatever width the row has left after customButton's fixed
        // width and the gap before it, rather than the row's full width, now that it shares the
        // row with that button.
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clipToPadding = false
        clipChildren = false
        addView(neoShadow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(segmentsRow)
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
