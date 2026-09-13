package org.example.test

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * App shell: hosts the 5 top-level pages (Homepage, Templates, Projects,
 * Profile, Settings) behind a bottom navigation bar. Only Homepage has
 * real content today (the hero + "Create app" action that opens
 * SketchActivity); the other 4 tabs are empty placeholders for now.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pageContainer: FrameLayout
    private val pages = LinkedHashMap<AppTab, ViewGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() so the DayNight theme resolves
        // to the saved preference (and system widgets/status bar match).
        ThemeManager.applySavedMode(this)
        super.onCreate(savedInstanceState)

        // The nav bar is now a floating glass dock that sits *over* the page content
        // (rather than pushing it up in a column), so the root is a plain FrameLayout
        // with the dock added last so it draws on top.
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            clipChildren = false
            clipToPadding = false
        }

        pageContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val bottomNav = BottomNavBar(this).apply {
            onTabSelected = { tab -> showPage(tab) }
        }

        root.addView(pageContainer)
        root.addView(bottomNav, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        setContentView(root)

        buildPages()
        showPage(AppTab.TEMPLATES)
    }

    private fun buildPages() {
        pages[AppTab.HOME] = buildHomePage(this)
        pages[AppTab.TEMPLATES] = buildPlaceholderPage(this, "Templates")
        pages[AppTab.PROJECTS] = buildPlaceholderPage(this, "Projects")
        pages[AppTab.PROFILE] = buildPlaceholderPage(this, "Profile")
        pages[AppTab.SETTINGS] = buildSettingsPage(this)

        pages.forEach { (_, view) ->
            view.visibility = ViewGroup.GONE
            pageContainer.addView(view)
        }
    }

    private fun showPage(tab: AppTab) {
        pages.forEach { (t, view) -> view.visibility = if (t == tab) ViewGroup.VISIBLE else ViewGroup.GONE }
    }
}
