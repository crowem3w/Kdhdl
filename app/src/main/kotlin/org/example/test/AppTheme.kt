package org.example.test

import android.content.Context

/**
 * Color palette for the app shell (Home / Templates / Projects / Profile /
 * Settings + the bottom nav). These screens paint their own colors in code
 * rather than through XML theme attrs, so they resolve their palette here
 * based on [ThemeManager]'s saved preference.
 *
 * The Sketch canvas editor is intentionally excluded — it behaves like a
 * design surface (dark workspace regardless of app theme), similar to most
 * design tools.
 */
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
    /** Azure accent used for the selected-tab glow (elevation shadow tint +
     * border stroke). Non-selected frames use the same hue at a much lower
     * intensity so the whole strip reads as one coherent visual hierarchy. */
    val navGlow: Int,
)

object AppTheme {
    /** Azure — the single glow color shared by both themes. */
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
