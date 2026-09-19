package org.example.test

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Palette for the Button setup page (see buildButtonCategoryPanel()): soft-gray surface with dark
// text. The Elements sidebar around it is themed via Material tokens instead (see
// ThemeOverlay.Test.ElementsSidebar in styles_sketch.xml).
private val PANEL_BG = Color.parseColor("#F3F4F6")
private val PANEL_PRIMARY_TEXT = Color.parseColor("#1A1B24")
private val PANEL_SECONDARY_TEXT = Color.parseColor("#6B7280")
private val PANEL_DIVIDER = Color.parseColor("#D1D5DB")

// Selection-frame blue for the two Buttons-panel previews in canvasContentRow (see
// buildButtonCategoryPanel()) - standard Material blue.
private val BUTTON_PREVIEW_SELECTED_BORDER = Color.parseColor("#2196F3")

private data class ElementCategory(val id: String, val label: String, val iconRes: Int)

// The categories listed under the "Elements" header, in display order. Only "structure"
// (Buttons) opens anything so far - see bindElementsSidebar().
private const val CATEGORY_BUTTONS = "structure"

private val ELEMENT_CATEGORIES = listOf(
    ElementCategory(CATEGORY_BUTTONS, "Buttons", R.drawable.ic_cat_buttons),
    ElementCategory("layout", "Layout", R.drawable.ic_cat_layout),
    ElementCategory("typography", "Typography", R.drawable.ic_cat_typography),
    ElementCategory("shapes", "Geometry", R.drawable.ic_cat_geometry),
    ElementCategory("media", "Media", R.drawable.ic_media),
    ElementCategory("navigation", "Navigation", R.drawable.ic_cat_navigation),
    ElementCategory("input", "Input", R.drawable.ic_cat_input),
    ElementCategory("actions", "Actions", R.drawable.ic_cat_actions),
    ElementCategory("content", "Content", R.drawable.ic_cat_content),
    ElementCategory("feedback", "Feedback", R.drawable.ic_cat_feedback),
    ElementCategory("mobile", "Mobile", R.drawable.ic_cat_mobile),
    ElementCategory("prototype", "Prototype", R.drawable.ic_cat_prototype),
)

/** Handle returned by buildButtonCategoryPanel() - mirrors ScreensPanelContentViews so the
 *  rename pattern stays consistent between the Screens panel and this Button panel. */
class ButtonCategoryPanelViews(
    val root: View,
    private val labelView: EditText,
    private var committedLabel: String,
) {
    fun setDisplayedLabel(name: String) {
        committedLabel = name
        if (!labelView.isFocusable) labelView.setText(name)
    }
}

/**
 * Builds the Button setup page shown inside the Elements sidebar when "Buttons" is tapped (see
 * bindElementsSidebar() below). Light-mode, matches the palette this file has always used for
 * the Button UI.
 *
 * - Top-left: a back arrow ([onBack]) that returns to the sidebar's category list, then the
 *   Button instance's name ("Button {N}"), renamable inline exactly like the Screens panel's
 *   header title (see buildScreensPanelContent() in ScreensPanel.kt).
 * - Top-right: a frameless (x) that closes the whole sidebar via [onClose].
 * - Body: the frameless/framed previews, "Insert instance", and the Align / Label settings.
 *
 * [compact] tightens the horizontal padding and the Align segmented control so the page fits the
 * narrower (320dp) sidebar instead of the full-width bottom sheet it was originally laid out for.
 */
