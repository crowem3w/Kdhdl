package org.example.test

import android.content.Context











data class AppPalette(
    val background: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceMuted: Int,
    val navBarBackground: Int,
    val navFrameFill: Int,
    val navIconTint: Int,
    val navLabelEdge: Int,
    val navLabelMedium: Int,
    val navLabelCenter: Int,
    


    val navGlow: Int,
)

object AppTheme {
    
    private const val AZURE = 0xFF0080FF.toInt()

    private val DARK = AppPalette(
        background = 0xFF121212.toInt(),
        surface = 0xFF1E1E1E.toInt(),
        onSurface = 0xFFF5F5F4.toInt(),
        onSurfaceMuted = 0xFFA8A29E.toInt(),
        navBarBackground = 0xFF121212.toInt(),
        navFrameFill = 0xFF262626.toInt(),
        navIconTint = 0xFFFFFFFF.toInt(),
        navLabelEdge = 0xFF6E6A66.toInt(),
        navLabelMedium = 0xFF9C9691.toInt(),
        navLabelCenter = 0xFFF5F5F4.toInt(),
        navGlow = AZURE,
    )

    private val LIGHT = AppPalette(
        background = 0xFFF3F2F0.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        onSurface = 0xFF1C1B1F.toInt(),
        onSurfaceMuted = 0xFF6E6A66.toInt(),
        navBarBackground = 0xFFF3F2F0.toInt(),
        navFrameFill = 0xFFFFFFFF.toInt(),
        navIconTint = 0xFF1C1B1F.toInt(),
        navLabelEdge = 0xFFA19C97.toInt(),
        navLabelMedium = 0xFF79746F.toInt(),
        navLabelCenter = 0xFF1C1B1F.toInt(),
        navGlow = AZURE,
    )

    fun of(context: Context): AppPalette =
        if (ThemeManager.isDarkMode(context)) DARK else LIGHT
}