package org.example.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

// Accent blue used for the selected Design/Prototype label's text (and customButton's icon tint
// when ButtonObjectMode.CUSTOM is active) - the toggle is neomorphic/monochrome now (see
// NeomorphicToggleFrame/NeomorphicSegmentFrame below), so this blue is the only remaining color
// cue for "selected", carried by the text rather than a fill.
private val MODE_SELECTED_BLUE = Color.parseColor("#3D7EFF")

private val MODE_TEXT_SELECTED = MODE_SELECTED_BLUE
private val MODE_TEXT_UNSELECTED = Color.parseColor("#6B7280")

// Neomorphic (soft-UI) shadow pair shared by both the outer toggle frame (as an outward, "raised"
// dual shadow) and the selected segment (as an inward, "pressed" dual shadow): a dark shadow on
// the bottom-right side and a light highlight on the top-left side, as if lit from the top-left -
// the same direction convention for both, just outward for the frame and inward for the segment.
private val NEO_DARK_SHADOW = Color.argb(46, 0, 0, 0)
private val NEO_LIGHT_SHADOW = Color.argb(204, 255, 255, 255)

/**
 * The shared toggle frame's white capsule, self-drawn (rather than a plain `background`
 * GradientDrawable + View.elevation) so it can carry a soft neomorphic dual shadow instead of
 * elevation's single hard-edged Material shadow: a light highlight bleeding out the top-left, a
 * dark shadow bleeding out the bottom-right, both soft and low-contrast per neomorphism's usual
 * "barely lifted off the surface" look.
 *
 * Padding equal to [shadowBleedPx] is applied on all four sides (see buildButtonObjectPanelContent
 * below) so that bleed has room to render within this view's own bounds - required because
 * Paint.setShadowLayer only renders on a software layer, and a software layer's bitmap is sized
 * exactly to the view, so anything drawn past the view's raw edge would otherwise be clipped
 * (same constraint ButtonObjectCornerShadowView below is sized to work around). The two segment
 * children are pushed inward by that same padding automatically, so they still line up exactly
 * with the white capsule drawn here.
 */
private class NeomorphicToggleFrame(
    context: Context,
    private val cornerRadiusPx: Float,
    private val fillColor: Int,
    private val shadowBleedPx: Float,
) : LinearLayout(context) {
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
    private val shape = RectF()

    // Soft/low-contrast by design: a fairly large blur relative to a small offset, so the shadow
    // reads as a gentle glow rather than a directional drop-shadow.
    private val shadowRadiusPx = shadowBleedPx * 0.6f
    private val shadowOffsetPx = shadowBleedPx * 0.35f

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.set(shadowBleedPx, shadowBleedPx, w - shadowBleedPx, h - shadowBleedPx)
    }

    override fun onDraw(canvas: Canvas) {
        // Dark shadow, offset down-right.
        shadowPaint.color = fillColor
        shadowPaint.setShadowLayer(shadowRadiusPx, shadowOffsetPx, shadowOffsetPx, NEO_DARK_SHADOW)
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, shadowPaint)

        // Light highlight, offset up-left.
        shadowPaint.setShadowLayer(shadowRadiusPx, -shadowOffsetPx, -shadowOffsetPx, NEO_LIGHT_SHADOW)
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, shadowPaint)

        // Clean flat fill on top, no shadow, so only the two soft halos beyond the capsule's own
        // edge stay visible - the capsule's own surface stays plain white.
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, fillPaint)
        super.onDraw(canvas)
    }
}

/**
 * One Design/Prototype tap target. It no longer fills solid blue when selected - instead it draws
 * the same dual dark/light shadow pair as NeomorphicToggleFrame above but INSET, along its own
 * rounded edges, so the selected segment reads as pressed/carved into the shared white frame
 * rather than a separate colored chip on top of it. Both segments keep the exact same white fill
 * as the frame at all times - selection is communicated purely by that inset shadow (plus the
 * label switching to the accent blue and bold), matching neomorphism's usual convention of one
 * surface color throughout, with shape read entirely through shadow.
 */
