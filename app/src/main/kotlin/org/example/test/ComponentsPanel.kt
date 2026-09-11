package org.example.test

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
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


private data class ComponentCategory(val id: String, val label: String, val iconRes: Int)

// The "Geometry" category (id "shapes" in COMPONENT_CATEGORIES) gets its own dedicated content
// instead of the generic blank placeholder: an accordion of Create/Edit/Transform/Deform/Animate
// sub-sections, each with its own icon, that expand to reveal their leaf items. The leaf items
// have no icons of their own, so they're rendered as text-only rows.
private data class GeometrySubCategory(val label: String, val iconRes: Int, val children: List<String>)

private val GEOMETRY_SUBCATEGORIES = listOf(
    GeometrySubCategory("Create", R.drawable.ic_geo_create, listOf("Point", "Line", "Curve", "2D Shape", "3D Primitive", "Custom Mesh")),
    GeometrySubCategory("Edit", R.drawable.ic_geo_edit, listOf("Vertex", "Edge", "Face", "Path", "Control Point")),
    GeometrySubCategory("Transform", R.drawable.ic_geo_transform, listOf("Move", "Rotate", "Scale", "Skew", "Pivot")),
    GeometrySubCategory("Deform", R.drawable.ic_geo_deform, listOf("Bend", "Twist", "Taper", "Warp", "Freeform")),
    GeometrySubCategory("Animate", R.drawable.ic_geo_animate, listOf("Keyframe", "Motion Path", "Constraint", "Timeline", "Graph Editor")),
)

// Pseudo-category shown at the top of the rail. Selecting it is the default/all-components
// view (every section visible, nothing filtered out) rather than jumping to one category.
private val ALL_CATEGORY = ComponentCategory("all", "All", R.drawable.ic_components)