fun buildButtonCategoryPanel(
    context: Context,
    initialLabel: String,
    onClose: () -> Unit,
    // Presentation-only hook from when this lived in a bottom sheet (tapping a preview used to max
    // out the sheet). The sidebar has no such state, so it is optional and unused by the sidebar.
    onExpandRequested: () -> Unit = {},
    onLabelRenamed: (String) -> Unit = {},
    // Style/align now travel together, since a placed button needs both to render correctly (see
    // SketchPart.buttonAlign and drawFramedButtonLabel() in SketchCanvasView.kt).
    onAddToCanvasRequested: (ButtonStyle, String) -> Unit = { _, _ -> },
    // Fired on every Align/Label/variant change - not just on "+ Add to Canvas" - so that when
    // this panel is editing an already-placed button (see initialVariant/initialAlign/
    // initialButtonText below), the change reaches the Canvas instantly instead of only updating
    // this panel's own preview.
    onLiveChanged: (ButtonStyle, String, String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit = {},
    // When this panel is opened to edit an already-placed FRAMED button (rather than compose a
    // new one), these seed the controls with that button's current style/align/text instead of
    // the "fresh instance" defaults.
    initialVariant: ButtonStyle = ButtonStyle.FRAMELESS,
    initialAlign: String = "Centered",
    initialButtonText: String = "Button",
    compact: Boolean = false,
): ButtonCategoryPanelViews {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    // Layout metrics that shrink in compact (sidebar) mode.
    val hPad = if (compact) dp(12) else dp(20)
    val settingsLeftCellPad = if (compact) dp(10) else dp(14)
    val settingsRightCellPad = if (compact) dp(8) else dp(12)
    val alignOptionLabelSize = if (compact) 8f else 9f

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(PANEL_BG)
        // Top padding kept small so the name/(x) row sits right up near the panel's top edge,
        // but not 0 - the panel's bg (bg_bottom_panel.xml) has a 24dp top corner radius, so a
        // little clearance keeps the row clear of that curve instead of clipping into it.
        setPadding(hPad, dp(8), hPad, dp(20))
        clipChildren = false
        clipToPadding = false
    }

    // Doubles as the rename field, same non-focusable-until-tapped approach as
    // buildScreensPanelContent()'s title EditText. Size/spacing matches that same title field
    // (textSize 18f, marginStart 8dp off the back button) so the two panels' headers read
    // consistently.
    var committedLabel = initialLabel
    val label = EditText(context).apply {
        setText(initialLabel)
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(PANEL_PRIMARY_TEXT)
        setSingleLine(true)
        maxLines = 1
        background = null
        setPadding(0, 0, 0, 0)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        isFocusable = false
        isFocusableInTouchMode = false
        isCursorVisible = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        contentDescription = "Button name, tap to rename"
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        }
    }

    fun enterLabelEditMode() {
        label.isFocusable = true
        label.isFocusableInTouchMode = true
        label.isCursorVisible = true
        label.setSelection(label.text.length)
        label.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(label, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun exitLabelEditMode(commit: Boolean) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(label.windowToken, 0)
        label.isCursorVisible = false
        label.isFocusable = false
        label.isFocusableInTouchMode = false
        val typed = label.text.toString().trim()
        if (commit && typed.isNotEmpty() && typed != committedLabel) {
            committedLabel = typed
            label.setText(typed)
            onLabelRenamed(typed)
        } else {
            if (commit && typed.isEmpty()) {
                Toast.makeText(context, "Name can't be empty", Toast.LENGTH_SHORT).show()
            }
            label.setText(committedLabel)
        }
    }

    label.setOnClickListener { if (!label.isFocusable) enterLabelEditMode() }
    label.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) exitLabelEditMode(commit = true) }
    label.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            exitLabelEditMode(commit = true)
            true
        } else {
            false
        }
    }

    // Frameless back arrow, sits to the left of the label - navigates back to the Elements
    // panel's normal category rail/content without dismissing the whole panel (unlike the (x)
    // close button below, which closes everything). Sized 36dp/20dp icon, matching
    // buildScreensPanelContent()'s own back/close button exactly (no end margin - spacing to the
    // label comes from the label's own marginStart instead, same as that panel).
    val backButton = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        isClickable = true
        isFocusable = true
        contentDescription = "Back"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back_return)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
        setOnClickListener { onBack() }
    }

    // Frameless (x): no background/frame behind the icon, unlike e.g. filterButton above which
    // has bg_quick_action_item_pressed.
    val closeButton = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        isClickable = true
        isFocusable = true
        contentDescription = "Close"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        })
        setOnClickListener { onClose() }
    }

    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        }
        addView(backButton)
        addView(label)
        addView(closeButton)
    })

    // Tracks which preview is selected (shadow-highlighted) - drives both the shadow visuals
    // below and which style the "Add to Canvas" button (see below) places on the real canvas.
    // Frameless starts selected by default. ButtonStyle is shared with SketchPart (see
    // SketchPart.kt) so the selection maps directly onto the placed part's own styling.
    var selectedVariant: ButtonStyle = initialVariant

    // Hoisted above the previews/click-handlers below (rather than declared down by the Align
    // segmented control where it's used for that UI) so onLiveChanged - fired from the variant
    // click handlers - can already read the current Align selection. See AlignOptionEntry further
    // down for the actual segmented control built from this.
    var selectedAlign = initialAlign

    // Assigned once the settings panel (Align/Label, further below) is built, so
    // updateSelectionVisuals() can also refresh which settings rows are visible and keep the
    // Label input in sync with whichever preview just became selected. A no-op until then.
    var onSelectedVariantChanged: () -> Unit = {}

    // Frameless "Button" - plain text, no background at all by default. ~20% larger than before
    // (textSize 14->17, padding 10/8->12/10) per the earlier "slightly bigger" sizing pass, plus
    // a further ~17% horizontal-only bump (12->14dp) so the preview itself reads a bit wider
    // without maxing out the row.
    val buttonNoFrame = TextView(context).apply {
        text = initialButtonText
        setTextColor(PANEL_PRIMARY_TEXT)
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        contentDescription = "Button, no frame - tap to select or expand panel"
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    // Applied to buttonNoFrame only while selected (see updateSelectionVisuals()); background is
    // null the rest of the time so it stays truly frameless when deselected.
    val framelessSelectedBorderBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), BUTTON_PREVIEW_SELECTED_BORDER)
    }

    // "Button" with a rounded-square frame - now a solid black fill (previously transparent)
    // with white text so it stays readable, per the "black default" request. Border kept as the
    // rounded-square frame, black by default and switching to blue while selected (see
    // updateSelectionVisuals()). ~20% larger (cornerRadius 10->12, padding 16/10->19/12, textSize
    // 14->17), plus a further ~16% horizontal-only bump (19->22dp) so it reads a bit wider.
    val framedButtonBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.BLACK)
        setStroke(dp(2), Color.BLACK)
    }
    // Kept as a named reference (rather than inline in addView) so the Align/Label settings
    // below (see settings panel further down) can read and update its text and gravity.
    val buttonWithFrameLabel = TextView(context).apply {
        text = initialButtonText
        setTextColor(Color.WHITE)
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }
    val buttonWithFrame = FrameLayout(context).apply {
        background = framedButtonBg
        isClickable = true
        isFocusable = true
        contentDescription = "Button, with frame - tap to select or expand panel"
        setPadding(dp(22), dp(12), dp(22), dp(12))
        addView(buttonWithFrameLabel)
    }

    // Applies the shadow AND the blue selection border to whichever preview is selected, and
    // clears both from the other one. buttonWithFrame has a solid fill, so a View elevation
    // shadow (drawn from its rounded-rect outline) reads correctly; buttonNoFrame has no
    // background, so its "shadow" is instead a Paint-level shadow behind the glyphs themselves
    // (setShadowLayer), since an elevation shadow there would just draw a plain rectangle behind
    // the text rather than hugging it. The border: buttonNoFrame swaps in a blue-stroked
    // background only while selected (null otherwise, staying frameless); buttonWithFrame keeps
    // its background always but its stroke color toggles black/blue in place.
    fun updateSelectionVisuals() {
        val framelessSelected = selectedVariant == ButtonStyle.FRAMELESS
        buttonNoFrame.setShadowLayer(
            if (framelessSelected) dp(6).toFloat() else 0f,
            0f,
            if (framelessSelected) dp(2).toFloat() else 0f,
            if (framelessSelected) Color.parseColor("#66000000") else Color.TRANSPARENT,
        )
        buttonWithFrame.elevation = if (!framelessSelected) dp(6).toFloat() else 0f
        buttonNoFrame.background = if (framelessSelected) framelessSelectedBorderBg else null
        framedButtonBg.setStroke(dp(2), if (!framelessSelected) BUTTON_PREVIEW_SELECTED_BORDER else Color.BLACK)
        onSelectedVariantChanged()
    }
    updateSelectionVisuals()

    buttonNoFrame.setOnClickListener {
        selectedVariant = ButtonStyle.FRAMELESS
        updateSelectionVisuals()
        onExpandRequested()
        onLiveChanged(selectedVariant, selectedAlign, buttonNoFrame.text.toString())
    }
    buttonWithFrame.setOnClickListener {
        selectedVariant = ButtonStyle.FRAMED
        updateSelectionVisuals()
        onExpandRequested()
        onLiveChanged(selectedVariant, selectedAlign, buttonWithFrameLabel.text.toString())
    }

    // The two contents, laid out side by side directly on the Canvas surface (the previous
    // light-gray content-row background has been removed - see canvasBg below for the Canvas's
    // own color instead). clipChildren/clipToPadding false so buttonWithFrame's elevation shadow
    // isn't cut off when selected.
    val canvasContentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        clipChildren = false
        clipToPadding = false
        addView(buttonNoFrame)
        addView(buttonWithFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(24)
        })
    }

    // The Canvas itself: a full-width, soft-gray-framed surface - height stays content-sized to
    // fit canvasContentRow (see below), width bleeds edge-to-edge. Fill color updated to #E6E7E9
    // (previously white, with the gray content-row drawn on top of it).
    val canvasBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor("#E6E7E9"))
        setStroke(dp(1), PANEL_DIVIDER)
    }
    val canvas = FrameLayout(context).apply {
        background = canvasBg
        // Full width, true edge-to-edge - counteracts root's 20dp side padding with matching
        // negative margins (root has clipToPadding/clipChildren = false so this is allowed to
        // draw into that padding; hPad matches root's horizontal padding). Height stays content-sized (wrap_content) rather than being
        // forced to match the width, so this is a rectangle now, not a square.
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            marginStart = -hPad
            marginEnd = -hPad
        }
        setPadding(dp(20), dp(20), dp(20), dp(20))
        clipChildren = false
        clipToPadding = false
        addView(canvasContentRow)
    }

    root.addView(canvas)

    // Full-width, rounded-rectangle action button that actually places the currently-selected
    // preview's style onto the real sketch canvas (the two previews above stay presentation-only/
    // expand the panel, same as before). Stretched to fill the row below the canvas rather than
    // sizing to its label, and filled with Material blue as the primary action for this panel.
    val addToCanvasIdleColor = Color.parseColor("#2196F3") // Material blue 500
    val addToCanvasPressedColor = Color.parseColor("#1976D2") // Material blue 700 - darkens on press
    val addToCanvasBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(addToCanvasIdleColor)
    }

    val addToCanvasButton = FrameLayout(context).apply {
        background = addToCanvasBg
        isClickable = true
        isFocusable = true
        contentDescription = "Add the selected Button style to the canvas"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        }
        setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(TextView(context).apply {
            text = "Insert instance"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        })
    }

    // Visual feedback: darkens to a deeper Material blue on press (standard Material "state
    // darken" for a filled/contained button, reads clearly against the blue fill unlike the old
    // soft-gray tint), plus the same quick press-down scale (96% -> 100%) for tactile feel.
    // Returns false so the normal click still fires.
    addToCanvasButton.setOnTouchListener { view, event ->
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                addToCanvasBg.setColor(addToCanvasPressedColor)
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100L).start()
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                addToCanvasBg.setColor(addToCanvasIdleColor)
                view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
        }
        false
    }

    addToCanvasButton.setOnClickListener {
        onAddToCanvasRequested(selectedVariant, selectedAlign)
    }
    root.addView(addToCanvasButton)

    // Settings panel: rounded-square frame with a drop shadow, sitting below the "Insert
    // instance" button, holding one row per setting ("Align", "Label"). Each row reuses the
    // original 30%-gray / 70%-white split (gray matches the Canvas surface's own color, see
    // canvasBg above) divided by a vertical line, with a thin horizontal divider between rows.
    // Align only appears for the framed button variant; Label always appears (see
    // updateSettingsRowsVisibility() below).
    val settingsLeftBg = Color.parseColor("#E6E7E9")
    val settingsCornerRadius = dp(16).toFloat()
    val settingsFrameBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = settingsCornerRadius
        setColor(Color.WHITE)
    }
    val settingsFrame = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = settingsFrameBg
        clipToOutline = true
        elevation = dp(6).toFloat()
        // Same full-width treatment as addToCanvasButton ("Insert instance") above -
        // MATCH_PARENT rather than a fixed width, no horizontal centering needed as a result.
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        }
    }

    // Left cell shared by every row: setting icon beside setting name (horizontal), on the gray
    // fill. Width is content-driven (WRAP_CONTENT), not a fixed weight, so rows don't line up
    // into table-like columns - the right cell (weight 1f) simply takes whatever space is left.
    fun buildSettingsLeftCell(iconRes: Int, labelText: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(settingsLeftBg)
        setPadding(settingsLeftCellPad, 0, settingsLeftCellPad, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
        })
        addView(TextView(context).apply {
            text = labelText
            setTextColor(PANEL_SECONDARY_TEXT)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
    }

    fun buildVerticalDivider(): View = View(context).apply {
        setBackgroundColor(PANEL_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // Bold caption placed above a row's right-side control (segmented control / input box).
    fun buildRightCellHeader(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(PANEL_PRIMARY_TEXT)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        }
    }

    // --- Align row: right cell holds a 5-option segmented control (Justify/Start/End/
    // Centered/Stack), each option an icon+label pair stacked vertically, divided by a vertical
    // line, one selected at a time. Applied directly to buttonWithFrame's label - the only
    // preview Align is shown for.
    data class AlignOptionEntry(val id: String, val iconRes: Int)
    val alignOptions = listOf(
        AlignOptionEntry("Justify", R.drawable.ic_align_justify),
        AlignOptionEntry("Start", R.drawable.ic_align_start),
        AlignOptionEntry("End", R.drawable.ic_align_end),
        AlignOptionEntry("Centered", R.drawable.ic_align_center),
        AlignOptionEntry("Stack", R.drawable.ic_align_stack),
    )
    data class AlignOptionViews(val container: View, val icon: ImageView, val label: TextView)
    val alignOptionViews = LinkedHashMap<String, AlignOptionViews>()

    fun applyAlignToFramedPreview() {
        val gravity = when (selectedAlign) {
            "Start" -> Gravity.START or Gravity.CENTER_VERTICAL
            "End" -> Gravity.END or Gravity.CENTER_VERTICAL
            "Justify" -> Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL
            // "Stack" has no direct single-line equivalent for a button label - approximated
            // as centered, same as "Centered", until multi-line stacking is supported.
            else -> Gravity.CENTER
        }
        buttonWithFrameLabel.gravity = if (selectedAlign == "Justify") Gravity.CENTER else gravity
        (buttonWithFrameLabel.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (selectedAlign == "Justify") ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            this.gravity = gravity
        }
        buttonWithFrameLabel.requestLayout()
    }

    fun updateAlignVisuals() {
        alignOptionViews.forEach { (opt, views) ->
            val selected = opt == selectedAlign
            val fg = if (selected) Color.WHITE else PANEL_PRIMARY_TEXT
            views.icon.setColorFilter(fg)
            views.label.setTextColor(fg)
            views.container.setBackgroundColor(if (selected) BUTTON_PREVIEW_SELECTED_BORDER else Color.TRANSPARENT)
        }
    }

    val alignSegmentedBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), PANEL_DIVIDER)
        setColor(Color.WHITE)
    }
    val alignSegmentedControlHeight = dp(56)
    val alignSegmentedControl = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = alignSegmentedBg
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alignSegmentedControlHeight)
    }
    alignOptions.forEachIndexed { index, option ->
        val icon = ImageView(context).apply {
            setImageResource(option.iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { bottomMargin = dp(3) }
        }
        val labelView = TextView(context).apply {
            text = option.id
            textSize = alignOptionLabelSize
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val optionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Align: ${option.id}"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(icon)
            addView(labelView)
            setOnClickListener {
                selectedAlign = option.id
                updateAlignVisuals()
                applyAlignToFramedPreview()
                onLiveChanged(selectedVariant, selectedAlign, buttonWithFrameLabel.text.toString())
            }
        }
        alignOptionViews[option.id] = AlignOptionViews(optionContainer, icon, labelView)
        alignSegmentedControl.addView(optionContainer)
        if (index != alignOptions.lastIndex) alignSegmentedControl.addView(buildVerticalDivider())
    }
    updateAlignVisuals()
    // Seeds buttonWithFrameLabel's gravity/width to match initialAlign immediately (rather than
    // only once the person taps an Align option), so re-opening this panel on an already-placed
    // button shows its real current alignment right away.
    applyAlignToFramedPreview()

    val alignRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(buildSettingsLeftCell(R.drawable.ic_align, "Align"))
        addView(buildVerticalDivider())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(settingsRightCellPad, dp(12), settingsRightCellPad, dp(12))
            // weight 1f: fills whatever space is left after the content-sized left cell,
            // rather than a fixed fraction - keeps this row's split independent of the other
            // row's, so the two rows don't line up into table-like columns.
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(buildRightCellHeader("Align"))
            addView(alignSegmentedControl)
        })
    }

    // --- Label row: right cell holds a full-width rounded-square text input, editing whichever
    // preview is currently selected (buttonNoFrame's text, or buttonWithFrameLabel's text).
    val labelInputBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(PANEL_BG)
        setStroke(dp(1), PANEL_DIVIDER)
    }
    val labelInput = EditText(context).apply {
        setText(buttonNoFrame.text)
        textSize = 13f
        setTextColor(PANEL_PRIMARY_TEXT)
        setSingleLine(true)
        maxLines = 1
        background = labelInputBg
        setPadding(dp(12), dp(6), dp(12), dp(6))
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        contentDescription = "Button label text"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
    }
    labelInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val text = s?.toString().orEmpty()
            if (selectedVariant == ButtonStyle.FRAMELESS) buttonNoFrame.text = text else buttonWithFrameLabel.text = text
            onLiveChanged(selectedVariant, selectedAlign, text)
        }
    })

    val labelRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(buildSettingsLeftCell(R.drawable.ic_cat_typography, "Label"))
        addView(buildVerticalDivider())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(settingsRightCellPad, dp(12), settingsRightCellPad, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(buildRightCellHeader("Label"))
            addView(labelInput)
        })
    }

    settingsFrame.addView(alignRow)
    settingsFrame.addView(labelRow)
    root.addView(settingsFrame)

    // Align only shows for the framed variant; Label always shows. Also keeps the Label input
    // synced to whichever preview's text is now active, so it always reflects the current value
    // instead of stale text left over from the other variant.
    fun updateSettingsRowsVisibility() {
        val framedSelected = selectedVariant == ButtonStyle.FRAMED
        alignRow.visibility = if (framedSelected) View.VISIBLE else View.GONE
        labelInput.setText(if (framedSelected) buttonWithFrameLabel.text else buttonNoFrame.text)
    }
    onSelectedVariantChanged = { updateSettingsRowsVisibility() }
    updateSettingsRowsVisibility()

    return ButtonCategoryPanelViews(root, label, committedLabel)
}

