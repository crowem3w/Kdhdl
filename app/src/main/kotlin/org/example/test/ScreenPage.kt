package org.example.test

/**
 * The handful of screen types the (+) tile in the page-thumbnails row lets you create.
 * Each maps to an icon used on its thumbnail tile.
 */
enum class ScreenPageType(val label: String, val iconRes: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    ONBOARDING("Onboarding", R.drawable.ic_cat_prototype),
    SPLASH("Splash", R.drawable.ic_play),
    DEFAULT("Default", R.drawable.ic_frame),
}

/**
 * One entry in the project's page list, shown as a tile in the Screens panel's thumbnails row.
 * This is a lightweight list/selection model only - all pages currently share the same canvas
 * content; selecting a page highlights its tile but does not (yet) swap canvas content per page.
 */
data class ScreenPage(
    val id: Long,
    val type: ScreenPageType,
    val name: String = type.label,
)