private val COMPONENT_CATEGORIES = listOf(
    ComponentCategory("structure", "Structure", R.drawable.ic_cat_structure),
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

// Named, empty placeholder components shown inside each category's section. These aren't wired
// up to anything yet (see bg_component_placeholder tiles below) - just labeled slots.
private val COMPONENT_ITEMS: Map<String, List<String>> = mapOf(
    "structure" to listOf("Frame", "Section", "Container", "Group"),
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





















// Dedicated content for the "Geometry" category, rendered directly in the sidebar rail beneath
// its own row (not in the main content pane): a compact three-level navigation tree. Each of
// Create/Edit/Transform/Deform/Animate sits as a second-level row - icon, label, and an
// expand/collapse chevron - directly under Geometry. Tapping one reveals its leaf items
// indented beneath it, offset from the row's own text and marked with a single thin vertical
// guide line running the full height of the list (rather than a mark per leaf). Only one
// second-level row is expanded at a time. "Animate" starts expanded by default with "Keyframe"
// as the active leaf (a small blue accent + brighter text), matching the rail's default state;
// every other leaf uses neutral secondary text. Nothing beyond this active-state styling is
// wired up to real content yet (see onClick below).
// Returns the view plus a `reset` callback that restores this default state, used whenever the
// Geometry category itself is deselected/reselected in the rail.
private fun buildGeometrySidebarTree(context: Context): Pair<View, () -> Unit> {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun selectableForeground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return if (outValue.resourceId != 0) context.getDrawable(outValue.resourceId) else null
    }

    // Palette + metrics shared by every row in the tree - kept in one place so the second and
    // third levels read as clearly related but progressively less emphasized.
    val primaryText = Color.WHITE
    val secondaryText = Color.parseColor("#C7C7CF")
    val neutralText = Color.parseColor("#9A9AA5")
    val activeBlue = Color.parseColor("#3D7EFF")
    val guideLineColor = Color.parseColor("#3A3A42")

    val rowHeightL2 = dp(48)
    val rowHeightL3 = dp(42)
    val outerPadding = dp(16)
    val iconSize = dp(20)
    val iconLabelGap = dp(14)
    val chevronSize = dp(16)
    // Where the leaf list sits, measured from the sidebar's left edge: past the second-level
    // row's own icon + label start, plus a further ~24px so it reads as a nested level.
    val l3Offset = outerPadding + iconSize + iconLabelGap + dp(24)

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clipChildren = false
        clipToPadding = false
    }

    data class SubRow(val row: LinearLayout, val chevron: ImageView, val labelView: TextView, val childrenContainer: LinearLayout)
    val rows = mutableListOf<SubRow>()
    var expandedIndex: Int? = null

    fun setChevronExpanded(chevron: ImageView, expanded: Boolean) {
        chevron.animate().cancel()
        chevron.animate().rotation(if (expanded) 270f else 180f).setDuration(150L).start()
    }

    // Builds one subcategory's leaf list: a single continuous vertical guide line running the
    // full height of the list, with the leaf rows in a text column beside it.
    fun buildLeafList(subIndex: Int): LinearLayout {
        val sub = GEOMETRY_SUBCATEGORIES[subIndex]
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            for (childLabel in sub.children) {
                val isActive = sub.label == "Animate" && childLabel == "Keyframe"
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    foreground = selectableForeground()
                    setPadding(dp(12), 0, outerPadding, 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightL3)
                    // Restrained active-state indicator: a short blue bar rather than a filled
                    // background, so only one accent marks the active leaf.
                    addView(View(context).apply {
                        setBackgroundColor(if (isActive) activeBlue else Color.TRANSPARENT)
                        layoutParams = LinearLayout.LayoutParams(dp(3), dp(18)).apply { marginEnd = dp(10) }
                    })
                    addView(TextView(context).apply {
                        text = childLabel
                        setTextColor(if (isActive) primaryText else neutralText)
                        textSize = 12.5f
                        maxLines = 1
                    })
                    // Content intentionally left blank for now - no detail view wired up yet.
                    setOnClickListener { }
                })
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

    fun collapse(index: Int, animate: Boolean) {
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
        entry.labelView.setTextColor(secondaryText)
    }

    fun expand(index: Int) {
        val entry = rows[index]
        setChevronExpanded(entry.chevron, true)
        entry.childrenContainer.removeAllViews()
        entry.childrenContainer.addView(buildLeafList(index))
        entry.childrenContainer.animate().cancel()
        entry.childrenContainer.visibility = View.VISIBLE
        entry.childrenContainer.alpha = 0f
        entry.childrenContainer.animate().alpha(1f).setDuration(150L).start()
        entry.labelView.setTextColor(primaryText)
    }

    fun toggle(index: Int) {
        val current = expandedIndex
        if (current == index) {
            collapse(index, animate = true)
            expandedIndex = null
        } else {
            current?.let { collapse(it, animate = true) }
            expand(index)
            expandedIndex = index
        }
    }

    for ((index, sub) in GEOMETRY_SUBCATEGORIES.withIndex()) {
        lateinit var chevron: ImageView
        lateinit var label: TextView
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            setPadding(outerPadding, 0, outerPadding, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightL2)
            addView(ImageView(context).apply {
                setImageResource(sub.iconRes)
                setColorFilter(secondaryText)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            label = TextView(context).apply {
                text = sub.label
                setTextColor(secondaryText)
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = iconLabelGap
                }
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
        rows.add(SubRow(row, chevron, label, childrenContainer))
        row.setOnClickListener { toggle(index) }
    }

    // Default state: everything collapsed except "Animate", which starts expanded with
    // "Keyframe" active - this is what the rail shows the moment Geometry is selected.
    fun applyDefaultState() {
        rows.indices.forEach { idx -> collapse(idx, animate = false) }
        expandedIndex = null
        val animateIndex = GEOMETRY_SUBCATEGORIES.indexOfFirst { it.label == "Animate" }
        if (animateIndex >= 0) {
            expand(animateIndex)
            expandedIndex = animateIndex
        }
    }
    applyDefaultState()

    fun reset() {
        rows.forEach { it.row.animate().cancel(); it.chevron.animate().cancel() }
        applyDefaultState()
    }

    return root to ::reset
}

fun buildComponentsContent(context: Context, onClose: () -> Unit = {}): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun selectableForeground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return if (outValue.resourceId != 0) context.getDrawable(outValue.resourceId) else null
    }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Left un-clipped so the selected level-1 row's background (frameBg, see buildRailRow)
        // can bleed all the way out to the panel's true edge instead of stopping at this
        // container's own bounds.
        clipChildren = false
        clipToPadding = false
    }

    
    
    val closeButton = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
        isClickable = true
        isFocusable = true
        foreground = selectableForeground()
        contentDescription = "Back"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back_return)
            setColorFilter(Color.parseColor("#9A9AA5"))
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
        setOnClickListener { onClose() }
    }

    
    val searchInput = EditText(context).apply {
        hint = "Search components"
        setHintTextColor(Color.parseColor("#6F707A"))
        setTextColor(Color.WHITE)
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
            setColorFilter(Color.parseColor("#6F707A"))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        addView(searchInput)
    }
    val filterButton = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.bg_quick_action_item_pressed)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_filter)
            setColorFilter(Color.parseColor("#D8D8DE"))
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        })
        setOnClickListener { Toast.makeText(context, "Filters aren't available yet", Toast.LENGTH_SHORT).show() }
    }
    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        }
        addView(closeButton)
        addView(searchField)
        addView(filterButton)
    })

    
    // Live view refs for each level-1 row (All, Structure, Layout, Typography, Geometry, ...).
    // Split out from the row's own layout since a plain LinearLayout can't give us a background
    // that bleeds past its own bounds (frameBg) while the icon/label/chevron on top stay put -
    // see buildRailRow below.
    data class RailRowViews(
        val wrapper: FrameLayout,
        val frameBg: View,
        val icon: ImageView,
        val label: TextView,
        val chevron: ImageView
    )
    val railIcons = mutableMapOf<String, RailRowViews>()
    val sectionViews = mutableMapOf<String, View>()

    // Icon/label sizing for level-1 rows, in their default and selected states. The selected
    // values only apply to whichever row currently owns the frame (see setActiveCategory) -
    // every other row (and every row when nothing is selected) uses the default size.
    val railIconSizeDefault = dp(18)
    val railIconSizeSelected = dp(20)
    val railLabelTextSizeDefault = 12.5f
    val railLabelTextSizeSelected = 10f

    // Highlights the active level-1 row, or none at all when `id` is null. "All" is never
    // treated as a real selection here - its click handler always passes null, since it's the
    // default/no-filter view rather than one of the level-1 components (Structure, Layout,
    // Typography, Geometry, ...). So with nothing selected (including the initial state, and
    // whenever "All" is showing) no row shows the frame or the chevron.
    fun setActiveCategory(id: String?) {
        for ((catId, entry) in railIcons) {
            val active = id != null && catId == id
            // Flat, edge-to-edge highlight (see bg_rail_row_selected) instead of a floating
            // rounded pill - keeps the selected row structurally connected to the sidebar.
            entry.frameBg.visibility = if (active) View.VISIBLE else View.GONE
            val color = Color.parseColor(if (active) "#FFFFFF" else "#9A9AA5")
            entry.icon.setColorFilter(color)
            // Icon grows slightly and the label shrinks + goes bold once this row is selected,
            // so the pairing reads as "promoted" without changing its position on screen.
            val iconSize = if (active) railIconSizeSelected else railIconSizeDefault
            entry.icon.layoutParams = entry.icon.layoutParams.apply {
                width = iconSize
                height = iconSize
            }
            entry.label.setTextColor(color)
            entry.label.textSize = if (active) railLabelTextSizeSelected else railLabelTextSizeDefault
            entry.label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            // The chevron - like the frame - only appears on the currently selected level-1 row.
            entry.chevron.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    val sectionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, dp(28))
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
    // Shown instead of contentScroll whenever a specific category (not "All") is selected in
    // the rail. Categories don't have their own dedicated views yet, so this is just blank for
    // now - each category will get its own use-case-specific content here later. "Geometry"'s
    // own navigation lives in the sidebar rail itself (see geometryAccordion below), not here.
    val emptyCategoryView = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }
    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        addView(contentScroll)
        addView(emptyCategoryView)
    }
    fun showAllContent() {
        emptyCategoryView.visibility = View.GONE
        contentScroll.visibility = View.VISIBLE
    }
    fun showEmptyCategoryContent() {
        contentScroll.visibility = View.GONE
        emptyCategoryView.visibility = View.VISIBLE
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
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_more_horiz)
                background = selectableForeground()
                setColorFilter(Color.parseColor("#6F707A"))
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

            // Tracks which tile (if any) is currently "selected" within this row. Each
            // category row has its own independent selection state.
            var selectedIndex: Int? = null
            val itemContainers = mutableListOf<LinearLayout>()
            val labelViews = mutableListOf<TextView>()
            val iconBoxViews = mutableListOf<View>()

            val staggerStepMs = 50L
            val animDurationMs = 220L
            val liftDistancePx = dp(56).toFloat() // how far tiles travel up "into" the section header line

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

            // Cascades the non-selected tiles upward and out (staggered by distance from the
            // selected tile), and collapses the selected tile's label so only its icon remains.
            fun animateSelect(index: Int) {
                itemContainers.forEachIndexed { idx, container ->
                    if (idx == index) return@forEachIndexed
                    val delay = kotlin.math.abs(idx - index) * staggerStepMs
                    // Cancel any animation left over from a previous select/restore on this
                    // tile first - cancel() runs synchronously, so the explicit VISIBLE below
                    // always wins over whatever end-action that old animation had queued.
                    container.animate().cancel()
                    container.visibility = View.VISIBLE
                    container.animate()
                        .translationY(-liftDistancePx)
                        .alpha(0f)
                        .setStartDelay(delay)
                        .setDuration(animDurationMs)
                        .setInterpolator(AccelerateInterpolator())
                        // Guarded: if a restore re-targets this same in-flight animator back to
                        // alpha 1 before this end action fires, don't hide a tile that's meant
                        // to be visible again.
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
                // Only one tile left visible now - drop its box/border so it reads as a bare icon.
                iconBoxViews[index].background = null
            }

            // Reverses animateSelect: brings the other tiles back down into place (staggered)
            // and fades the selected tile's label back in.
            fun animateRestore(index: Int) {
                itemContainers.forEachIndexed { idx, container ->
                    if (idx == index) return@forEachIndexed
                    val delay = kotlin.math.abs(idx - index) * staggerStepMs
                    // Cancel first (may synchronously fire a stale GONE from an in-flight
                    // animateSelect), then force VISIBLE - the explicit set below always runs
                    // after cancel()'s side effect, so it wins.
                    container.animate().cancel()
                    container.visibility = View.VISIBLE
                    container.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setStartDelay(delay)
                        .setDuration(animDurationMs)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction(null) // clear any leftover GONE action from animateSelect
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
                // Other tiles are coming back, so this one goes back to looking like a tile too.
                iconBoxViews[index].setBackgroundResource(R.drawable.bg_component_placeholder)
            }

            for (i in 0 until 4) {
                lateinit var label: TextView
                lateinit var iconBox: View
                val itemContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    foreground = selectableForeground()
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i != 0) marginStart = dp(8)
                    }
                    // Icon tile (placeholder box today) - stays visible when selected, but loses
                    // its box/border once it's the only tile left in the row (see animateSelect).
                    // Fixed square size + centered container keeps the icon anchored in the middle
                    // of the frame instead of stretching full-width once its sibling tiles
                    // collapse away and this container expands to fill the row.
                    iconBox = View(context).apply {
                        setBackgroundResource(R.drawable.bg_component_placeholder)
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    }
                    addView(iconBox)
                    // Name label underneath each tile - hidden when its tile is selected.
                    label = TextView(context).apply {
                        text = itemNames.getOrElse(i) { "Component ${i + 1}" }
                        setTextColor(Color.parseColor("#9A9AA5"))
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
                            // Tapping the already-selected tile restores the row.
                            animateRestore(index)
                            selectedIndex = null
                        }
                        null -> {
                            animateSelect(index)
                            selectedIndex = index
                        }
                        else -> {
                            // Switching selection within the row: snap back instantly, then
                            // cascade out around the newly selected tile.
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

    // Fixed-width vertical sidebar frame (~168px), spanning the full available content height,
    // with a subtle 1px divider on its right edge (added where this rail is placed alongside
    // the content pane, below) and the same dark background as the rest of the interface.
    val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(4)
        }
        clipChildren = false
        clipToPadding = false
    }

    // "Geometry"'s Create/Edit/Transform/Deform/Animate navigation lives directly in the
    // sidebar, inserted right under its own row (see the COMPONENT_CATEGORIES loop below).
    // Hidden until the Geometry row is selected.
    val (geometryAccordion, geometryAccordionReset) = buildGeometrySidebarTree(context)
    geometryAccordion.visibility = View.GONE

    // How far the selected row's background (frameBg, below) bleeds past its own row bounds:
    // left all the way to the true edge of the screen, undoing the bottom panel's own
    // android:paddingHorizontal (see activity_sketch.xml); right up to the vertical divider
    // that separates this sidebar from the content pane, undoing the rail's own marginEnd
    // (see `rail` above). Kept as named constants here since both bleed amounts have to match
    // paddings/margins declared elsewhere for the frame to land exactly on those edges.
    val frameBleedToScreenEdge = dp(16)
    val frameBleedToDivider = dp(4)

    // Icon's distance from the true screen edge: the row's own left edge already sits
    // frameBleedToScreenEdge (16dp) in from that edge (the bottom panel's own horizontal
    // padding - see activity_sketch.xml), so the row's left padding is the remainder needed
    // to land the icon exactly 14dp from the edge. Works out negative (icon sits slightly
    // inside the panel's own padding), which setPadding allows.
    val targetIconFromScreenEdge = dp(14)
    val contentPaddingStart = targetIconFromScreenEdge - frameBleedToScreenEdge

    // Shared row for every level-1 entry (All, Structure, Layout, Typography, Geometry, ...) -
    // Geometry intentionally uses the exact same metrics as its siblings rather than its own
    // treatment, so it reads as one of the same list rather than a distinct header. Built as a
    // FrameLayout of two layers rather than one plain row:
    //  - frameBg: the selected-state background. Hidden until this row is the active selection
    //    (see setActiveCategory) - a level-1 row with nothing selected shows no frame at all.
    //    Sized past the row's own bounds (negative margins) so once visible it reaches the true
    //    screen edge on the left and the sidebar/content divider on the right, rather than
    //    stopping at the row's own padding.
    //  - content: the icon/label/chevron, unaffected by the background's bleed and kept at the
    //    row's normal padded position. The chevron is likewise hidden until this row is selected.
    fun buildRailRow(iconRes: Int, label: String, onClick: () -> Unit): RailRowViews {
        lateinit var iconView: ImageView
        lateinit var labelView: TextView
        lateinit var chevronView: ImageView

        val frameBg = View(context).apply {
            setBackgroundResource(R.drawable.bg_rail_row_selected)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                marginStart = -frameBleedToScreenEdge
                marginEnd = -frameBleedToDivider
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            // No elevation/shadow on this row - the selected state is communicated purely
            // through the flat background fill and text/icon color, not a raised surface.
            elevation = 0f
            setPadding(contentPaddingStart, 0, dp(10), 0)
            iconView = ImageView(context).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(railIconSizeDefault, railIconSizeDefault)
            }
            addView(iconView)
            labelView = TextView(context).apply {
                text = label
                textSize = railLabelTextSizeDefault
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            }
            addView(labelView)
            // Selection indicator - hidden by default, shown only for the active row (see
            // setActiveCategory).
            chevronView = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(Color.WHITE)
                rotation = 270f
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            }
            addView(chevronView)
            setOnClickListener { onClick() }
        }

        val wrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply {
                bottomMargin = dp(4)
            }
            clipChildren = false
            clipToPadding = false
            addView(frameBg)
            addView(content)
        }

        return RailRowViews(wrapper, frameBg, iconView, labelView, chevronView)
    }

    // Ordered list of every row in the rail (the "All" row, the divider, then each category
    // row) in visual top-to-bottom order. Drives the cascade: index distance from whichever
    // entry is selected determines each entry's stagger delay.
    data class RailEntry(val view: View, val label: TextView?)
    val railEntries = mutableListOf<RailEntry>()
    var activeRailIndex: Int? = null
    var activeCategoryId: String? = null

    val railStaggerStepMs = 45L
    val railAnimDurationMs = 200L
    val railLiftDistancePx = dp(40).toFloat()

    fun railResetAllImmediate() {
        railEntries.forEach { entry ->
            entry.view.animate().cancel()
            entry.view.visibility = View.VISIBLE
            entry.view.alpha = 1f
            entry.view.translationY = 0f
            entry.label?.let { l ->
                l.animate().cancel()
                l.visibility = View.VISIBLE
                l.alpha = 1f
            }
        }
    }

    // Cascades every other rail row - including "All" (row 0) and its divider - upward and out
    // (staggered by distance), leaving only the selected row in place. Since the others
    // collapse to GONE rather than just hiding, the selected row ends up sitting where "All"
    // used to be: "All" and every other level-1 component vanish together, and the selected
    // one takes its spot at the top of the rail. The selected row's own label is left alone
    // (see buildRailRow/content) so its name stays visible next to its icon.
    fun railAnimateSelect(index: Int) {
        railEntries.forEachIndexed { idx, entry ->
            if (idx == index) return@forEachIndexed
            val delay = kotlin.math.abs(idx - index) * railStaggerStepMs
            entry.view.animate().cancel()
            entry.view.visibility = View.VISIBLE
            entry.view.animate()
                .translationY(-railLiftDistancePx)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(railAnimDurationMs)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { if (entry.view.alpha == 0f) entry.view.visibility = View.GONE }
                .start()
        }
    }

    // Reverses railAnimateSelect: brings "All", its divider, and every other row back into
    // place (staggered).
    fun railAnimateRestore(index: Int) {
        railEntries.forEachIndexed { idx, entry ->
            if (idx == index) return@forEachIndexed
            val delay = kotlin.math.abs(idx - index) * railStaggerStepMs
            entry.view.animate().cancel()
            entry.view.visibility = View.VISIBLE
            entry.view.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(delay)
                .setDuration(railAnimDurationMs)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction(null)
                .start()
        }
    }

    // "All" sits on top of the rail and is the default state: every section is visible and
    // nothing is filtered out. Selecting it restores the rail if something else is collapsed,
    // then scrolls back to the top.
    val allItem = buildRailRow(ALL_CATEGORY.iconRes, ALL_CATEGORY.label) {
        // "All" is never a real selection (see setActiveCategory) - passing null both clears
        // the frame/chevron from wherever they were showing and marks nothing as selected.
        setActiveCategory(null)
        showAllContent()
        activeRailIndex?.let { railAnimateRestore(it) }
        activeRailIndex = null
        if (activeCategoryId == "shapes") {
            geometryAccordionReset()
            geometryAccordion.visibility = View.GONE
        }
        activeCategoryId = null
        contentScroll.post { contentScroll.smoothScrollTo(0, 0) }
    }
    rail.addView(allItem.wrapper)
    railIcons[ALL_CATEGORY.id] = allItem
    railEntries.add(RailEntry(allItem.wrapper, allItem.label))

    val railDivider = View(context).apply {
        setBackgroundColor(Color.parseColor("#2A2A31"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(2)
            bottomMargin = dp(8)
        }
    }
    rail.addView(railDivider)
    railEntries.add(RailEntry(railDivider, null))

    for (cat in COMPONENT_CATEGORIES) {
        val index = railEntries.size // this row's fixed position, captured before it's appended
        val item: RailRowViews

        item = buildRailRow(cat.iconRes, cat.label) {
            when (activeRailIndex) {
                index -> {
                    // Deselecting this category (tapped again) - back to the default "All"
                    // state, so no level-1 row shows the frame or chevron.
                    setActiveCategory(null)
                    railAnimateRestore(index)
                    activeRailIndex = null
                    activeCategoryId = null
                    showAllContent()
                    if (cat.id == "shapes") {
                        geometryAccordionReset()
                        geometryAccordion.visibility = View.GONE
                    }
                }
                null -> {
                    // Nothing was selected - selecting this category fresh. It takes over
                    // "All"'s spot at the top of the rail as everything else (All included)
                    // collapses away - see railAnimateSelect.
                    setActiveCategory(cat.id)
                    railAnimateSelect(index)
                    activeRailIndex = index
                    activeCategoryId = cat.id
                    showEmptyCategoryContent()
                    if (cat.id == "shapes") geometryAccordion.visibility = View.VISIBLE
                }
                else -> {
                    // A different category was active - switch straight to this one. If the
                    // previous one was Geometry, fold its sidebar tree back away first.
                    setActiveCategory(cat.id)
                    if (activeCategoryId == "shapes") {
                        geometryAccordionReset()
                        geometryAccordion.visibility = View.GONE
                    }
                    railResetAllImmediate()
                    railAnimateSelect(index)
                    activeRailIndex = index
                    activeCategoryId = cat.id
                    showEmptyCategoryContent()
                    if (cat.id == "shapes") geometryAccordion.visibility = View.VISIBLE
                }
            }
        }

        rail.addView(item.wrapper)
        railIcons[cat.id] = item
        railEntries.add(RailEntry(item.wrapper, item.label))

        // Geometry's Create/Edit/Transform/Deform/Animate tree sits directly beneath its own
        // row in the sidebar (not in the content pane) - hidden until Geometry is selected.
        if (cat.id == "shapes") {
            rail.addView(geometryAccordion)
        }
    }
    // Initial state: nothing selected, so no level-1 row shows the frame or chevron ("All" is
    // just the default view, not a selection).
    setActiveCategory(null)

    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val query = s?.toString()?.trim()?.lowercase().orEmpty()
            for (cat in COMPONENT_CATEGORIES) {
                val matches = query.isEmpty() ||
                    cat.label.lowercase().contains(query) ||
                    COMPONENT_ITEMS[cat.id].orEmpty().any { it.lowercase().contains(query) }
                sectionViews[cat.id]?.visibility = if (matches) View.VISIBLE else View.GONE
                railIcons[cat.id]?.wrapper?.alpha = if (matches) 1f else 0.35f
            }
        }
    })

    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        // Un-clipped for the same reason as `root` above - lets the selected level-1 row's
        // background bleed out past the rail into the screen edge on its left.
        clipChildren = false
        clipToPadding = false
        addView(rail)
        addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#2A2A31"))
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(10)
            }
        })
        addView(contentContainer)
    })

    return root
}