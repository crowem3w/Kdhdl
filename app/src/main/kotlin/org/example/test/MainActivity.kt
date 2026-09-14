package org.example.test

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity







class MainActivity : AppCompatActivity() {

    private lateinit var pageContainer: FrameLayout
    private val pages = LinkedHashMap<AppTab, ViewGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        
        
        ThemeManager.applySavedMode(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            
            
            clipChildren = false
            clipToPadding = false
        }

        pageContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val bottomNav = BottomNavBar(this).apply {
            onTabSelected = { tab -> showPage(tab) }
        }

        root.addView(pageContainer)
        root.addView(bottomNav)
        setContentView(root)

        buildPages()
        showPage(AppTab.HOME)
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