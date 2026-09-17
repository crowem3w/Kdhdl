package org.example.test

/**
 * The handful of screen types the (+) tile in the page-thumbnails row lets you create.
 * Each maps to an icon used on its thumbnail tile.
 */
enum class ScreenPageType(val label: String, val iconRes: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    ONBOARDING("Onboarding", R.drawable.ic_cat_prototype),
    SPLASH("Splash", R.drawable.ic_play),
    BLANK("Blank", R.drawable.ic_frame),
}

/**
 * One entry in the project's page list, shown as a tile in the Screens panel's thumbnails row.
 * Only one ScreenPage per ScreenPageType may exist at a time (enforced in
 * SketchActivity.addScreenPage / the type picker). Each page has its own independent
 * SketchCanvasView - see screenCanvases/getOrCreateCanvas/switchToScreenPage in SketchActivity -
 * so selecting a page swaps in that screen's own content rather than sharing one canvas.
 */
data class ScreenPage(
    val id: Long,
    val type: ScreenPageType,
    val name: String = type.label,
)
