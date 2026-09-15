package org.example.test

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

// Screens panel palette (light mode) - matches ComponentsPanel.kt's soft-gray surface so the two
// bottom-sheet panels read as one consistent light system regardless of the app's dark canvas
// chrome.
private val PANEL_PRIMARY_TEXT = Color.parseColor("#1A1B24")
private val PANEL_SECONDARY_TEXT = Color.parseColor("#6B7280")
private val PANEL_SURFACE = Color.WHITE
private val PANEL_BORDER = Color.parseColor("#E5E7EB")

// Same accent used for the canvas page-resize handle while dragging (SketchCanvasView), reused
// here so "selected" reads as the same accent color across the app.
private val ACCENT = Color.parseColor("#6750A4")

private const val THUMB_WIDTH_DP = 52
private const val THUMB_HEIGHT_DP = 92

private data class ScreenPickItem(val kind: PartKind, val iconRes: Int)

private val SCREEN_PICK_ITEMS = listOf(
    ScreenPickItem(PartKind.CARD, R.drawable.ic_frame),
    ScreenPickItem(PartKind.IMAGE, R.drawable.ic_media),
    ScreenPickItem(PartKind.CHIP, R.drawable.ic_cat_actions),
)

/** Handles returned by buildScreensPanelContent() so SketchActivity can re-render the
 *  thumbnails row later (e.g. after a page is added) without rebuilding the whole panel. */
class ScreensPanelViews(val root: View, val thumbnailsContainer: LinearLayout)

/**
 * Builds the Screens tab's panel content: a page-thumbnails row (one tile per created screen,
 * plus a trailing add-page tile) above a light-mode Card/Image/Chip picker that replaces the old
 * showPartPickerSheet() dialog. Lives inside screensContentContainer (see setupScreensPanel() in
 * SketchActivity), which sits inside the screensPanel bottom sheet.
 */
fun buildScreensPanelContent(
    context: Context,
    pages: List<ScreenPage>,
    selectedPageId: Long,
    onPick: (PartKind) -> Unit,
    onClose: () -> Unit,
    onSelectPage: (ScreenPage) -> Unit,
    onAddPageClick: () -> Unit,
): ScreensPanelViews {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        clipChildren = false
        clipToPadding = false
    }

    val closeButton = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        isClickable = true
        isFocusable = true
        contentDescription = "Close"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back_return)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
        setOnClickListener { onClose() }
    }

    val title = TextView(context).apply {
        text = "Screens"
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(PANEL_PRIMARY_TEXT)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        }
    }

    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        }
        addView(closeButton)
        addView(title)
    })

    
    
    
    val thumbnailsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val thumbnailsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(thumbnailsContainer)
    }
    renderScreenThumbnails(thumbnailsContainer, context, pages, selectedPageId, onSelectPage, onAddPageClick)

    
    var thumbnailsExpanded = true
    val chevronIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron_left)
        setColorFilter(PANEL_SECONDARY_TEXT)
        layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
    }
    val chevronButton = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
        isClickable = true
        isFocusable = true
        contentDescription = "Hide page thumbnails"
        addView(chevronIcon)
        setOnClickListener {
            thumbnailsExpanded = !thumbnailsExpanded
            thumbnailsScroll.visibility = if (thumbnailsExpanded) View.VISIBLE else View.GONE
            chevronIcon.rotation = if (thumbnailsExpanded) 0f else 180f
            contentDescription = if (thumbnailsExpanded) "Hide page thumbnails" else "Show page thumbnails"
        }
    }

    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        }
        addView(chevronButton)
        addView(thumbnailsScroll)
    })

    root.addView(TextView(context).apply {
        text = "Add a shape to the canvas"
        textSize = 13f
        setTextColor(PANEL_SECONDARY_TEXT)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        }
    })

    fun buildItemCardBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(PANEL_SURFACE)
    }

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    SCREEN_PICK_ITEMS.forEachIndexed { index, item ->
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = buildItemCardBackground()
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(18), dp(4), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(10)
            }
            setOnClickListener { onPick(item.kind) }
        }
        cell.addView(ImageView(context).apply {
            setImageResource(item.iconRes)
            setColorFilter(PANEL_PRIMARY_TEXT)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                bottomMargin = dp(10)
            }
        })
        cell.addView(TextView(context).apply {
            text = item.kind.displayLabel
            textSize = 12.5f
            setTextColor(PANEL_PRIMARY_TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        row.addView(cell)
    }
    root.addView(row)

    return ScreensPanelViews(root, thumbnailsContainer)
}

/**
 * Repopulates the page-thumbnails row: one tile per entry in [pages] (in creation order), the
 * tile matching [selectedPageId] highlighted, followed by a trailing add-page tile. Called both
 * when the panel content is first built and again by SketchActivity whenever the page list or
 * selection changes (see refreshScreenThumbnails() in SketchActivity).
 */
fun renderScreenThumbnails(
    container: LinearLayout,
    context: Context,
    pages: List<ScreenPage>,
    selectedPageId: Long,
    onSelectPage: (ScreenPage) -> Unit,
    onAddPageClick: () -> Unit,
) {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    container.removeAllViews()
    pages.forEachIndexed { index, page ->
        container.addView(
            buildScreenThumbnailTile(context, page, page.id == selectedPageId) { onSelectPage(page) }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = if (index == 0) 0 else dp(10)
            }
        )
    }
    container.addView(
        buildAddPageTile(context, onAddPageClick).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = if (pages.isEmpty()) 0 else dp(10)
        }
    )
}

