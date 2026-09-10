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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog


private data class ComponentCategory(val id: String, val label: String, val iconRes: Int)

private val COMPONENT_CATEGORIES = listOf(
    ComponentCategory("structure", "Structure", R.drawable.ic_cat_structure),
    ComponentCategory("layout", "Layout", R.drawable.ic_cat_layout),
    ComponentCategory("typography", "Typography", R.drawable.ic_cat_typography),
    ComponentCategory("shapes", "Shapes", R.drawable.ic_shapes),
    ComponentCategory("media", "Media", R.drawable.ic_media),
    ComponentCategory("navigation", "Navigation", R.drawable.ic_cat_navigation),
    ComponentCategory("input", "Input", R.drawable.ic_cat_input),
    ComponentCategory("actions", "Actions", R.drawable.ic_cat_actions),
    ComponentCategory("content", "Content", R.drawable.ic_cat_content),
    ComponentCategory("feedback", "Feedback", R.drawable.ic_cat_feedback),
    ComponentCategory("mobile", "Mobile", R.drawable.ic_cat_mobile),
    ComponentCategory("prototype", "Prototype", R.drawable.ic_cat_prototype),
)










fun showComponentsPanel(context: Context, onDismiss: () -> Unit = {}) {
    val dialog = BottomSheetDialog(context)
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun selectableForeground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return if (outValue.resourceId != 0) context.getDrawable(outValue.resourceId) else null
    }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#15161F"))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    
    root.addView(View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(14)
        }
        setBackgroundResource(R.drawable.bg_drag_handle)
    })

    
    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(14)
        }
        addView(TextView(context).apply {
            text = "Components"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = "\u2715"
            setTextColor(Color.parseColor("#9A9AA5"))
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { dialog.dismiss() }
        })
    })

    
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
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(16)
        }
        addView(searchField)
        addView(filterButton)
    })

    
    val railIcons = mutableMapOf<String, FrameLayout>()
    val sectionViews = mutableMapOf<String, View>()

    fun setActiveCategory(id: String) {
        for ((catId, item) in railIcons) {
            val active = catId == id
            item.setBackgroundResource(if (active) R.drawable.bg_tab_selected else 0)
            (item.getChildAt(0) as ImageView).setColorFilter(
                Color.parseColor(if (active) "#FFFFFF" else "#9A9AA5")
            )
        }
    }

    val sectionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(2), dp(20), dp(28))
    }
    val contentScroll = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        addView(sectionsContainer)
    }

    for (cat in COMPONENT_CATEGORIES) {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(22)
            }
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
            for (i in 0 until 4) {
                addView(View(context).apply {
                    setBackgroundResource(R.drawable.bg_component_placeholder)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (i != 0) marginStart = dp(8)
                    }
                })
            }
        })
        sectionsContainer.addView(section)
        sectionViews[cat.id] = section
    }

    val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16); marginEnd = dp(8)
        }
    }
    for (cat in COMPONENT_CATEGORIES) {
        val item = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40)).apply { bottomMargin = dp(4) }
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            addView(ImageView(context).apply {
                setImageResource(cat.iconRes)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
            setOnClickListener {
                setActiveCategory(cat.id)
                sectionViews[cat.id]?.let { target ->
                    contentScroll.post { contentScroll.smoothScrollTo(0, target.top) }
                }
            }
        }
        rail.addView(item)
        railIcons[cat.id] = item
    }
    setActiveCategory(COMPONENT_CATEGORIES.first().id)

    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val query = s?.toString()?.trim()?.lowercase().orEmpty()
            for (cat in COMPONENT_CATEGORIES) {
                val matches = query.isEmpty() || cat.label.lowercase().contains(query)
                sectionViews[cat.id]?.visibility = if (matches) View.VISIBLE else View.GONE
                railIcons[cat.id]?.alpha = if (matches) 1f else 0.35f
            }
        }
    })

    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(rail)
        addView(contentScroll)
    })

    dialog.setOnDismissListener { onDismiss() }
    dialog.setContentView(root)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    dialog.show()
}