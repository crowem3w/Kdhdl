package org.example.test.chart

import android.content.res.Resources
import android.util.TypedValue

object ChartLayoutMetrics {
    const val PRICE_AXIS_WIDTH_DP = 64f
    const val TIME_AXIS_HEIGHT_DP = 20f

    const val VOLUME_HEIGHT_RATIO = 0f

    private fun dp(resources: Resources, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    fun priceAxisWidthPx(resources: Resources): Float = dp(resources, PRICE_AXIS_WIDTH_DP)

    fun timeAxisHeightPx(resources: Resources): Float = dp(resources, TIME_AXIS_HEIGHT_DP)

    fun priceAreaHeightPx(chartContentHeightPx: Float): Float =
        chartContentHeightPx * (1f - VOLUME_HEIGHT_RATIO)
}
