package org.example.test

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch







fun buildSettingsPage(activity: MainActivity): FrameLayout {
    val d = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val palette = AppTheme.of(activity)

    val root = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(palette.background)
    }

    val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        setPadding(dp(20), dp(32), dp(20), dp(20))
    }

    column.addView(TextView(activity).apply {
        text = "Settings"
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(palette.onSurface)
    })

    val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(palette.surface)
        }
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    val textColumn = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    textColumn.addView(TextView(activity).apply {
        text = "Dark Mode"
        textSize = 16f
        setTextColor(palette.onSurface)
    })

    textColumn.addView(TextView(activity).apply {
        text = "Switch between light and dark appearance"
        textSize = 13f
        setTextColor(palette.onSurfaceMuted)
        setPadding(0, dp(4), 0, 0)
    })

    val darkModeSwitch = MaterialSwitch(activity).apply {
        isChecked = ThemeManager.isDarkMode(activity)
        setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != ThemeManager.isDarkMode(activity)) {
                ThemeManager.setDarkMode(activity, isChecked)
                activity.recreate()
            }
        }
    }

    row.addView(textColumn)
    row.addView(darkModeSwitch)
    column.addView(row)

    val hapticRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(palette.surface)
        }
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    val hapticTextColumn = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    hapticTextColumn.addView(TextView(activity).apply {
        text = "Haptic Feedback"
        textSize = 16f
        setTextColor(palette.onSurface)
    })

    hapticTextColumn.addView(TextView(activity).apply {
        text = "Vibrate on tab drag and other interactions"
        textSize = 13f
        setTextColor(palette.onSurfaceMuted)
        setPadding(0, dp(4), 0, 0)
    })

    val hapticSwitch = MaterialSwitch(activity).apply {
        isChecked = HapticSettings.isEnabled(activity)
        setOnCheckedChangeListener { _, isChecked ->
            HapticSettings.setEnabled(activity, isChecked)
        }
    }

    hapticRow.addView(hapticTextColumn)
    hapticRow.addView(hapticSwitch)
    column.addView(hapticRow)

    root.addView(column)
    return root
}