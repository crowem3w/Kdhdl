package org.example.test.chart

import org.example.test.R

enum class DrawingTool(val label: String, val iconRes: Int, val pointsRequired: Int) {
    NONE("None", 0, 0),
    TREND_LINE("Trend Line", R.drawable.ic_tool_trend_line, 2),
    RAY("Ray", R.drawable.ic_tool_ray, 2),
    INFO_LINE("Info Line", R.drawable.ic_tool_info_line, 2),
    EXTENDED_LINE("Extended Line", R.drawable.ic_tool_extended_line, 2),
    TREND_ANGLE("Trend Angle", R.drawable.ic_tool_trend_angle, 2),
    HORIZONTAL_LINE("Horizontal Line", R.drawable.ic_tool_horizontal_line, 1),
    HORIZONTAL_RAY("Horizontal Ray", R.drawable.ic_tool_horizontal_ray, 1),
    VERTICAL_LINE("Vertical Line", R.drawable.ic_tool_vertical_line, 1),
    CROSS_LINE("Cross Line", R.drawable.ic_tool_cross_line, 1);

    companion object {

        val selectable: List<DrawingTool> = listOf(
            TREND_LINE,
            RAY,
            INFO_LINE,
            EXTENDED_LINE,
            TREND_ANGLE,
            HORIZONTAL_LINE,
            HORIZONTAL_RAY,
            VERTICAL_LINE,
            CROSS_LINE,
        )
    }
}
