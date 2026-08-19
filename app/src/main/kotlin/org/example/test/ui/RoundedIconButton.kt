package org.example.test.ui

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import org.example.test.R

/**
 * An [android.widget.ImageView] whose corners are clipped to a radius
 * expressed as a **percentage of the view's own size** (min of width/height)
 * rather than a fixed dp value, so the same rounding looks proportionate at
 * any icon size (e.g. `app:cornerRadiusPercent="0.15"` for a 15% radius).
 *
 * Defaults to 15% if not set via XML attribute.
 */
class RoundedIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var cornerRadiusPercent: Float = 0.15f
        set(value) {
            field = value.coerceIn(0f, 0.5f)
            invalidateOutline()
        }

    init {
        clipToOutline = true
        var typedArray: TypedArray? = null
        try {
            typedArray = context.obtainStyledAttributes(attrs, R.styleable.RoundedIconButton, defStyleAttr, 0)
            cornerRadiusPercent = typedArray.getFloat(R.styleable.RoundedIconButton_cornerRadiusPercent, 0.15f)
        } finally {
            typedArray?.recycle()
        }

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radiusPx = minOf(view.width, view.height) * cornerRadiusPercent
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        isClickable = true
        isFocusable = true
    }
}