private class NeomorphicSegmentFrame(
    context: Context,
    private val cornerRadiusPx: Float,
    private val fillColor: Int,
    private val insetShadowRadiusPx: Float,
    private val insetShadowOffsetPx: Float,
) : FrameLayout(context) {
    /** Whether this segment is the active Design/Prototype selection. */
    var selected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** True only for the duration of a touch-down on this segment - a momentary press cue. */
    var pressed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val clipPath = Path()
    private val donutPath = Path()
    private val insetPath = Path()
    private val shape = RectF()

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    /**
     * Draws one inset shadow: a "donut" (this segment's full bounds minus its own shape shifted
     * by (dx, dy)) clipped to the segment's own rounded-rect shape. Only the sliver of the donut
     * that falls inside the clip - near the edge opposite the shift - ends up visible, and
     * setShadowLayer's blur across that sliver's boundary is what reads as a soft shadow cast
     * INTO the shape from that edge, rather than the usual outward drop-shadow.
     */
    private fun drawInsetShadow(canvas: Canvas, dx: Float, dy: Float, color: Int, radius: Float) {
        val bleed = radius + max(abs(dx), abs(dy)) + 4f
        donutPath.reset()
        donutPath.addRect(-bleed, -bleed, shape.width() + bleed, shape.height() + bleed, Path.Direction.CW)
        insetPath.reset()
        insetPath.addRoundRect(
            RectF(dx, dy, shape.width() + dx, shape.height() + dy),
            cornerRadiusPx,
            cornerRadiusPx,
            Path.Direction.CW,
        )
        donutPath.op(insetPath, Path.Op.DIFFERENCE)

        canvas.save()
        canvas.clipPath(clipPath)
        shadowPaint.color = fillColor
        shadowPaint.setShadowLayer(radius, dx, dy, color)
        canvas.drawPath(donutPath, shadowPaint)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        clipPath.reset()
        clipPath.addRoundRect(shape, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        // Flat base fill first - same white as the shared frame, so an unselected segment blends
        // in completely (no visible seam) until it's selected and its inset shadow appears.
        canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, fillPaint)

        if (selected) {
            // A momentary press deepens the inset slightly, the same "push in a little further"
            // cue the old scale-down animation gave the solid-blue version.
            val boost = if (pressed) 1.3f else 1f
            drawInsetShadow(canvas, insetShadowOffsetPx * boost, insetShadowOffsetPx * boost, NEO_DARK_SHADOW, insetShadowRadiusPx * boost)
            drawInsetShadow(canvas, -insetShadowOffsetPx * boost, -insetShadowOffsetPx * boost, NEO_LIGHT_SHADOW, insetShadowRadiusPx * boost)
        } else if (pressed) {
            // Unselected segment has no shape of its own to press in - same faint flat tint used
            // before, just as a plain overlay rather than a GradientDrawable color swap.
            val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(20, 0, 0, 0) }
            canvas.drawRoundRect(shape, cornerRadiusPx, cornerRadiusPx, tintPaint)
        }
        super.onDraw(canvas)
    }
}

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
 * carries a soft neomorphic dual shadow of its own (see NeomorphicToggleFrame) instead of a plain
 * elevation shadow. Inside that shared frame, each segment is its own equally-wide (50/50) tap
 * target with no padding/gap against the frame, and both stay the same flat white as the frame
 * itself at all times - the selected segment no longer fills solid Material blue; instead it
 * draws an INSET version of the same dual shadow (see NeomorphicSegmentFrame), reading as pressed/
 * carved into the frame rather than a colored chip on top of it. All four corners of a selected
 * segment's inset shadow (both the outer pair against the frame's own edge, and the inner pair at
 * the seam with the other segment) share the frame's own 12dp radius, so it reads as one
 * consistent rounded shape rather than only being rounded on one side. Only the label's own color
 * (accent blue, bold) still marks which segment is selected in text.
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

    class Segment(val frame: NeomorphicSegmentFrame, val label: TextView, val mode: ButtonObjectMode)

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
        seg.frame.selected = selected
        seg.frame.pressed = pressed
        seg.label.setTextColor(if (selected) MODE_TEXT_SELECTED else MODE_TEXT_UNSELECTED)
        seg.label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    fun setActive(mode: ButtonObjectMode, notify: Boolean) {
        activeMode = mode
        segments.forEach { applyState(it, selected = it.mode == mode) }
        customButtonIcon.setColorFilter(if (mode == ButtonObjectMode.CUSTOM) MODE_SELECTED_BLUE else MODE_TEXT_UNSELECTED)
        if (notify) onModeChanged(mode)
    }

    // toggleFrame's own corner radius (12dp) - reused here so a selected segment's own inset
    // shadow follows the same curve, reading as one continuous rounded shape rather than a
    // mismatched curve against the frame's own edge.
    val frameCornerRadiusPx = dp(12).toFloat()

    // Segment corners are uniformly rounded on all four corners to frameCornerRadiusPx, so a
    // selected segment reads as a full rounded-square shape - both the outer pair (against the
    // shared frame's own edge) and the inner pair (at the seam with the other segment) match the
    // frame's 12dp radius, rather than only being rounded on the outer side.
    // Inset shadow tuning for a selected segment - notably smaller/tighter than the outer frame's
    // own outward shadow (dp(7)/dp(4) there), since an inset shadow within a ~40dp-tall tap
    // target needs to stay compact or it reads as a smudge rather than a crisp carved edge.
    val segmentInsetShadowRadiusPx = dp(5).toFloat()
    val segmentInsetShadowOffsetPx = dp(2).toFloat()

    fun buildSegment(text: String, mode: ButtonObjectMode): Segment {
        val label = TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val frame = NeomorphicSegmentFrame(
            context = context,
            cornerRadiusPx = frameCornerRadiusPx,
            fillColor = Color.WHITE,
            insetShadowRadiusPx = segmentInsetShadowRadiusPx,
            insetShadowOffsetPx = segmentInsetShadowOffsetPx,
        ).apply {
            isClickable = true
            isFocusable = true
            // 0-width + weight=1f, shared 50/50 with the other segment, full toggleFrame height
            // (no padding/gap - see toggleFrame below) so the selected segment's inset shadow
            // fills its entire half edge-to-edge.
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addView(
                label,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        val seg = Segment(frame, label, mode)
        segments.add(seg)

        // On-tap visual feedback: a quick press-down scale (same 96% -> 100% used by the panel's
        // other tappable controls, e.g. addToCanvasButton in ComponentsPanel.kt) plus, via
        // NeomorphicSegmentFrame's own `pressed` flag, either a deepened inset shadow (if this
        // segment is already selected) or a faint flat tint (if not) - so the press reads
        // correctly either way. Returns false so the click (which commits the actual selection)
        // still fires.
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

    // The shared toggle frame: rounded-square, white, carrying a soft neomorphic dual shadow (see
    // NeomorphicToggleFrame) instead of the old single flat elevation shadow. shadowBleedPx is
    // also applied as this view's own padding on all sides, reserving room for that shadow's blur
    // to render within the view's bounds - see NeomorphicToggleFrame's class doc.
    val frameShadowBleedPx = dp(10).toFloat()
    val toggleFrame = NeomorphicToggleFrame(
        context = context,
        cornerRadiusPx = frameCornerRadiusPx,
        fillColor = Color.WHITE,
        shadowBleedPx = frameShadowBleedPx,
    ).apply {
        orientation = LinearLayout.HORIZONTAL
        // 0-width + weight=1f - claims whatever width the row has left after customButton's fixed
        // width and the gap before it, rather than the row's full width, now that it shares the
        // row with that button.
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setPadding(frameShadowBleedPx.toInt(), frameShadowBleedPx.toInt(), frameShadowBleedPx.toInt(), frameShadowBleedPx.toInt())
        // No gap/spacer between the two segments beyond that shared padding (contrast with the
        // old inset+gap look): each segment runs edge-to-edge against the padded frame border and
        // flush against the other segment's inner edge, so a selected segment's inset shadow
        // fills its whole half with no white gap anywhere.
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