/** Handle returned by bindElementsSidebar(). */
class ElementsSidebarController(
    private val showList: () -> Unit,
    private val isOnButtonPage: () -> Boolean,
) {
    /** Returns the sidebar to its category list (dropping the Button page, if it was showing). */
    fun resetToList() = showList()

    /**
     * System-Back handling: from the Button page, steps back to the category list (same as its
     * back arrow). Returns true if it consumed Back, false if the caller should close the sidebar.
     */
    fun handleBack(): Boolean {
        if (!isOnButtonPage()) return false
        showList()
        return true
    }
}

/**
 * Wires up the Elements sidebar declared in activity_sketch.xml (elementsSideSheet): fills the
 * category list under the "Elements" header, hooks the header's (x), and swaps to the Button
 * setup page when "Buttons" is tapped. The sidebar has two pages - the category list
 * (elementsListPage) and the Button setup (elementsButtonPage) - and only one is visible at a time.
 *
 * The other categories have no content yet, so tapping them just says so.
 */
fun bindElementsSidebar(
    sidebar: View,
    onClose: () -> Unit,
    onAddButtonToCanvasRequested: (ButtonStyle, String) -> Unit = { _, _ -> },
    // Checked fresh every time the Button page is opened: if it returns an already-placed FRAMED
    // button, the page opens in "edit" mode for that exact part instead of composing a new one,
    // and onButtonLiveEdited keeps it in sync in real time as Align/Label/style change.
    getEditableSelectedButton: () -> SketchPart? = { null },
    onButtonLiveEdited: (SketchPart, ButtonStyle, String, String) -> Unit = { _, _, _, _ -> },
): ElementsSidebarController {
    val context = sidebar.context
    val inflater = LayoutInflater.from(context)
    val listPage = sidebar.findViewById<View>(R.id.elementsListPage)
    val buttonPage = sidebar.findViewById<FrameLayout>(R.id.elementsButtonPage)
    val categoryList = sidebar.findViewById<LinearLayout>(R.id.elementsCategoryList)

    sidebar.findViewById<View>(R.id.elementsSidebarClose).setOnClickListener { onClose() }

    var onButtonPage = false

    fun showList() {
        onButtonPage = false
        buttonPage.visibility = View.GONE
        buttonPage.removeAllViews()
        listPage.visibility = View.VISIBLE
    }

    // Built fresh on every open (and torn down by showList()), so the instance counter only
    // advances when the Button page is actually opened and each open starts from clean state.
    fun showButtonPage() {
        onButtonPage = true
        listPage.visibility = View.GONE
        buttonPage.removeAllViews()
        val instanceNumber = ButtonInstanceCounter.nextInstanceNumber(context)
        // Re-checked on every open, so selecting a different placed button on the Canvas before
        // reopening this page edits that one instead.
        val editingPart = getEditableSelectedButton()
        val page = buildButtonCategoryPanel(
            context = context,
            initialLabel = "Button $instanceNumber",
            initialVariant = editingPart?.buttonStyle ?: ButtonStyle.FRAMELESS,
            initialAlign = editingPart?.buttonAlign ?: "Centered",
            initialButtonText = editingPart?.label?.takeIf { it.isNotBlank() } ?: "Button",
            onLiveChanged = { style, align, text ->
                editingPart?.let { onButtonLiveEdited(it, style, align, text) }
            },
            // (x) closes the whole sidebar; the next open starts back on the category list.
            onClose = {
                showList()
                onClose()
            },
            // Back arrow: back to the category list without closing the sidebar.
            onBack = { showList() },
            onAddToCanvasRequested = onAddButtonToCanvasRequested,
            compact = true,
        )
        // Scrollable so the settings stay reachable when the keyboard is up or the screen is short.
        buttonPage.addView(
            ScrollView(context).apply {
                isFillViewport = true
                clipChildren = false
                clipToPadding = false
                addView(page.root)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        buttonPage.visibility = View.VISIBLE
    }

    for (cat in ELEMENT_CATEGORIES) {
        val row = inflater.inflate(R.layout.item_elements_category, categoryList, false)
        row.findViewById<ImageView>(R.id.elementsItemIcon).setImageResource(cat.iconRes)
        row.findViewById<TextView>(R.id.elementsItemLabel).text = cat.label
        row.setOnClickListener {
            if (cat.id == CATEGORY_BUTTONS) {
                showButtonPage()
            } else {
                Toast.makeText(context, "${cat.label} isn't available yet", Toast.LENGTH_SHORT).show()
            }
        }
        categoryList.addView(row)
    }

    return ElementsSidebarController(showList = { showList() }, isOnButtonPage = { onButtonPage })
}
