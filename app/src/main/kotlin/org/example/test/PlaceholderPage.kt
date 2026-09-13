package org.example.test

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Simple "coming soon" placeholder used for the tabs that don't have their
 * content implemented yet (Templates, Projects, Profile, Settings).
 */
fun buildPlaceholderPage(context: Context, title: String): FrameLayout {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val mutedColor = 0xFFA8A29E.toInt()
    val onColor = 0xFFF5F5F4.toInt()

    val root = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(0xFF121212.toInt())
    }

    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply { setMargins(dp(32), 0, dp(32), 0) }
    }

    column.addView(TextView(context).apply {
        text = title
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(onColor)
        gravity = Gravity.CENTER
    })

    column.addView(TextView(context).apply {
        text = "Coming soon."
        textSize = 15f
        setTextColor(mutedColor)
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, 0)
    })

    root.addView(column)
    return root
}
