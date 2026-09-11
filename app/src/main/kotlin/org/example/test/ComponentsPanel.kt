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
// its icon (not in the main content pane), styled after the reference tree UI: each row is
// icon + label + a chevron that rotates to indicate expanded/collapsed, with a vertical accent
// line marking the currently open branch. Create/Edit/Transform/Deform/Animate start out as
// plain neutral rows; tapping one plays a "cascading merge" - the other rows slide into its
// position and fade away, staggered by distance - leaving just the tapped row, now shown with a
// blue accent line and a bordered icon box, with its leaf items appearing directly beneath it as
// a text-only list. That list has a single continuous vertical line running from the first leaf
// to the last (rather than a mark per line), echoing the tree in geometry.txt, plus a small
// accent bar next to whichever leaf is currently highlighted (the first one, by default).
// Nothing is wired up to real content yet - tapping a leaf only changes which one is
// highlighted.
// Returns the view plus a `reset` callback that immediately restores everything, for use when
// the Geometry category itself is deselected in the rail.
private fun buildGeometrySidebarAccordion(context: Context): Pair<View, () -> Unit> {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun selectableForeground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return if (outValue.resourceId != 0) context.getDrawable(outValue.resourceId) else null
    }

    val staggerStepMs = 45L
    val mergeDurationMs = 220L
    val restoreDurationMs = 200L
    val restoreOffsetPx = dp(14).toFloat()
    val activeColor = Color.WHITE
    val inactiveColor = Color.parseColor("#9A9AA5")
    val accentColor = Color.parseColor("#3D7EFF")
    val treeLineColor = Color.parseColor("#4A4A52")

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }
        setPadding(0, 0, 0, dp(6))
        clipChildren = false
        clipToPadding = false
    }

    data class Item(
        val row: LinearLayout,
        val accentLine: View,
        val iconBox: FrameLayout,
        val icon: ImageView,
        val label: TextView,
        val chevron: ImageView,
        val childrenContainer: LinearLayout
    )
    val items = mutableListOf<Item>()
    var selectedIndex: Int? = null

    // Builds this item's leaf list directly under it: a single continuous vertical line beside
    // the text (rather than a mark per line), stretched once the text column is actually
    // measured so it runs exactly from the top of the first leaf to the bottom of the last, plus
    // a small accent bar beside whichever leaf is highlighted (the first one, to start).
    fun fillChildren(container: LinearLayout, index: Int) {
        container.removeAllViews()
        val children = GEOMETRY_SUBCATEGORIES[index].children
        val leafRows = mutableListOf<Pair<View, TextView>>()
        var highlightedLeaf = 0

        fun applyLeafState(leafIdx: Int, highlighted: Boolean) {
            val (bar, text) = leafRows[leafIdx]
            bar.setBackgroundColor(if (highlighted) accentColor else Color.TRANSPARENT)
            text.setTextColor(if (highlighted) activeColor else inactiveColor)
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            for ((leafIdx, childLabel) in children.withIndex()) {
                lateinit var bar: View
                lateinit var text: TextView
                val leafRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    foreground = selectableForeground()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    bar = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(2), dp(2))
                    }
                    addView(bar)
                    text = TextView(context).apply {
                        text = childLabel
                        textSize = 11.5f
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    addView(text)
                }
                leafRow.post {
                    val params = bar.layoutParams
                    params.height = leafRow.height
                    bar.layoutParams = params
                }
                leafRow.setOnClickListener {
                    // Content intentionally left blank for now - this only moves the highlight,
                    // no detail view is wired up yet.
                    if (highlightedLeaf != leafIdx) {
                        applyLeafState(highlightedLeaf, false)
                        highlightedLeaf = leafIdx
                        applyLeafState(highlightedLeaf, true)
                    }
                }
                addView(leafRow)
                leafRows.add(bar to text)
            }
        }
        leafRows.forEachIndexed { leafIdx, _ -> applyLeafState(leafIdx, leafIdx == highlightedLeaf) }

        val treeLine = View(context).apply {
            setBackgroundColor(treeLineColor)
            layoutParams = LinearLayout.LayoutParams(dp(2), dp(2))
        }
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(14)
            }
            addView(treeLine)
            addView(textColumn)
        })
        textColumn.post {
            val params = treeLine.layoutParams
            params.height = textColumn.height
            treeLine.layoutParams = params
        }
    }

    // Neutral (default, all rows visible) vs. active (the one row left after the merge): a
    // bordered icon box, blue accent line and bright label mark the active row; a plain icon,
    // hidden accent line and muted label mark a neutral one. The chevron rotates to match.
    fun applyRowState(item: Item, active: Boolean) {
        item.accentLine.visibility = if (active) View.VISIBLE else View.GONE
        item.iconBox.setBackgroundResource(if (active) R.drawable.bg_icon_box_accent else 0)
        item.icon.setColorFilter(activeColor) // icons stay fully visible either way
        item.label.setTextColor(if (active) activeColor else inactiveColor)
        item.chevron.setColorFilter(if (active) activeColor else inactiveColor)
        item.chevron.rotation = if (active) -90f else 180f
    }

    fun collapseChildrenImmediate(item: Item) {
        item.childrenContainer.animate().cancel()
        item.childrenContainer.visibility = View.GONE
        item.childrenContainer.alpha = 0f
        item.childrenContainer.removeAllViews()
    }

    fun restoreAllImmediate() {
        items.forEach { item ->
            item.row.animate().cancel()
            item.row.visibility = View.VISIBLE
            item.row.alpha = 1f
            item.row.translationY = 0f
            applyRowState(item, active = false)
            collapseChildrenImmediate(item)
        }
    }

    // The forward half of the cascade: every other row slides toward the selected row's slot
    // and fades out, staggered by distance, until only the selected one is left; its children
    // then fade in beneath it.
    fun mergeInto(selected: Int) {
        val tops = items.map { it.row.top }
        items.forEachIndexed { idx, item ->
            if (idx == selected) return@forEachIndexed
            val delta = (tops[selected] - tops[idx]).toFloat()
            val delay = kotlin.math.abs(idx - selected) * staggerStepMs
            item.row.animate().cancel()
            item.row.visibility = View.VISIBLE
            item.row.animate()
                .translationY(delta)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(mergeDurationMs)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    item.row.visibility = View.GONE
                    item.row.translationY = 0f
                }
                .start()
        }
        applyRowState(items[selected], active = true)

        val selectedItem = items[selected]
        fillChildren(selectedItem.childrenContainer, selected)
        selectedItem.childrenContainer.animate().cancel()
        selectedItem.childrenContainer.visibility = View.VISIBLE
        selectedItem.childrenContainer.alpha = 0f
        selectedItem.childrenContainer.animate()
            .alpha(1f)
            .setStartDelay(staggerStepMs)
            .setDuration(mergeDurationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // The reverse half: hide the selected row's children, then cascade the other rows back
    // into view, staggered by distance.
    fun unmerge(selected: Int) {
        val selectedItem = items[selected]
        applyRowState(selectedItem, active = false)
        selectedItem.childrenContainer.animate().cancel()
        selectedItem.childrenContainer.animate()
            .alpha(0f)
            .setDuration(restoreDurationMs)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { collapseChildrenImmediate(selectedItem) }
            .start()

        items.forEachIndexed { idx, item ->
            if (idx == selected) return@forEachIndexed
            val delay = kotlin.math.abs(idx - selected) * staggerStepMs
            item.row.animate().cancel()
            item.row.alpha = 0f
            item.row.translationY = -restoreOffsetPx
            item.row.visibility = View.VISIBLE
            item.row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(restoreDurationMs)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    for ((index, sub) in GEOMETRY_SUBCATEGORIES.withIndex()) {
        lateinit var accentLine: View
        lateinit var iconBox: FrameLayout
        lateinit var icon: ImageView
        lateinit var label: TextView
        lateinit var chevron: ImageView
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                if (index != 0) topMargin = dp(2)
            }
            setPadding(dp(10), 0, dp(10), 0)
            accentLine = View(context).apply {
                setBackgroundColor(accentColor)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(dp(2), dp(20))
            }
            addView(accentLine)
            iconBox = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginStart = dp(8)
                }
                icon = ImageView(context).apply {
                    setImageResource(sub.iconRes)
                    setColorFilter(activeColor)
                    layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
                }
                addView(icon)
            }
            addView(iconBox)
            label = TextView(context).apply {
                text = sub.label
                setTextColor(inactiveColor)
                textSize = 12.5f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
            }
            addView(label)
            chevron = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(inactiveColor)
                rotation = 180f
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginStart = dp(8)
                }
            }
            addView(chevron)
        }
        // This subcategory's leaf list, inserted right after its own row so it renders below it
        // in place (not after the whole row list) - hidden until this row is selected.
        val childrenContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
        root.addView(row)
        root.addView(childrenContainer)
        val item = Item(row, accentLine, iconBox, icon, label, chevron, childrenContainer)
        items.add(item)

        row.setOnClickListener {
            when (selectedIndex) {
                index -> {
                    // Tapping the visible row again reverses the cascade.
                    unmerge(index)
                    selectedIndex = null
                }
                null -> {
                    mergeInto(index)
                    selectedIndex = index
                }
                else -> {
                    restoreAllImmediate()
                    mergeInto(index)
                    selectedIndex = index
                }
            }
        }
    }

    fun reset() {
        items.forEach { it.row.animate().cancel() }
        restoreAllImmediate()
        selectedIndex = null
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

    
    val railIcons = mutableMapOf<String, LinearLayout>()
    val sectionViews = mutableMapOf<String, View>()

    fun setActiveCategory(id: String) {
        for ((catId, item) in railIcons) {
            val active = catId == id
            item.setBackgroundResource(if (active) R.drawable.bg_tab_selected else 0)
            val color = Color.parseColor(if (active) "#FFFFFF" else "#9A9AA5")
            (item.getChildAt(0) as ImageView).setColorFilter(color)
            (item.getChildAt(1) as TextView).setTextColor(color)
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

    val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(4)
        }
        clipChildren = false
        clipToPadding = false
    }

    // "Geometry"'s Create/Edit/Transform/Deform/Animate navigation lives directly in the
    // sidebar, inserted right under its own row (see the COMPONENT_CATEGORIES loop below).
    // Hidden until the Geometry row is selected.
    val (geometryAccordion, geometryAccordionReset) = buildGeometrySidebarAccordion(context)
    geometryAccordion.visibility = View.GONE

    fun buildRailRow(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                bottomMargin = dp(4)
            }
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            setPadding(dp(10), 0, dp(10), 0)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            addView(TextView(context).apply {
                text = label
                textSize = 12.5f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
            })
            setOnClickListener { onClick() }
        }
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

    // Cascades every rail row except `index` upward and out (staggered by distance). Row 0
    // ("All") is excluded - it always stays visible regardless of what else is selected.
    fun railHideSiblings(index: Int) {
        railEntries.forEachIndexed { idx, entry ->
            if (idx == index || idx == 0) return@forEachIndexed
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

    // Reverses railHideSiblings: brings every other row back into place, staggered.
    fun railShowSiblings(index: Int) {
        railEntries.forEachIndexed { idx, entry ->
            if (idx == index || idx == 0) return@forEachIndexed
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

    // Cascades every other rail row away (see railHideSiblings) and collapses the selected
    // row's own label so only its icon remains.
    fun railAnimateSelect(index: Int) {
        railHideSiblings(index)
        railEntries[index].label?.let { label ->
            label.animate().cancel()
            label.visibility = View.VISIBLE
            label.animate()
                .alpha(0f)
                .setDuration(railAnimDurationMs)
                .withEndAction { if (label.alpha == 0f) label.visibility = View.GONE }
                .start()
        }
    }

    // Reverses railAnimateSelect: brings every other row back (see railShowSiblings) and fades
    // the selected row's label back in.
    fun railAnimateRestore(index: Int) {
        railShowSiblings(index)
        railEntries[index].label?.let { label ->
            label.animate().cancel()
            label.visibility = View.VISIBLE
            label.alpha = 0f
            label.animate()
                .alpha(1f)
                .setDuration(railAnimDurationMs)
                .withEndAction(null)
                .start()
        }
    }

    // Custom row for "Geometry": unlike other categories, its label stays visible when selected
    // (no collapse-to-icon) and it gets a chevron plus a subtle highlighted background instead,
    // matching the reference tree UI.
    data class GeometryRowRefs(val row: LinearLayout, val label: TextView, val chevron: ImageView)
    lateinit var geometryRowRefs: GeometryRowRefs
    fun buildGeometryRailRow(iconRes: Int, labelText: String, onClick: () -> Unit): GeometryRowRefs {
        lateinit var label: TextView
        lateinit var chevron: ImageView
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                bottomMargin = dp(4)
            }
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            setPadding(dp(10), 0, dp(10), 0)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            label = TextView(context).apply {
                text = labelText
                textSize = 12.5f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
            }
            addView(label)
            chevron = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(Color.WHITE)
                rotation = 180f
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginStart = dp(8)
                }
            }
            addView(chevron)
            setOnClickListener { onClick() }
        }
        return GeometryRowRefs(row, label, chevron)
    }

    // "All" sits on top of the rail and is the default state: every section is visible and
    // nothing is filtered out. Selecting it restores the rail if something else is collapsed,
    // then scrolls back to the top.
    val allItem = buildRailRow(ALL_CATEGORY.iconRes, ALL_CATEGORY.label) {
        setActiveCategory(ALL_CATEGORY.id)
        showAllContent()
        activeRailIndex?.let { railAnimateRestore(it) }
        activeRailIndex = null
        if (activeCategoryId == "shapes") {
            geometryAccordionReset()
            geometryAccordion.visibility = View.GONE
            geometryRowRefs.row.setBackgroundResource(0)
            geometryRowRefs.chevron.rotation = 180f
        }
        activeCategoryId = null
        contentScroll.post { contentScroll.smoothScrollTo(0, 0) }
    }
    rail.addView(allItem)
    railIcons[ALL_CATEGORY.id] = allItem
    railEntries.add(RailEntry(allItem, allItem.getChildAt(1) as TextView))

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
        val isGeometry = cat.id == "shapes"
        val item: LinearLayout
        if (isGeometry) {
            val refs = buildGeometryRailRow(cat.iconRes, cat.label) {
                setActiveCategory(cat.id)
                when (activeRailIndex) {
                    index -> {
                        // Deselecting Geometry (tapped again).
                        railShowSiblings(index)
                        activeRailIndex = null
                        activeCategoryId = null
                        showAllContent()
                        geometryAccordionReset()
                        geometryAccordion.visibility = View.GONE
                        geometryRowRefs.row.setBackgroundResource(0)
                        geometryRowRefs.chevron.rotation = 180f
                    }
                    null -> {
                        // Nothing was selected - selecting Geometry fresh.
                        railHideSiblings(index)
                        activeRailIndex = index
                        activeCategoryId = cat.id
                        showEmptyCategoryContent()
                        geometryAccordion.visibility = View.VISIBLE
                        geometryRowRefs.row.setBackgroundResource(R.drawable.bg_row_active)
                        geometryRowRefs.chevron.rotation = -90f
                    }
                    else -> {
                        // A different category was active - switch straight to Geometry.
                        railResetAllImmediate()
                        railHideSiblings(index)
                        activeRailIndex = index
                        activeCategoryId = cat.id
                        showEmptyCategoryContent()
                        geometryAccordion.visibility = View.VISIBLE
                        geometryRowRefs.row.setBackgroundResource(R.drawable.bg_row_active)
                        geometryRowRefs.chevron.rotation = -90f
                    }
                }
            }
            geometryRowRefs = refs
            item = refs.row
        } else {
            item = buildRailRow(cat.iconRes, cat.label) {
                setActiveCategory(cat.id)
                when (activeRailIndex) {
                    index -> {
                        // Deselecting this category (tapped again).
                        railAnimateRestore(index)
                        activeRailIndex = null
                        activeCategoryId = null
                        showAllContent()
                    }
                    null -> {
                        // Nothing was selected - selecting this category fresh.
                        railAnimateSelect(index)
                        activeRailIndex = index
                        activeCategoryId = cat.id
                        showEmptyCategoryContent()
                    }
                    else -> {
                        // A different category was active - switch straight to this one. If the
                        // previous one was Geometry, fold its sidebar accordion back away first.
                        if (activeCategoryId == "shapes") {
                            geometryAccordionReset()
                            geometryAccordion.visibility = View.GONE
                            geometryRowRefs.row.setBackgroundResource(0)
                            geometryRowRefs.chevron.rotation = 180f
                        }
                        railResetAllImmediate()
                        railAnimateSelect(index)
                        activeRailIndex = index
                        activeCategoryId = cat.id
                        showEmptyCategoryContent()
                    }
                }
            }
        }
        rail.addView(item)
        railIcons[cat.id] = item
        railEntries.add(RailEntry(item, item.getChildAt(1) as TextView))

        // Geometry's Create/Edit/Transform/Deform/Animate rows sit directly beneath its own
        // row in the sidebar (not in the content pane) - hidden until Geometry is selected.
        if (isGeometry) {
            rail.addView(geometryAccordion)
        }
    }
    setActiveCategory(ALL_CATEGORY.id)

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
                railIcons[cat.id]?.alpha = if (matches) 1f else 0.35f
            }
        }
    })

    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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