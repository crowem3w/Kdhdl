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





















// Dedicated content for the "Geometry" category: an accordion listing Create/Edit/Transform/
// Deform/Animate (each with its own icon) below one another. Tapping one cascades the other
// sub-categories out of the way (same lift+fade stagger used elsewhere in this panel) and
// reveals its leaf items as text-only rows underneath - those leaves have no icons supplied,
// so they're plain labels. Nothing is wired up to real content yet (see onClick below).
private fun buildGeometryContent(context: Context): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun selectableForeground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return if (outValue.resourceId != 0) context.getDrawable(outValue.resourceId) else null
    }

    val staggerStepMs = 45L
    val animDurationMs = 200L
    val liftDistancePx = dp(28).toFloat()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(0, dp(2), 0, dp(24))
        clipChildren = false
        clipToPadding = false
    }

    root.addView(TextView(context).apply {
        text = "Geometry"
        setTextColor(Color.WHITE)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    })

    data class Row(val header: LinearLayout, val childrenContainer: LinearLayout)
    val rows = mutableListOf<Row>()
    var expandedIndex: Int? = null

    for ((index, sub) in GEOMETRY_SUBCATEGORIES.withIndex()) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(ImageView(context).apply {
                setImageResource(sub.iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
            addView(TextView(context).apply {
                text = sub.label
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                }
            })
        }

        // Leaf items: text-only rows (no icon supplied for these), indented under their parent.
        // Hidden until this sub-category is expanded.
        val childrenContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
            for (childLabel in sub.children) {
                addView(TextView(context).apply {
                    text = childLabel
                    setTextColor(Color.parseColor("#9A9AA5"))
                    textSize = 12.5f
                    isClickable = true
                    isFocusable = true
                    foreground = selectableForeground()
                    setPadding(dp(38), dp(7), dp(10), dp(7))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    // Content intentionally left blank for now - no detail view wired up yet.
                    setOnClickListener { }
                })
            }
        }

        root.addView(header)
        root.addView(childrenContainer)
        rows.add(Row(header, childrenContainer))
    }

    fun collapseChildrenImmediate(row: Row) {
        row.childrenContainer.animate().cancel()
        row.childrenContainer.visibility = View.GONE
        row.childrenContainer.alpha = 0f
        row.childrenContainer.translationY = 0f
    }

    fun resetHeadersImmediate() {
        rows.forEach { row ->
            row.header.animate().cancel()
            row.header.visibility = View.VISIBLE
            row.header.alpha = 1f
            row.header.translationY = 0f
        }
    }

    // Fades/lifts every other header out of the way (staggered by distance from the expanded
    // row) and reveals the expanded row's children, fading them in top-to-bottom.
    fun expand(index: Int) {
        rows.forEachIndexed { idx, row ->
            if (idx == index) return@forEachIndexed
            val delay = kotlin.math.abs(idx - index) * staggerStepMs
            row.header.animate().cancel()
            row.header.visibility = View.VISIBLE
            row.header.animate()
                .translationY(-liftDistancePx)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(animDurationMs)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { if (row.header.alpha == 0f) row.header.visibility = View.GONE }
                .start()
        }
        val childrenContainer = rows[index].childrenContainer
        childrenContainer.animate().cancel()
        childrenContainer.visibility = View.VISIBLE
        childrenContainer.alpha = 0f
        childrenContainer.translationY = -dp(8).toFloat()
        childrenContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(staggerStepMs)
            .setDuration(animDurationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(null)
            .start()
    }

    // Reverses expand(): hides the expanded row's children and brings every other header back
    // into place, staggered.
    fun collapse(index: Int) {
        val childrenContainer = rows[index].childrenContainer
        childrenContainer.animate().cancel()
        childrenContainer.animate()
            .alpha(0f)
            .translationY(-dp(8).toFloat())
            .setDuration(animDurationMs)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (childrenContainer.alpha == 0f) {
                    childrenContainer.visibility = View.GONE
                    childrenContainer.translationY = 0f
                }
            }
            .start()

        rows.forEachIndexed { idx, row ->
            if (idx == index) return@forEachIndexed
            val delay = kotlin.math.abs(idx - index) * staggerStepMs
            row.header.animate().cancel()
            row.header.visibility = View.VISIBLE
            row.header.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(delay)
                .setDuration(animDurationMs)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction(null)
                .start()
        }
    }

    rows.forEachIndexed { index, row ->
        row.header.setOnClickListener {
            when (expandedIndex) {
                index -> {
                    collapse(index)
                    expandedIndex = null
                }
                null -> {
                    expand(index)
                    expandedIndex = index
                }
                else -> {
                    resetHeadersImmediate()
                    rows.forEachIndexed { idx, r -> if (idx != index) collapseChildrenImmediate(r) }
                    expand(index)
                    expandedIndex = index
                }
            }
        }
    }

    return root
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
    // the rail. Most categories don't have their own dedicated views yet, so this is just blank
    // for now - each will get its own use-case-specific content here later. "Geometry" is the
    // first to get one - see geometryContentView below.
    val emptyCategoryView = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }
    // Dedicated "Geometry" content: Create/Edit/Transform/Deform/Animate accordion (see
    // buildGeometryContent). Wrapped in a ScrollView since the expanded leaf list can run long.
    val geometryContentView = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
        addView(buildGeometryContent(context))
    }
    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        addView(contentScroll)
        addView(emptyCategoryView)
        addView(geometryContentView)
    }
    fun showAllContent() {
        emptyCategoryView.visibility = View.GONE
        geometryContentView.visibility = View.GONE
        contentScroll.visibility = View.VISIBLE
    }
    fun showEmptyCategoryContent() {
        contentScroll.visibility = View.GONE
        geometryContentView.visibility = View.GONE
        emptyCategoryView.visibility = View.VISIBLE
    }
    fun showGeometryContent() {
        contentScroll.visibility = View.GONE
        emptyCategoryView.visibility = View.GONE
        geometryContentView.visibility = View.VISIBLE
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

    // Cascades every rail row except `index` upward and out (staggered by distance), and
    // collapses the selected row's own label so only its icon remains. Row 0 ("All") is
    // excluded - it always stays visible regardless of what else is selected.
    fun railAnimateSelect(index: Int) {
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

    // Reverses railAnimateSelect: brings every other row back into place (staggered) and
    // fades the selected row's label back in. Row 0 ("All") is excluded - always visible.
    fun railAnimateRestore(index: Int) {
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

    // "All" sits on top of the rail and is the default state: every section is visible and
    // nothing is filtered out. Selecting it restores the rail if something else is collapsed,
    // then scrolls back to the top.
    val allItem = buildRailRow(ALL_CATEGORY.iconRes, ALL_CATEGORY.label) {
        setActiveCategory(ALL_CATEGORY.id)
        showAllContent()
        activeRailIndex?.let { railAnimateRestore(it) }
        activeRailIndex = null
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
        // "Geometry" (id "shapes") has its own dedicated content; every other category still
        // falls back to the generic blank placeholder until it gets one too.
        fun showCategoryContent() = if (cat.id == "shapes") showGeometryContent() else showEmptyCategoryContent()
        val item = buildRailRow(cat.iconRes, cat.label) {
            setActiveCategory(cat.id)
            when (activeRailIndex) {
                index -> {
                    railAnimateRestore(index)
                    activeRailIndex = null
                    showAllContent()
                }
                null -> {
                    railAnimateSelect(index)
                    activeRailIndex = index
                    showCategoryContent()
                }
                else -> {
                    railResetAllImmediate()
                    railAnimateSelect(index)
                    activeRailIndex = index
                    showCategoryContent()
                }
            }
        }
        rail.addView(item)
        railIcons[cat.id] = item
        railEntries.add(RailEntry(item, item.getChildAt(1) as TextView))
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