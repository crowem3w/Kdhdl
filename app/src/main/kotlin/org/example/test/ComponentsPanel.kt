package org.example.test

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
private val PANEL_BG = Color.parseColor("#F3F4F6")
private val PANEL_PRIMARY_TEXT = Color.parseColor("#1A1B24")
private val PANEL_SECONDARY_TEXT = Color.parseColor("#6B7280")
private val PANEL_NEUTRAL_TEXT = Color.parseColor("#9CA3AF")
private val PANEL_DIVIDER = Color.parseColor("#D1D5DB")
private val PANEL_ACCENT = Color.parseColor("#355E3B")

private data class ComponentCategory(val id: String, val label: String, val iconRes: Int)

/**
 * Draws a single shadowed line that traces the Elements-panel sidebar's top edge, bends through
 * its rounded top-right corner, and continues down its right edge - so the sidebar/content
 * divider "blends" into the sidebar's border radius instead of meeting a curved corner with an
 * abrupt straight line, and so the drop shadow reads as one continuous shadow along the sidebar's
 * top and right sides rather than two disconnected shadows.
 *
 * Sized as an overlay slightly wider than the sidebar (see edgeWidthPx vs. actual view width) so
 * the blurred shadow has room to bleed to the right without being clipped at the view's bounds.
 */
