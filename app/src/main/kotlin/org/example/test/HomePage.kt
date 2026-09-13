package org.example.test

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Homepage tab content. This is the app's landing/hero screen; its
 * "Create app" action is what opens the SketchActivity editor.
 */
fun buildHomePage(activity: MainActivity): FrameLayout {
    val d = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val mutedColor = 0xFFA8A29E.toInt()
    val onColor = 0xFFF5F5F4.toInt()

    val page = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(0xFF121212.toInt())
    }

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setPadding(dp(20), dp(20), dp(12), dp(20))
    }

    val leftColumn = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
    }

    leftColumn.addView(TextView(activity).apply {
        text = "/ Design / Prompt / Build"
        textSize = 12f
        setTextColor(mutedColor)
    })

    leftColumn.addView(TextView(activity).apply {
        text = "Turn your ideas\ninto prompt."
        textSize = 28f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(onColor)
        setPadding(0, dp(12), 0, 0)
    })

    leftColumn.addView(TextView(activity).apply {
        text = "A visual builder for age of AI. Design, costumize, and generate prompts apps."
        textSize = 17f
        setTextColor(mutedColor)
        setPadding(0, dp(12), 0, 0)
    })

    leftColumn.addView(MaterialButton(activity).apply {
        text = "Create app  \u2192"
        isAllCaps = false
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20) }
        setOnClickListener {
            activity.startActivity(Intent(activity, SketchActivity::class.java))
        }
    })

    val heroImage = ImageView(activity).apply {
        setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.home_hero))
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
    }

    root.addView(leftColumn)
    root.addView(heroImage)
    page.addView(root)
    return page
}
