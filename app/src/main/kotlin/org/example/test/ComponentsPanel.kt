package org.example.test

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Elements panel palette (light mode): soft-gray surface with a lime-green secondary/accent color.
internal val PANEL_BG = Color.parseColor("#F3F4F6")
internal val PANEL_PRIMARY_TEXT = Color.parseColor("#1A1B24")
internal val PANEL_SECONDARY_TEXT = Color.parseColor("#6B7280")
internal val PANEL_NEUTRAL_TEXT = Color.parseColor("#9CA3AF")
internal val PANEL_DIVIDER = Color.parseColor("#D1D5DB")
internal val PANEL_ACCENT = Color.parseColor("#355E3B")

// Selection-frame blue for the two Buttons-panel previews in canvasContentRow (see
// buildButtonCategoryPanel()) - standard Material blue, distinct from the panel's own
// hunter-green/purple accents so the "this one's selected" signal stays unambiguous.
private val BUTTON_PREVIEW_SELECTED_BORDER = Color.parseColor("#2196F3")
// Selected-state frame color for the Design/Prototype toggle - matches the app's existing
// blue accent (see bg_tab_selected) rather than the sidebar's hunter-green accent.
internal val PANEL_MODE_SELECTED = Color.parseColor("#3D7EFF")

internal data class ComponentCategory(val id: String, val label: String, val iconRes: Int)
private data class GeometrySubCategory(val label: String, val iconRes: Int, val children: List<String>)

private val GEOMETRY_SUBCATEGORIES = listOf(
    GeometrySubCategory("Create", R.drawable.ic_geo_create, listOf("Point", "Line", "Curve", "2D Shape", "3D Primitive", "Custom Mesh")),
    GeometrySubCategory("Edit", R.drawable.ic_geo_edit, listOf("Vertex", "Edge", "Face", "Path", "Control Point")),
    GeometrySubCategory("Transform", R.drawable.ic_geo_transform, listOf("Move", "Rotate", "Scale", "Skew", "Pivot")),
    GeometrySubCategory("Deform", R.drawable.ic_geo_deform, listOf("Bend", "Twist", "Taper", "Warp", "Freeform")),
    GeometrySubCategory("Animate", R.drawable.ic_geo_animate, listOf("Keyframe", "Motion Path", "Constraint", "Timeline", "Graph Editor")),
)

internal val ALL_CATEGORY = ComponentCategory("all", "All", R.drawable.ic_components)

internal val COMPONENT_CATEGORIES = listOf(
    ComponentCategory("structure", "Buttons", R.drawable.ic_cat_buttons),
    ComponentCategory("layout", "Layout", R.drawable.ic_cat_layout),
    ComponentCategory("typography", "Typography", R.drawable.ic_cat_typography),
    ComponentCategory("shapes", "Geometry", R.drawable.ic_cat_geometry),
    ComponentCategory("media", "Media", R.drawable.ic_media),
    ComponentCategory("navigation", "Navigation", R.drawable.ic_cat_navigation),
    ComponentCategory("input", "Input", R.drawable.ic_cat_input),
    ComponentCategory("actions", "Actions", R.drawable.ic_cat_actions),
    ComponentCategory("content", "Content", R.drawable.ic_cat_content),
    ComponentCategory("feedback", "Feedback", R.drawable.ic_cat_feedback),
    ComponentCategory("mobile", "Mobile", R.drawable.ic_cat_mobile),
    ComponentCategory("prototype", "Prototype", R.drawable.ic_cat_prototype),
)