private fun buildScreenThumbnailTile(context: Context, page: ScreenPage, selected: Boolean, onClick: () -> Unit): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val tile = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    tile.addView(FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(THUMB_HEIGHT_DP)).apply {
            bottomMargin = dp(6)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(PANEL_SURFACE)
            setStroke(if (selected) dp(2) else dp(1), if (selected) ACCENT else PANEL_BORDER)
        }
        elevation = dp(1).toFloat()
        addView(ImageView(context).apply {
            setImageResource(page.type.iconRes)
            setColorFilter(if (selected) ACCENT else PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
    })

    tile.addView(TextView(context).apply {
        text = page.name
        textSize = 11f
        maxLines = 1
        setTextColor(if (selected) ACCENT else PANEL_SECONDARY_TEXT)
        layoutParams = LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP + 12), ViewGroup.LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER_HORIZONTAL
        ellipsize = android.text.TextUtils.TruncateAt.END
    })

    return tile
}

private fun buildAddPageTile(context: Context, onClick: () -> Unit): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val tile = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        contentDescription = "Add screen"
        setOnClickListener { onClick() }
    }

    tile.addView(FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(THUMB_WIDTH_DP), dp(THUMB_HEIGHT_DP)).apply {
            bottomMargin = dp(6)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), PANEL_BORDER)
            
        }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(PANEL_SECONDARY_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
    })

    
    tile.addView(TextView(context).apply {
        text = " "
        textSize = 11f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })

    return tile
}

/** Small bottom sheet listing the screen types the (+) tile can create. */
fun showScreenTypePicker(context: Context, onPick: (ScreenPageType) -> Unit) {
    val dialog = BottomSheetDialog(context)
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    root.addView(TextView(context).apply {
        text = "Add screen"
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(PANEL_PRIMARY_TEXT)
        setPadding(0, 0, 0, dp(12))
    })

    ScreenPageType.values().forEach { type ->
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                onPick(type)
                dialog.dismiss()
            }
            addView(ImageView(context).apply {
                setImageResource(type.iconRes)
                setColorFilter(PANEL_PRIMARY_TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) }
            })
            addView(TextView(context).apply {
                text = "${type.label} screen"
                textSize = 15f
                setTextColor(PANEL_PRIMARY_TEXT)
            })
        })
    }

    dialog.setContentView(root)
    dialog.show()
}
