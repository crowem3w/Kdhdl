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
    /** Translucent fill of the floating glass dock itself (the frosted pill). */
    val navGlassFill: Int,
    /** Faint 1px edge highlight that sells the "glass" read on the pill. */
    val navGlassStroke: Int,
    /** Shadow tint for the pill's own soft elevation. */
    val navGlassShadow: Int,
    /** Muted gray used for every inactive item's thin-line icon + label. */
    val navInactiveTint: Int,
    /** Bright background of the raised floating button for the active item. */
    val navActiveButtonFill: Int,
    /** Dark tint for the active item's filled icon, sitting on the bright button. */
    val navActiveIconTint: Int,
    /** Strong, high-contrast color for the active item's bold label. */
    val navActiveLabelTint: Int,
    /** Stronger shadow tint for the raised active button, vs. the pill's own shadow. */
    val navActiveButtonShadow: Int,
)

object AppTheme {
    private val DARK = AppPalette(
        background = 0xFF121212.toInt(),
        surface = 0xFF1E1E1E.toInt(),
        onSurface = 0xFFF5F5F4.toInt(),
        onSurfaceMuted = 0xFFA8A29E.toInt(),
        navGlassFill = 0x33FFFFFF,
        navGlassStroke = 0x40FFFFFF,
        navGlassShadow = 0xB3000000.toInt(),
        navInactiveTint = 0xFF9C9691.toInt(),
        navActiveButtonFill = 0xFFF5F5F4.toInt(),
        navActiveIconTint = 0xFF1C1B1F.toInt(),
        navActiveLabelTint = 0xFFF5F5F4.toInt(),
        navActiveButtonShadow = 0xCC000000.toInt(),
    )

    private val LIGHT = AppPalette(
        background = 0xFFF3F2F0.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        onSurface = 0xFF1C1B1F.toInt(),
        onSurfaceMuted = 0xFF6E6A66.toInt(),
        navGlassFill = 0xB3FFFFFF.toInt(),
        navGlassStroke = 0x66FFFFFF,
        navGlassShadow = 0x33000000,
        navInactiveTint = 0xFFA19C97.toInt(),
        navActiveButtonFill = 0xFFFFFFFF.toInt(),
        navActiveIconTint = 0xFF1C1B1F.toInt(),
        navActiveLabelTint = 0xFF1C1B1F.toInt(),
        navActiveButtonShadow = 0x40000000,
    )

    fun of(context: Context): AppPalette =
        if (ThemeManager.isDarkMode(context)) DARK else LIGHT
}