internal val COMPONENT_ITEMS: Map<String, List<String>> = mapOf(
    // "structure" is the "Buttons" category (see COMPONENT_CATEGORIES below). It now opens a
    // dedicated full-panel Button experience (see buildButtonCategoryPanel()) instead of the
    // generic item grid, so it carries a single real item rather than the placeholder
    // Frame/Section/Container/Group set it used to have.
    "structure" to listOf("Button"),
    "layout" to listOf("Row", "Column", "Grid", "Stack"),
    "typography" to listOf("Heading", "Body Text", "Caption", "Label"),
    "shapes" to listOf("Rectangle", "Ellipse", "Line", "Polygon"),
    "media" to listOf("Image", "Video", "Icon", "Avatar"),
    "navigation" to listOf("Top App Bar", "Bottom Nav", "Tab Bar", "Drawer"),
    "input" to listOf("Text Field", "Checkbox", "Radio Button", "Switch"),
    "actions" to listOf("Button", "Icon Button", "FAB", "Chip"),
    "content" to listOf("Card", "List Item", "Divider", "Badge"),
    "feedback" to listOf("Snackbar", "Dialog", "Progress Bar", "Tooltip"),
    "mobile" to listOf("Status Bar", "Bottom Sheet", "Segmented Control", "Pull to Refresh"),
    "prototype" to listOf("Hotspot", "Overlay", "Transition", "Scroll Group"),
)

