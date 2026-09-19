package org.example.test

import android.content.Context
import android.graphics.Color
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

// Same Material blue used for ComponentsPanel.kt's "Insert instance" primary action button,
// reused here so the Text panel's own primary action reads as the same accent across panels.
private val ADD_TEXT_IDLE_COLOR = Color.parseColor("#2196F3") // Material blue 500
private val ADD_TEXT_PRESSED_COLOR = Color.parseColor("#1976D2") // Material blue 700 - darkens on press

/**
 * Builds the Text tab's panel content: for now, just the top action row at the top of
 * textContentContainer (see setupTextPanel()/openTextPanel() in SketchActivity) - a single
 * full-width "Add text" button, replacing the old showTextInputDialog() flow. Tapping it fires
 * [onAddTextRequested], which SketchActivity wires to place a Text part on the Canvas and close
 * the panel immediately, no text-entry step. More content is expected to be added below this row
 * later.
 */
fun buildTextPanelContent(
    context: Context,
    onAddTextRequested: () -> Unit,
): View {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        clipChildren = false
        clipToPadding = false
    }

    // Top action row: addTextButton (blue frame), full width.
    val topActionsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clipChildren = false
        clipToPadding = false
    }

    // Rounded-square, Material-blue frame - fills the full row width. Corner radius and padding
    // match ComponentsPanel.kt's addToCanvasButton ("Insert instance") frame (12dp radius, 12dp
    // top/bottom padding) so the two primary-action buttons read as the same size/shape across
    // panels.
    val addTextBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(ADD_TEXT_IDLE_COLOR)
    }

    val addTextButton = FrameLayout(context).apply {
        background = addTextBg
        isClickable = true
        isFocusable = true
        contentDescription = "Add text"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }

    // Icon + label laid out horizontally and centered together within the frame - icon on the
    // left, label to its right - with the icon (28dp) sized well beyond the label's own text size
    // (15sp) so it reads as the row's dominant element rather than a small leading glyph.
    val contentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }

    contentRow.addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_tool_text)
        setColorFilter(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            marginEnd = dp(10)
        }
    })

    contentRow.addView(TextView(context).apply {
        text = "Add text"
        setTextColor(Color.WHITE)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })

    addTextButton.addView(contentRow)

    // Same press-darken + quick scale feedback as ComponentsPanel.kt's "Insert instance" button.
    addTextButton.setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                addTextBg.setColor(ADD_TEXT_PRESSED_COLOR)
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                addTextBg.setColor(ADD_TEXT_IDLE_COLOR)
                view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
        }
        false
    }

    addTextButton.setOnClickListener { onAddTextRequested() }

    topActionsRow.addView(addTextButton)
    root.addView(topActionsRow)

    return root
}