private class SidebarEdgeShadowView(
    context: Context,
    edgeWidthPx: Float,
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Float,
    private val lineColor: Int,
    private val shadowRadiusPx: Float,
    private val shadowColor: Int,
    private val shadowDx: Float,
    private val shadowDy: Float,
) : View(context) {

    // Mutable so the drag handle can move the line as the rail is resized, without having to
    // rebuild this view.
    var edgeWidthPx: Float = edgeWidthPx
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = lineColor
    }

    init {
        // View elevation can't cast a shadow along an arbitrary curved path - Paint.setShadowLayer
        // can, but it only renders on a software-rendered layer.
        setLayerType(View.LAYER_TYPE_SOFTWARE, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The line itself is drawn at edgeWidthPx (the sidebar's real right edge). The view is
        // wider than that so the blurred shadow has room to bleed rightward without being
        // clipped at the view's own bounds.
        val top = strokeWidthPx
        val right = edgeWidthPx
        val r = cornerRadiusPx
        val path = Path().apply {
            moveTo(0f, top)
            lineTo(right - r, top)
            arcTo(RectF(right - 2 * r, top, right, top + 2 * r), 270f, 90f, false)
            lineTo(right, height.toFloat())
        }
        paint.setShadowLayer(shadowRadiusPx, shadowDx, shadowDy, shadowColor)
        canvas.drawPath(path, paint)
    }
}

private data class GeometrySubCategory(val label: String, val iconRes: Int, val children: List<String>)

private val GEOMETRY_SUBCATEGORIES = listOf(
    GeometrySubCategory("Create", R.drawable.ic_geo_create, listOf("Point", "Line", "Curve", "2D Shape", "3D Primitive", "Custom Mesh")),
    GeometrySubCategory("Edit", R.drawable.ic_geo_edit, listOf("Vertex", "Edge", "Face", "Path", "Control Point")),
    GeometrySubCategory("Transform", R.drawable.ic_geo_transform, listOf("Move", "Rotate", "Scale", "Skew", "Pivot")),
    GeometrySubCategory("Deform", R.drawable.ic_geo_deform, listOf("Bend", "Twist", "Taper", "Warp", "Freeform")),
    GeometrySubCategory("Animate", R.drawable.ic_geo_animate, listOf("Keyframe", "Motion Path", "Constraint", "Timeline", "Graph Editor")),
)

private val ALL_CATEGORY = ComponentCategory("all", "All", R.drawable.ic_components)

private val COMPONENT_CATEGORIES = listOf(
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

private fun buildGeometrySidebarTree(context: Context): Pair<View, () -> Unit> {
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

fun buildComponentsContent(context: Context, onClose: () -> Unit = {}): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)



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






    data class RailRowViews(
        val wrapper: FrameLayout,
        val frameBg: View,
        val icon: ImageView,
        val label: TextView,
        val chevron: ImageView
    )
    val railIcons = mutableMapOf<String, RailRowViews>()
    val sectionViews = mutableMapOf<String, View>()




    val railIconSizeDefault = dp(18)
    val railIconSizeSelected = dp(20)
    val railLabelTextSizeDefault = 12.5f
    val railLabelTextSizeSelected = 10f






    fun setActiveCategory(id: String?) {
        for ((catId, entry) in railIcons) {
            val active = id != null && catId == id


            entry.frameBg.visibility = if (active) View.VISIBLE else View.GONE
            val color = if (active) PANEL_ACCENT else PANEL_SECONDARY_TEXT
            entry.icon.setColorFilter(color)


            // Icons + labels are always bold in this sidebar; only size/color/chevron
            // change to indicate the active category.
            val iconSize = railIconSizeSelected
            entry.icon.layoutParams = entry.icon.layoutParams.apply {
                width = iconSize
                height = iconSize
            }
            entry.label.setTextColor(color)
            entry.label.textSize = if (active) railLabelTextSizeSelected else railLabelTextSizeDefault
            entry.label.setTypeface(null, Typeface.BOLD)

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

            for (i in 0 until 4) {
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




    val railWidthPx = dp(168).toFloat()
    val railMinWidthPx = dp(64).toFloat()
    val railMaxWidthPx = dp(240).toFloat()
    // Below this width the row labels fade out and rows show icon-only.
    val railCompactThresholdPx = dp(96).toFloat()
    val cornerRadiusPx = dp(16).toFloat()
    val edgeStrokeWidthPx = 1.5f * d
    val edgeShadowRadiusPx = 4f * d
    val edgeShadowDyPx = 2f * d
    // Extra width to the right of the sidebar's real edge so the blurred shadow has room to
    // bleed without being clipped at the overlay view's own bounds.
    val edgeShadowBleedPx = kotlin.math.ceil(edgeShadowRadiusPx + edgeShadowDyPx).toInt()
    val dividerGapPx = dp(16)

    val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.MATCH_PARENT)
        // Rounded only on the top-right corner, matching the edge that sits against the
        // sidebar/content divider.
        background = context.getDrawable(R.drawable.bg_elements_sidebar)
        clipChildren = false
        clipToPadding = false
    }

    // Overlay that traces the sidebar's top edge, bends around its rounded top-right corner, and
    // continues down its right edge - this is the sidebar/content divider. It's drawn as one
    // continuous shadowed line so the divider's top blends into the sidebar's border radius
    // instead of meeting it as an abrupt straight line, and so the drop shadow covers the
    // sidebar's top side as well as its right side.
    val sidebarEdgeShadow = SidebarEdgeShadowView(
        context = context,
        edgeWidthPx = railWidthPx,
        cornerRadiusPx = cornerRadiusPx,
        strokeWidthPx = edgeStrokeWidthPx,
        lineColor = PANEL_DIVIDER,
        shadowRadiusPx = edgeShadowRadiusPx,
        shadowColor = Color.parseColor("#33000000"),
        shadowDx = 0f,
        shadowDy = edgeShadowDyPx,
    )

    val railWrapper = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(168) + edgeShadowBleedPx, ViewGroup.LayoutParams.MATCH_PARENT)
        clipChildren = false
        clipToPadding = false
        addView(rail)
        addView(
            sidebarEdgeShadow,
            FrameLayout.LayoutParams(dp(168) + edgeShadowBleedPx, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    // Draggable divider: a plain, wide-enough-to-grab touch target dropped into the gap between
    // the rail and the content column. It doesn't draw anything itself - the rounded-corner
    // divider line is still sidebarEdgeShadow above - it just resizes the rail as it's dragged.
    var railCurrentWidthPx = railWidthPx
    var railIsCompact = false

    fun applyRailWidth(widthPx: Float) {
        val w = widthPx.toInt()
        (rail.layoutParams as FrameLayout.LayoutParams).width = w
        rail.requestLayout()
        val wrapperWidth = w + edgeShadowBleedPx
        (railWrapper.layoutParams as LinearLayout.LayoutParams).width = wrapperWidth
        railWrapper.requestLayout()
        (sidebarEdgeShadow.layoutParams as FrameLayout.LayoutParams).width = wrapperWidth
        sidebarEdgeShadow.edgeWidthPx = widthPx
        sidebarEdgeShadow.requestLayout()
    }

    val dividerHandle = View(context).apply {
        isClickable = true
        isFocusable = true
        contentDescription = "Resize sidebar"
        layoutParams = LinearLayout.LayoutParams(dividerGapPx, ViewGroup.LayoutParams.MATCH_PARENT)
    }




    val (geometryAccordion, geometryAccordionReset) = buildGeometrySidebarTree(context)
    geometryAccordion.visibility = View.GONE







    val frameBleedToScreenEdge = dp(16)
    val frameBleedToDivider = dp(4)






    val targetIconFromScreenEdge = dp(14)
    val contentPaddingStart = targetIconFromScreenEdge - frameBleedToScreenEdge












    // Only the topmost rail row (the "All" row) sits against the sidebar's rounded top-right
    // corner - every other edge of the sidebar (top-left, both bottom corners) is square, so
    // every row below stays flat. Rounding just that one corner, by the same cornerRadiusPx used
    // for the sidebar itself, keeps the selection highlight from poking a square edge out past
    // the sidebar's rounded corner.
    fun buildRailRow(iconRes: Int, label: String, topRounded: Boolean = false, onClick: () -> Unit): RailRowViews {
        lateinit var iconView: ImageView
        lateinit var labelView: TextView
        lateinit var chevronView: ImageView

        val frameBg = View(context).apply {
            background = if (topRounded) {
                GradientDrawable().apply {
                    setColor(Color.parseColor("#33355E3B"))
                    cornerRadii = floatArrayOf(
                        0f, 0f,                                 // top-left
                        cornerRadiusPx, cornerRadiusPx,         // top-right
                        0f, 0f,                                 // bottom-right
                        0f, 0f                                  // bottom-left
                    )
                }
            } else {
                context.getDrawable(R.drawable.bg_rail_row_selected)
            }
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


            chevronView = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left)
                setColorFilter(PANEL_ACCENT)
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




    val allItem = buildRailRow(ALL_CATEGORY.iconRes, ALL_CATEGORY.label, topRounded = true) {


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
        setBackgroundColor(PANEL_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(2)
            bottomMargin = dp(8)
        }
    }
    rail.addView(railDivider)
    railEntries.add(RailEntry(railDivider, null))

    for (cat in COMPONENT_CATEGORIES) {
        val index = railEntries.size
        val item: RailRowViews

        item = buildRailRow(cat.iconRes, cat.label) {
            when (activeRailIndex) {
                index -> {


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



                    setActiveCategory(cat.id)
                    railAnimateSelect(index)
                    activeRailIndex = index
                    activeCategoryId = cat.id
                    showEmptyCategoryContent()
                    if (cat.id == "shapes") geometryAccordion.visibility = View.VISIBLE
                }
                else -> {


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



        if (cat.id == "shapes") {
            rail.addView(geometryAccordion)
        }
    }


    setActiveCategory(null)

    // Icon-only mode: below railCompactThresholdPx, fade out every row's label so only icons
    // remain. Toggled from the drag handle below; also driven off railIcons/allItem so both the
    // "All" row and every category row respond together.
    fun setRailCompact(compact: Boolean) {
        if (compact == railIsCompact) return
        railIsCompact = compact
        val labels = railIcons.values.map { it.label } + allItem.label
        labels.forEach { label ->
            label.animate().cancel()
            if (compact) {
                label.animate()
                    .alpha(0f)
                    .setDuration(120L)
                    .withEndAction { if (label.alpha == 0f) label.visibility = View.GONE }
                    .start()
            } else {
                label.visibility = View.VISIBLE
                label.animate()
                    .alpha(1f)
                    .setDuration(120L)
                    .start()
            }
        }
    }

    var dragStartRawX = 0f
    var dragStartWidthPx = 0f
    dividerHandle.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartWidthPx = railCurrentWidthPx
                // The Elements panel sits in a draggable BottomSheetBehavior, whose
                // ViewDragHelper watches every touch stream on the parent CoordinatorLayout for
                // drag gestures. Without this, a reversal in direction mid-drag (e.g. left then
                // right) can get intercepted by the sheet and delivered to us as ACTION_CANCEL,
                // killing the resize gesture. Claim the touch stream for the full drag so the
                // sheet leaves it alone.
                view.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragStartRawX
                val newWidth = (dragStartWidthPx + deltaX).coerceIn(railMinWidthPx, railMaxWidthPx)
                railCurrentWidthPx = newWidth
                applyRailWidth(newWidth)
                setRailCompact(newWidth < railCompactThresholdPx)
                // Re-anchor once clamped so a reversal responds immediately instead of requiring
                // the finger to travel back through the overshoot distance first.
                if (newWidth == railMinWidthPx || newWidth == railMaxWidthPx) {
                    dragStartRawX = event.rawX
                    dragStartWidthPx = newWidth
                }
                true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                view.parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

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


        clipChildren = false
        clipToPadding = false
        addView(railWrapper)
        addView(dividerHandle)
        addView(contentContainer)
    })

    return root
}