internal fun buildGeometrySidebarTree(context: Context): Pair<View, () -> Unit> {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()



    val primaryText = PANEL_PRIMARY_TEXT
    val secondaryText = PANEL_SECONDARY_TEXT
    val neutralText = PANEL_NEUTRAL_TEXT
    val activeBlue = PANEL_ACCENT
    val guideLineColor = PANEL_DIVIDER

    val rowHeightL2 = dp(48)
    val rowHeightL3 = dp(42)
    val outerPadding = dp(16)


    val iconSize = dp(18)
    val iconLabelGap = dp(14)
    val chevronSize = dp(16)
    val rowPaddingStartL2 = outerPadding - dp(2)
    val accentBarWidth = dp(3)
    val accentBarHeight = dp(18)


    val l3Offset = rowPaddingStartL2 + iconSize + iconLabelGap + dp(24)

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clipChildren = false
        clipToPadding = false
    }

    data class SubRow(
        val row: LinearLayout,
        val icon: ImageView,
        val accentBar: View,
        val chevron: ImageView,
        val labelView: TextView,
        val childrenContainer: LinearLayout
    )
    val rows = mutableListOf<SubRow>()
    var expandedIndex: Int? = null

    fun setChevronExpanded(chevron: ImageView, expanded: Boolean) {
        chevron.animate().cancel()
        chevron.animate().rotation(if (expanded) 270f else 180f).setDuration(150L).start()
    }




    val accentSlideStartOffset = dp(2)






    val activeLeafIndex = mutableMapOf<Int, Int>()
    GEOMETRY_SUBCATEGORIES.indexOfFirst { it.label == "Animate" }.takeIf { it >= 0 }?.let { animateIdx ->
        val keyframeChildIdx = GEOMETRY_SUBCATEGORIES[animateIdx].children.indexOf("Keyframe")
        if (keyframeChildIdx >= 0) activeLeafIndex[animateIdx] = keyframeChildIdx
    }












    fun setAccentSelected(
        accentBar: View,
        label: TextView,
        selected: Boolean,
        animate: Boolean,
        slide: Boolean,
        activeTextColor: Int,
        inactiveTextColor: Int,
        icon: ImageView? = null,
        activeIconColor: Int = activeTextColor,
        inactiveIconColor: Int = inactiveTextColor
    ) {
        label.setTextColor(if (selected) activeTextColor else inactiveTextColor)
        label.setTypeface(label.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        icon?.setColorFilter(if (selected) activeIconColor else inactiveIconColor)
        accentBar.animate().cancel()
        when {
            !animate -> {
                accentBar.setBackgroundColor(if (selected) activeBlue else Color.TRANSPARENT)
                accentBar.translationX = 0f
                accentBar.alpha = 1f
            }
            selected && slide -> {
                val textWidth = label.paint.measureText(label.text.toString())
                accentBar.setBackgroundColor(activeBlue)
                accentBar.alpha = 1f
                accentBar.translationX = textWidth + accentSlideStartOffset
                accentBar.animate()
                    .translationX(0f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            selected && !slide -> {
                accentBar.setBackgroundColor(activeBlue)
                accentBar.translationX = 0f
                accentBar.alpha = 1f
            }
            !selected && slide -> {
                val textWidth = label.paint.measureText(label.text.toString())
                accentBar.animate()
                    .translationX(textWidth + accentSlideStartOffset)
                    .alpha(0f)
                    .setDuration(180L)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        accentBar.setBackgroundColor(Color.TRANSPARENT)
                        accentBar.translationX = 0f
                        accentBar.alpha = 1f
                    }
                    .start()
            }
            else -> {
                accentBar.animate()
                    .alpha(0f)
                    .setDuration(120L)
                    .withEndAction {
                        accentBar.setBackgroundColor(Color.TRANSPARENT)
                        accentBar.translationX = 0f
                        accentBar.alpha = 1f
                    }
                    .start()
            }
        }
    }






    fun buildLeafList(subIndex: Int): LinearLayout {
        val sub = GEOMETRY_SUBCATEGORIES[subIndex]
        data class LeafRow(val accentBar: View, val label: TextView)
        val leafRows = mutableListOf<LeafRow>()
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            sub.children.forEachIndexed { childIndex, childLabel ->
                lateinit var accentBar: View
                lateinit var label: TextView
                val isActive = activeLeafIndex[subIndex] == childIndex
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(12), 0, outerPadding, 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightL3)


                    accentBar = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(3), dp(18)).apply { marginEnd = dp(10) }
                    }
                    addView(accentBar)
                    label = TextView(context).apply {
                        text = childLabel
                        textSize = 12.5f
                        maxLines = 1
                    }
                    addView(label)
                    setAccentSelected(
                        accentBar, label, selected = isActive, animate = false, slide = true,
                        activeTextColor = primaryText, inactiveTextColor = neutralText
                    )
                    setOnClickListener {
                        val current = activeLeafIndex[subIndex]
                        if (current == childIndex) {


                            setAccentSelected(
                                accentBar, label, selected = false, animate = true, slide = true,
                                activeTextColor = primaryText, inactiveTextColor = neutralText
                            )
                            activeLeafIndex.remove(subIndex)
                        } else {



                            current?.let { prevChildIndex ->
                                leafRows.getOrNull(prevChildIndex)?.let { prevRow ->
                                    setAccentSelected(
                                        prevRow.accentBar, prevRow.label, selected = false, animate = true, slide = false,
                                        activeTextColor = primaryText, inactiveTextColor = neutralText
                                    )
                                }
                            }
                            setAccentSelected(
                                accentBar, label, selected = true, animate = true, slide = true,
                                activeTextColor = primaryText, inactiveTextColor = neutralText
                            )
                            activeLeafIndex[subIndex] = childIndex
                        }
                    }
                })
                leafRows.add(LeafRow(accentBar, label))
            }
        }
        val guideLine = View(context).apply {
            setBackgroundColor(guideLineColor)
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = l3Offset
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
            addView(guideLine)
            addView(textColumn)
        }
    }

    fun collapse(index: Int, animate: Boolean, slide: Boolean = true) {
        val entry = rows[index]
        setChevronExpanded(entry.chevron, false)
        entry.childrenContainer.animate().cancel()
        if (animate) {
            entry.childrenContainer.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction {
                    entry.childrenContainer.visibility = View.GONE
                    entry.childrenContainer.removeAllViews()
                }
                .start()
        } else {
            entry.childrenContainer.visibility = View.GONE
            entry.childrenContainer.alpha = 0f
            entry.childrenContainer.removeAllViews()
        }



        setAccentSelected(
            accentBar = entry.accentBar,
            label = entry.labelView,
            selected = false,
            animate = animate,
            slide = slide,
            activeTextColor = primaryText,
            inactiveTextColor = secondaryText,
            icon = entry.icon,
            activeIconColor = primaryText,
            inactiveIconColor = secondaryText
        )
    }

    fun expand(index: Int, animate: Boolean = true) {
        val entry = rows[index]
        setChevronExpanded(entry.chevron, true)
        entry.childrenContainer.removeAllViews()
        entry.childrenContainer.addView(buildLeafList(index))
        entry.childrenContainer.animate().cancel()
        entry.childrenContainer.visibility = View.VISIBLE
        entry.childrenContainer.alpha = 0f
        entry.childrenContainer.animate().alpha(1f).setDuration(150L).start()


        setAccentSelected(
            accentBar = entry.accentBar,
            label = entry.labelView,
            selected = true,
            animate = animate,
            slide = true,
            activeTextColor = primaryText,
            inactiveTextColor = secondaryText,
            icon = entry.icon,
            activeIconColor = primaryText,
            inactiveIconColor = secondaryText
        )
    }

    fun toggle(index: Int) {
        val current = expandedIndex
        if (current == index) {


            collapse(index, animate = true, slide = true)
            expandedIndex = null
        } else {



            current?.let { collapse(it, animate = true, slide = false) }
            expand(index)
            expandedIndex = index
        }
    }

    for ((index, sub) in GEOMETRY_SUBCATEGORIES.withIndex()) {
        lateinit var icon: ImageView
        lateinit var accentBar: View
        lateinit var chevron: ImageView
        lateinit var label: TextView
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(rowPaddingStartL2, 0, outerPadding, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightL2)
            icon = ImageView(context).apply {
                setImageResource(sub.iconRes)
                setColorFilter(secondaryText)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)



                if (sub.label == "Deform") {
                    setPadding(dp(1), dp(1), dp(1), dp(1))
                }
            }
            addView(icon)


            accentBar = View(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(accentBarWidth, accentBarHeight).apply {
                    marginStart = iconLabelGap
                    marginEnd = dp(10)
                }
            }
            addView(accentBar)
            label = TextView(context).apply {
                text = sub.label
                setTextColor(secondaryText)
                textSize = 11f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)
            chevron = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(neutralText)
                rotation = 180f
                layoutParams = LinearLayout.LayoutParams(chevronSize, chevronSize)
            }
            addView(chevron)
        }
        val childrenContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(row)
        root.addView(childrenContainer)
        rows.add(SubRow(row, icon, accentBar, chevron, label, childrenContainer))
        row.setOnClickListener { toggle(index) }
    }



    fun applyDefaultState() {
        rows.indices.forEach { idx -> collapse(idx, animate = false) }
        expandedIndex = null
        val animateIndex = GEOMETRY_SUBCATEGORIES.indexOfFirst { it.label == "Animate" }
        if (animateIndex >= 0) {
            expand(animateIndex, animate = false)
            expandedIndex = animateIndex
        }
    }
    applyDefaultState()

    fun reset() {
        rows.forEach { it.row.animate().cancel(); it.chevron.animate().cancel(); it.accentBar.animate().cancel() }
        applyDefaultState()
    }






    val treeGuideLine = View(context).apply {
        setBackgroundColor(guideLineColor)
        layoutParams = FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = rowPaddingStartL2 + iconSize / 2
        }
    }
    val tree = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(treeGuideLine)
        addView(root)
    }

    return tree to ::reset
}

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
 * Builds the dedicated, Button-only panel that replaces the normal category rail + content grid
 * when "Buttons" is selected from the Elements panel's sidebar (see buildComponentsContent()'s
 * "structure" rail entry). Light-mode, matches the rest of the Elements panel's palette.
 *
 * - Top-left: the Button instance's name ("Button {N}"), renamable inline exactly like the
 *   Screens panel's header title (see buildScreensPanelContent() in ScreensPanel.kt) - tapping it
 *   turns it into an editable field, committing a non-empty trimmed value fires [onLabelRenamed].
 * - Top-right: a frameless (x) that closes the whole Elements panel via [onClose].
 * - Body: a single Button preview; tapping it fires [onExpandRequested] to max out the Elements
 *   panel's height. This is presentation-only for now - it doesn't place anything on the canvas.
 */
