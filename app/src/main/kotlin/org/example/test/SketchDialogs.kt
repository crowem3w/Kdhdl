package org.example.test

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView







fun showPartPickerSheet(
    context: Context,
    title: String,
    kinds: List<PartKind>,
    onPick: (PartKind) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val dialog = BottomSheetDialog(context)
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    root.addView(TextView(context).apply {
        text = title
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    })

    val grid = GridLayout(context).apply { columnCount = 3 }
    for (kind in kinds) {
        grid.addView(MaterialButton(context).apply {
            text = kind.displayLabel
            isAllCaps = false
            textSize = 12f
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp(104)
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            setOnClickListener {
                onPick(kind)
                dialog.dismiss()
            }
        })
    }
    root.addView(grid)

    dialog.setOnDismissListener { onDismiss() }
    dialog.setContentView(ScrollView(context).apply { addView(root) })
    dialog.show()
}



/**
 * Modal shown when the "Text" tab is selected: a floating, rounded white card (matching the
 * white sketch background) with just a shadow at its edges (no stroke/border) so it reads as
 * "lifted" above the canvas. Sizes itself responsively to the available screen width rather than
 * a fixed dp value, and focuses its EditText immediately so the keyboard opens automatically.
 */
fun showTextInputDialog(
    context: Context,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit = {},
): Dialog {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val screenWidth = context.resources.displayMetrics.widthPixels
    val cardMaxWidth = dp(480)
    val cardWidth = minOf(screenWidth - dp(48), cardMaxWidth)

    val dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(true)
    }

    val titleView = TextView(context).apply {
        text = "Add text"
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#1A1A1A"))
    }

    val input = EditText(context).apply {
        hint = "Type your text"
        setHintTextColor(Color.parseColor("#9A9AA5"))
        setTextColor(Color.parseColor("#1A1A1A"))
        textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_DONE
        maxLines = 4
        background = null
    }

    fun finish(confirmed: Boolean) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        dialog.dismiss()
        if (confirmed) onConfirm(input.text.toString()) else onCancel()
    }

    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            finish(confirmed = true)
            true
        } else {
            false
        }
    }

    val cancelButton = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = "Cancel"
        isAllCaps = false
        backgroundTintList = null
        setBackgroundColor(Color.TRANSPARENT)
        cornerRadius = 0
        elevation = 0f
        stateListAnimator = null
        setTextColor(Color.parseColor("#4A4A52"))
        setOnClickListener {
            setTextColor(Color.parseColor("#E53935"))
            finish(confirmed = false)
        }
    }

    val addButton = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = "Add"
        isAllCaps = false
        backgroundTintList = null
        setBackgroundColor(Color.TRANSPARENT)
        cornerRadius = 0
        elevation = 0f
        stateListAnimator = null
        setTextColor(Color.parseColor("#4A4A52"))
        setOnClickListener {
            setTextColor(Color.parseColor("#2E7D32"))
            finish(confirmed = true)
        }
    }

    val buttonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(16), 0, 0)
        addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            addButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            },
        )
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        addView(titleView)
        addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
        addView(buttonRow)
    }

    // 12dp corner radius, white background matching the main (sketch) screen, no stroke/border
    // (strokeWidth = 0) — the card reads via its drop shadow (cardElevation) only.
    val card = MaterialCardView(context).apply {
        radius = dp(12).toFloat()
        cardElevation = dp(8).toFloat()
        strokeWidth = 0
        setCardBackgroundColor(Color.WHITE)
        preventCornerOverlap = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Extra padding around the card so its drop shadow isn't clipped, and so the modal stays
    // comfortably inset from the screen edges on any device size (responsive width above).
    val wrapper = FrameLayout(context).apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        clipChildren = false
        clipToPadding = false
        addView(
            card,
            FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER },
        )
    }

    dialog.setContentView(wrapper)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    dialog.setOnCancelListener { onCancel() }
    dialog.show()

    input.requestFocus()
    input.post {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    return dialog
}


fun showMoreMenu(
    context: Context,
    hasParts: Boolean,
    onGeneratePrompt: () -> Unit,
    onExportProject: () -> Unit,
    onClear: () -> Unit,
) {
    val dialog = BottomSheetDialog(context)
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    root.addView(TextView(context).apply {
        text = "Sketch options"
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    })

    root.addView(MaterialButton(context).apply {
        text = "Generate prompt"
        isAllCaps = false
        isEnabled = hasParts
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener {
            onGeneratePrompt()
            dialog.dismiss()
        }
    })

    root.addView(MaterialButton(context).apply {
        text = "Export project (.zip)"
        isAllCaps = false
        isEnabled = hasParts
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        setOnClickListener {
            onExportProject()
            dialog.dismiss()
        }
    })

    if (hasParts) {
        root.addView(MaterialButton(context).apply {
            text = "Clear canvas"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                onClear()
                dialog.dismiss()
            }
        })
    }

    dialog.setContentView(ScrollView(context).apply { addView(root) })
    dialog.show()
}


fun showPartOptionsDialog(
    context: Context,
    part: SketchPart,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val d = context.resources.displayMetrics.density
    val pad = (16 * d).toInt()
    val input = EditText(context).apply {
        setText(part.label)
        hint = part.kind.displayLabel
        inputType = InputType.TYPE_CLASS_TEXT
        setPadding(pad, pad, pad, pad)
    }
    AlertDialog.Builder(context)
        .setTitle(part.kind.displayLabel)
        .setView(input)
        .setPositiveButton("Save") { _, _ -> onRename(input.text.toString()) }
        .setNegativeButton("Delete") { _, _ -> onDelete() }
        .setNeutralButton("Cancel", null)
        .show()
}


fun showPromptDialog(context: Context, prompt: String) {
    val d = context.resources.displayMetrics.density
    val pad = (20 * d).toInt()
    val textView = TextView(context).apply {
        text = prompt
        setTextIsSelectable(true)
        setPadding(pad, pad, pad, pad)
    }
    AlertDialog.Builder(context)
        .setTitle("Prompt")
        .setView(ScrollView(context).apply { addView(textView) })
        .setPositiveButton("Copy") { _, _ ->
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("M3E prompt", prompt))
            Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Close", null)
        .show()
}