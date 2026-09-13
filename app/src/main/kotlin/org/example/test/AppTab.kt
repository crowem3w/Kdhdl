package org.example.test

/**
 * The 5 top-level destinations of the app, each backed by an outline icon
 * (default state) and a "shaded" / filled icon (selected state).
 */
enum class AppTab(
    val label: String,
    val icon: Int,
    val iconSelected: Int,
) {
    HOME(
        label = "Home",
        icon = R.drawable.ic_nav_home,
        iconSelected = R.drawable.ic_nav_home_filled,
    ),
    TEMPLATES(
        label = "Templates",
        icon = R.drawable.ic_nav_templates,
        iconSelected = R.drawable.ic_nav_templates_filled,
    ),
    PROJECTS(
        label = "Projects",
        icon = R.drawable.ic_nav_projects,
        iconSelected = R.drawable.ic_nav_projects_filled,
    ),
    PROFILE(
        label = "Profile",
        icon = R.drawable.ic_nav_profile,
        iconSelected = R.drawable.ic_nav_profile_filled,
    ),
    SETTINGS(
        label = "Settings",
        icon = R.drawable.ic_nav_settings,
        iconSelected = R.drawable.ic_nav_settings_filled,
    ),
}