fun buildButtonCategoryPanel(
    context: Context,
    initialLabel: String,
    onClose: () -> Unit,
    onExpandRequested: () -> Unit,
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
): ButtonCategoryPanelViews {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(PANEL_BG)
        // Top padding kept small so the name/(x) row sits right up near the panel's top edge,
        // but not 0 - the panel's bg (bg_bottom_panel.xml) has a 24dp top corner radius, so a
        // little clearance keeps the row clear of that curve instead of clipping into it.
        setPadding(dp(20), dp(8), dp(20), dp(20))
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
        // draw into that padding). Height stays content-sized (wrap_content) rather than being
        // forced to match the width, so this is a rectangle now, not a square.
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            marginStart = -dp(20)
            marginEnd = -dp(20)
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
        setPadding(dp(14), 0, dp(14), 0)
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
            textSize = 9f
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
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

/**
 * Handle returned by [buildComponentsContent]. The Elements *sidebar* (see ElementsSidebarView)
 * owns category navigation; this content (search row + category content, or the dedicated
 * Buttons panel) lives in the bottom sheet and is driven from the sidebar through [showCategory].
 */
internal class ComponentsContentHandle(
    val view: View,
    /** Shows the content for [ALL_CATEGORY] ("all") or one of [COMPONENT_CATEGORIES]' ids. */
    val showCategory: (String) -> Unit,
)

internal fun buildComponentsContent(
    context: Context,
    onClose: () -> Unit = {},
    onButtonPanelExpandRequested: () -> Unit = {},
    onAddButtonToCanvasRequested: (ButtonStyle, String) -> Unit = { _, _ -> },
    // Checked fresh every time the Buttons panel is opened (see showButtonCategoryPanel() below):
    // if it returns an already-placed FRAMED button, the panel opens in "edit" mode for that
    // exact part instead of composing a new one, and onButtonLiveEdited below keeps it in sync in
    // real time as Align/Label/style change.
    getEditableSelectedButton: () -> SketchPart? = { null },
    onButtonLiveEdited: (SketchPart, ButtonStyle, String, String) -> Unit = { _, _, _, _ -> },
    // The sheet's search field filters the category sections; this reports which category ids
    // still match (null = no active query) so the sidebar can dim the rest.
    onSearchFilterChanged: (Set<String>?) -> Unit = {},
    // Fired when the content moves itself to another category (e.g. the Buttons panel's back
    // arrow returns to "all"), so the sidebar's selection can follow.
    onActiveCategoryChanged: (String) -> Unit = {},
): ComponentsContentHandle {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Keeps the header row (back/search/filter) from sitting flush against the panel's
        // top edge, most noticeable when elementsPanel is at its collapsed/minimum peek height.
        setPadding(0, dp(16), 0, 0)

        clipChildren = false
        clipToPadding = false
    }



    val closeButton = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
        isClickable = true
        isFocusable = true
        contentDescription = "Back"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back_return)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
        setOnClickListener { onClose() }
    }


    val searchInput = EditText(context).apply {
        hint = "Search components"
        setHintTextColor(PANEL_NEUTRAL_TEXT)
        setTextColor(PANEL_PRIMARY_TEXT)
        textSize = 14f
        setSingleLine(true)
        background = null
        setPadding(dp(10), dp(10), dp(4), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val searchField = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_properties_card)
        setPadding(dp(12), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        addView(searchInput)
    }
    val filterButton = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.bg_quick_action_item_pressed)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_filter)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        })
        setOnClickListener { Toast.makeText(context, "Filters aren't available yet", Toast.LENGTH_SHORT).show() }
    }
    val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        }
        setPadding(dp(8), 0, dp(16), 0)
        addView(closeButton)
        addView(searchField)
        addView(filterButton)
    }
    root.addView(headerRow)

    val sectionViews = mutableMapOf<String, View>()

    val sectionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(2), dp(16), dp(28))
        clipChildren = false
        clipToPadding = false
    }
    val contentScroll = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        clipChildren = false
        clipToPadding = false
        addView(sectionsContainer)
    }




    val emptyCategoryView = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }
    val contentBody = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(contentScroll)
        addView(emptyCategoryView)
    }

    // Design/Prototype mode toggle - sits above the content body, below the search row. Purely visual for now: switching between the two
    // just moves which one carries the solid-blue selected shading; it doesn't filter or
    // change contentBody's content yet.
    //
    // Segmented-control style: a single light-mode outer frame (white, rounded, drop-shadowed)
    // shared by both segments, with a small inset so each segment's own selected-state
    // background sits inside it rather than each segment carrying its own bordered frame.
    data class ModeFrame(val frame: FrameLayout, val label: TextView)
    fun modeSegmentBackground(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(if (selected) PANEL_MODE_SELECTED else Color.TRANSPARENT)
    }
    val modeFrames = mutableListOf<ModeFrame>()
    fun setActiveMode(index: Int) {
        modeFrames.forEachIndexed { i, mf ->
            val selected = i == index
            mf.frame.background = modeSegmentBackground(selected)
            mf.label.setTextColor(if (selected) Color.WHITE else PANEL_SECONDARY_TEXT)
            mf.label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }
    fun buildModeFrame(text: String): ModeFrame {
        val label = TextView(context).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
            isClickable = true
            isFocusable = true
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        return ModeFrame(frame, label).also { modeFrames.add(it) }
    }
    val designFrame = buildModeFrame("Design")
    val prototypeFrame = buildModeFrame("Prototype")
    designFrame.frame.setOnClickListener { setActiveMode(0) }
    prototypeFrame.frame.setOnClickListener { setActiveMode(1) }
    // The shared outer frame: white/light background, rounded corners, and a real drop shadow
    // via elevation (GradientDrawable's rounded-rect outline gives the shadow something to
    // cast against, so no manual shadow-layer view is needed here).
    val modeSharedFrameBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(Color.WHITE)
    }
    val modeToggleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        background = modeSharedFrameBg
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        elevation = dp(3).toFloat()
        setPadding(dp(3), dp(3), dp(3), dp(3))
        clipToPadding = false
        clipChildren = false
        addView(designFrame.frame, LinearLayout.LayoutParams(0, dp(32), 1f))
        addView(FrameLayout(context).apply { layoutParams = LinearLayout.LayoutParams(dp(4), dp(1)) })
        addView(prototypeFrame.frame, LinearLayout.LayoutParams(0, dp(32), 1f))
        // Hidden by default: the panel opens on "All", where the toggle doesn't apply.
        visibility = View.GONE
    }
    setActiveMode(0)

    val contentContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        clipChildren = false
        clipToPadding = false
        addView(modeToggleRow)
        addView(contentBody)
    }
    fun showAllContent() {
        emptyCategoryView.visibility = View.GONE
        contentScroll.visibility = View.VISIBLE
        modeToggleRow.visibility = View.GONE
    }
    fun showEmptyCategoryContent() {
        contentScroll.visibility = View.GONE
        emptyCategoryView.visibility = View.VISIBLE
        modeToggleRow.visibility = View.VISIBLE
    }

    for (cat in COMPONENT_CATEGORIES) {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(22)
            }
            clipChildren = false
            clipToPadding = false
        }
        section.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            addView(TextView(context).apply {
                text = cat.label
                setTextColor(PANEL_PRIMARY_TEXT)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_more_horiz)
                setColorFilter(PANEL_SECONDARY_TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener {
                    Toast.makeText(context, "${cat.label} options aren't available yet", Toast.LENGTH_SHORT).show()
                }
            })
        })
        section.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clipChildren = false
            clipToPadding = false
            val itemNames = COMPONENT_ITEMS[cat.id].orEmpty()



            var selectedIndex: Int? = null
            val itemContainers = mutableListOf<LinearLayout>()
            val labelViews = mutableListOf<TextView>()
            val iconBoxViews = mutableListOf<View>()

            val staggerStepMs = 50L
            val animDurationMs = 220L
            val liftDistancePx = dp(56).toFloat()

            fun resetAllImmediate() {
                itemContainers.forEach { c ->
                    c.animate().cancel()
                    c.visibility = View.VISIBLE
                    c.alpha = 1f
                    c.translationY = 0f
                }
                labelViews.forEach { l ->
                    l.animate().cancel()
                    l.visibility = View.VISIBLE
                    l.alpha = 1f
                }
                iconBoxViews.forEach { it.setBackgroundResource(R.drawable.bg_component_placeholder) }
            }



            fun animateSelect(index: Int) {
                itemContainers.forEachIndexed { idx, container ->
                    if (idx == index) return@forEachIndexed
                    val delay = kotlin.math.abs(idx - index) * staggerStepMs



                    container.animate().cancel()
                    container.visibility = View.VISIBLE
                    container.animate()
                        .translationY(-liftDistancePx)
                        .alpha(0f)
                        .setStartDelay(delay)
                        .setDuration(animDurationMs)
                        .setInterpolator(AccelerateInterpolator())



                        .withEndAction { if (container.alpha == 0f) container.visibility = View.GONE }
                        .start()
                }
                val selectedLabel = labelViews[index]
                selectedLabel.animate().cancel()
                selectedLabel.visibility = View.VISIBLE
                selectedLabel.animate()
                    .alpha(0f)
                    .setDuration(animDurationMs)
                    .withEndAction { if (selectedLabel.alpha == 0f) selectedLabel.visibility = View.GONE }
                    .start()

                iconBoxViews[index].background = null
            }



            fun animateRestore(index: Int) {
                itemContainers.forEachIndexed { idx, container ->
                    if (idx == index) return@forEachIndexed
                    val delay = kotlin.math.abs(idx - index) * staggerStepMs



                    container.animate().cancel()
                    container.visibility = View.VISIBLE
                    container.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setStartDelay(delay)
                        .setDuration(animDurationMs)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction(null)
                        .start()
                }
                val selectedLabel = labelViews[index]
                selectedLabel.animate().cancel()
                selectedLabel.visibility = View.VISIBLE
                selectedLabel.alpha = 0f
                selectedLabel.animate()
                    .alpha(1f)
                    .setDuration(animDurationMs)
                    .withEndAction(null)
                    .start()

                iconBoxViews[index].setBackgroundResource(R.drawable.bg_component_placeholder)
            }

            // Was hardcoded to 4 - every category used to carry exactly 4 items. "structure"
            // (Buttons) now carries just 1, so this renders however many items the category
            // actually has instead of padding out to 4 with placeholder "Component N" labels.
            for (i in itemNames.indices) {
                lateinit var label: TextView
                lateinit var iconBox: View
                val itemContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i != 0) marginStart = dp(8)
                    }





                    iconBox = View(context).apply {
                        setBackgroundResource(R.drawable.bg_component_placeholder)
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    }
                    addView(iconBox)

                    label = TextView(context).apply {
                        text = itemNames.getOrElse(i) { "Component ${i + 1}" }
                        setTextColor(PANEL_SECONDARY_TEXT)
                        textSize = 10.5f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                        }
                    }
                    addView(label)
                }
                itemContainers.add(itemContainer)
                labelViews.add(label)
                iconBoxViews.add(iconBox)
                addView(itemContainer)
            }

            itemContainers.forEachIndexed { index, container ->
                container.setOnClickListener {
                    when (selectedIndex) {
                        index -> {

                            animateRestore(index)
                            selectedIndex = null
                        }
                        null -> {
                            animateSelect(index)
                            selectedIndex = index
                        }
                        else -> {


                            resetAllImmediate()
                            animateSelect(index)
                            selectedIndex = index
                        }
                    }
                }
            }
        })
        sectionsContainer.addView(section)
        sectionViews[cat.id] = section
    }

    root.addView(contentContainer)


    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val query = s?.toString()?.trim()?.lowercase().orEmpty()
            val matching = mutableSetOf<String>()
            for (cat in COMPONENT_CATEGORIES) {
                val matches = query.isEmpty() ||
                    cat.label.lowercase().contains(query) ||
                    COMPONENT_ITEMS[cat.id].orEmpty().any { it.lowercase().contains(query) }
                if (matches) matching.add(cat.id)
                sectionViews[cat.id]?.visibility = if (matches) View.VISIBLE else View.GONE
            }
            onSearchFilterChanged(if (query.isEmpty()) null else matching)
        }
    })

    // Host for the dedicated Button-only panel (see buildButtonCategoryPanel()). It takes over
    // the whole panel - header row and content area both hidden - rather than sitting inside
    // contentBody, since "Buttons" gets a full replacement panel rather than just a new content
    // section. Built lazily so the instance counter only advances when "Buttons" is actually
    // opened, and torn down on close so the next open gets a fresh instance number.
    val buttonPanelHost = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }
    root.addView(buttonPanelHost)

    fun showNormalContent() {
        buttonPanelHost.visibility = View.GONE
        buttonPanelHost.removeAllViews()
        headerRow.visibility = View.VISIBLE
        contentContainer.visibility = View.VISIBLE
    }

    fun showButtonCategoryPanel() {
        headerRow.visibility = View.GONE
        contentContainer.visibility = View.GONE
        buttonPanelHost.removeAllViews()
        val instanceNumber = ButtonInstanceCounter.nextInstanceNumber(context)
        // Re-checked on every open (this whole panel is torn down and rebuilt each time "Buttons"
        // is selected in the sidebar - see showNormalContent()/onClose() below), so selecting a
        // different placed button on the Canvas before reopening this panel edits that one instead.
        val editingPart = getEditableSelectedButton()
        val panel = buildButtonCategoryPanel(
            context = context,
            initialLabel = "Button $instanceNumber",
            initialVariant = editingPart?.buttonStyle ?: ButtonStyle.FRAMELESS,
            initialAlign = editingPart?.buttonAlign ?: "Centered",
            initialButtonText = editingPart?.label?.takeIf { it.isNotBlank() } ?: "Button",
            onLiveChanged = { style, align, text ->
                editingPart?.let { onButtonLiveEdited(it, style, align, text) }
            },
            onClose = {
                // Closing the Button panel closes the whole Elements panel (same as the normal
                // header's back/close button); the sidebar clears its selection when the sheet
                // hides, and the next open starts fresh (and the counter advances again).
                showNormalContent()
                showAllContent()
                onClose()
            },
            onExpandRequested = onButtonPanelExpandRequested,
            onAddToCanvasRequested = onAddButtonToCanvasRequested,
            onBack = {
                // Same reset as onClose above, but without dismissing the whole Elements panel -
                // falls back to the "All" view so another category can be picked in the sidebar.
                showNormalContent()
                showAllContent()
                onActiveCategoryChanged(ALL_CATEGORY.id)
            },
        )
        buttonPanelHost.addView(panel.root)
        buttonPanelHost.visibility = View.VISIBLE
    }

    // Also serves the sheet's first open: the default state is "all", built above.
    return ComponentsContentHandle(root) { categoryId ->
        when (categoryId) {
            ALL_CATEGORY.id -> {
                showNormalContent()
                showAllContent()
                contentScroll.post { contentScroll.smoothScrollTo(0, 0) }
            }
            // "Buttons" takes over the whole sheet with its dedicated panel.
            "structure" -> showButtonCategoryPanel()
            else -> {
                showNormalContent()
                showEmptyCategoryContent()
            }
        }
    }
}
