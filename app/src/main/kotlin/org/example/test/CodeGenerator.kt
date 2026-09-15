package org.example.test

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Fills in the boilerplate/ template from a set of ScreenExports. Every screen becomes its own
 * Fragment + layout; MainActivity hosts them in a single fragment container and wires up the
 * Splash → Onboarding (once) → Home flow described in PromptGenerator, when those screen types
 * are present. A single-screen project (just Home, the default) still gets this same shape -
 * one HomeFragment shown immediately, no splash/onboarding code generated since there's nothing
 * for it to do.
 */
object CodeGenerator {

    private const val PACKAGE = "org.example.test"

    fun generateProjectZip(
        context: Context,
        screens: List<ScreenExport>,
        density: Float,
    ): File {
        val workDir = File(context.cacheDir, "generated_project").apply {
            deleteRecursively()
            mkdirs()
        }

        copyAssetDir(context, "boilerplate", workDir)

        val srcRoot = File(workDir, "app/src/main/kotlin/org/example/test")
        val resRoot = File(workDir, "app/src/main/res")

        val fragments = screens.map { buildFragmentInfo(it, density) }
        val types = screens.map { it.type }.toSet()

        File(resRoot, "layout/activity_main.xml").writeText(buildActivityMainXml())

        fragments.forEach { info ->
            File(resRoot, "layout/${info.layoutResName}.xml").writeText(info.layoutXml)
            File(srcRoot, "${info.className}.kt").writeText(info.fragmentKotlin)
        }

        File(resRoot, "values/strings.xml").writeText(buildStringsXml(fragments))
        File(workDir, "app/build.gradle.kts").writeText(buildGradleKts())
        File(resRoot, "values/themes.xml").writeText(buildThemesXml())
        File(srcRoot, "MainActivity.kt").writeText(buildMainActivityKt(fragments, types))

        val zipFile = File(context.cacheDir, "generated_app.zip")
        zipDirectory(workDir, zipFile)
        return zipFile
    }

    // ---- per-screen fragment + layout -------------------------------------------------------

    private class FragmentInfo(
        val type: ScreenPageType,
        val className: String,
        val layoutResName: String,
        val stringPrefix: String,
        val layoutXml: String,
        val fragmentKotlin: String,
        val stringEntries: List<Pair<String, String>>,
    )

    private fun classNameFor(type: ScreenPageType) = when (type) {
        ScreenPageType.HOME -> "HomeFragment"
        ScreenPageType.ONBOARDING -> "OnboardingFragment"
        ScreenPageType.SPLASH -> "SplashFragment"
        ScreenPageType.BLANK -> "BlankFragment"
    }

    private fun layoutResNameFor(type: ScreenPageType) = when (type) {
        ScreenPageType.HOME -> "fragment_home"
        ScreenPageType.ONBOARDING -> "fragment_onboarding"
        ScreenPageType.SPLASH -> "fragment_splash"
        ScreenPageType.BLANK -> "fragment_blank"
    }

    private fun emptyStateLabel(type: ScreenPageType) = when (type) {
        ScreenPageType.HOME -> "Home"
        ScreenPageType.ONBOARDING -> "Onboarding"
        ScreenPageType.SPLASH -> "Splash"
        ScreenPageType.BLANK -> "Blank screen"
    }

    private fun buildFragmentInfo(screen: ScreenExport, density: Float): FragmentInfo {
        val className = classNameFor(screen.type)
        val layoutResName = layoutResNameFor(screen.type)
        val stringPrefix = layoutResName

        val stringEntries = mutableListOf<Pair<String, String>>()
        val layoutXml = buildLayoutXml(screen, density, stringPrefix, stringEntries)
        val fragmentKotlin = when (screen.type) {
            ScreenPageType.SPLASH -> buildSplashFragmentKt(className, layoutResName)
            ScreenPageType.ONBOARDING -> buildOnboardingFragmentKt(className, layoutResName)
            else -> buildPlainFragmentKt(className, layoutResName)
        }

        return FragmentInfo(screen.type, className, layoutResName, stringPrefix, layoutXml, fragmentKotlin, stringEntries)
    }

    private fun buildLayoutXml(
        screen: ScreenExport,
        density: Float,
        stringPrefix: String,
        stringEntries: MutableList<Pair<String, String>>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine(
            """<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"""" +
                """
    android:layout_width="match_parent"
    android:layout_height="match_parent">
"""
        )

        if (screen.parts.isEmpty()) {
            val labelRes = "${stringPrefix}_placeholder"
            stringEntries.add(labelRes to emptyStateLabel(screen.type))
            sb.appendLine(
                """
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:textSize="24sp"
        android:text="@string/$labelRes" />
"""
            )
        } else {
            screen.parts.sortedBy { it.y }.forEachIndexed { i, part ->
                val labelRes = "${stringPrefix}_label_$i"
                stringEntries.add(labelRes to part.label.ifBlank { part.kind.displayLabel })
                sb.appendLine(viewXmlFor(part, "${stringPrefix}_$i", labelRes, density))
            }
        }

        sb.appendLine("</FrameLayout>")
        return sb.toString()
    }

    private fun viewXmlFor(part: SketchPart, id: String, labelRes: String, density: Float): String {
        val marginStart = (part.x / density).roundToInt()
        val marginTop = (part.y / density).roundToInt()
        val wDp = (part.w / density).roundToInt()
        val hDp = (part.h / density).roundToInt()
        val labelResRef = "@string/$labelRes"
        val fullWidth = part.kind == PartKind.TOP_APP_BAR || part.kind == PartKind.NAV_BAR

        val widthAttr = if (fullWidth) "match_parent" else "${wDp}dp"
        val gravity = when (part.kind) {
            PartKind.TOP_APP_BAR -> "top"
            PartKind.NAV_BAR -> "bottom"
            else -> "top|start"
        }
        val marginAttrs = buildString {
            if (!fullWidth) append("""android:layout_marginStart="${marginStart}dp"""" + "\n        ")
            append("""android:layout_marginTop="${marginTop}dp"""")
        }

        return when (part.kind) {
            PartKind.BUTTON -> """
    <com.google.android.material.button.MaterialButton
        android:id="@+id/$id"
        android:layout_width="$widthAttr"
        android:layout_height="${hDp}dp"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:text="$labelResRef" />"""

            PartKind.FAB -> """
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:contentDescription="$labelResRef" />"""

            PartKind.CARD -> """
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/$id"
        android:layout_width="$widthAttr"
        android:layout_height="${hDp}dp"
        android:layout_gravity="$gravity"
        $marginAttrs
        app:cardCornerRadius="16dp"
        app:cardElevation="2dp" />"""

            PartKind.TEXT_FIELD -> """
    <com.google.android.material.textfield.TextInputLayout
        android:id="@+id/${id}_layout"
        style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
        android:layout_width="$widthAttr"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:hint="$labelResRef">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/$id"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
    </com.google.android.material.textfield.TextInputLayout>"""

            PartKind.CHIP -> """
    <com.google.android.material.chip.Chip
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:text="$labelResRef" />"""

            PartKind.CHECKBOX -> """
    <CheckBox
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:text="$labelResRef" />"""

            PartKind.SWITCH -> """
    <com.google.android.material.materialswitch.MaterialSwitch
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:text="$labelResRef" />"""

            PartKind.TOP_APP_BAR -> """
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/$id"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:layout_gravity="top"
        android:title="$labelResRef" />"""

            PartKind.NAV_BAR -> """
    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/$id"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom" />"""

            PartKind.TEXT -> """
    <TextView
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:textSize="16sp"
        android:text="$labelResRef" />"""

            PartKind.IMAGE -> """
    <ImageView
        android:id="@+id/$id"
        android:layout_width="${wDp}dp"
        android:layout_height="${hDp}dp"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:scaleType="centerCrop"
        android:background="#E6E0E9"
        android:contentDescription="$labelResRef" />"""
        }
    }

    // ---- container activity + fragment sources ----------------------------------------------

    private fun buildActivityMainXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/fragment_container"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
    """.trimIndent() + "\n"

    private fun buildPlainFragmentKt(className: String, layoutResName: String): String = """
        package $PACKAGE

        import androidx.fragment.app.Fragment

        class $className : Fragment(R.layout.$layoutResName)
    """.trimIndent() + "\n"

    private fun buildSplashFragmentKt(className: String, layoutResName: String): String = """
        package $PACKAGE

        import android.os.Bundle
        import android.os.Handler
        import android.os.Looper
        import android.view.View
        import androidx.fragment.app.Fragment

        /**
         * Shown briefly on launch, then automatically hands off to MainActivity.advanceFromSplash(),
         * which decides between Onboarding (first run only) and Home.
         */
        class $className : Fragment(R.layout.$layoutResName) {

            private val handler = Handler(Looper.getMainLooper())
            private val advance = Runnable { (activity as? MainActivity)?.advanceFromSplash() }

            override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
                super.onViewCreated(view, savedInstanceState)
                handler.postDelayed(advance, SPLASH_DELAY_MS)
            }

            override fun onDestroyView() {
                handler.removeCallbacks(advance)
                super.onDestroyView()
            }

            private companion object {
                const val SPLASH_DELAY_MS = 1200L
            }
        }
    """.trimIndent() + "\n"

    private fun buildOnboardingFragmentKt(className: String, layoutResName: String): String = """
        package $PACKAGE

        import android.os.Bundle
        import android.view.View
        import android.view.ViewGroup
        import androidx.fragment.app.Fragment
        import com.google.android.material.button.MaterialButton

        /**
         * Shown once, the first time the app is opened. Tapping its button (the sketch's own button,
         * if one was drawn - e.g. "Get started") marks onboarding complete and hands off to
         * MainActivity.finishOnboarding(), which won't show this screen again.
         */
        class $className : Fragment(R.layout.$layoutResName) {

            override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
                super.onViewCreated(view, savedInstanceState)
                val root = view as? ViewGroup ?: return
                findFirstButton(root)?.setOnClickListener {
                    (activity as? MainActivity)?.finishOnboarding()
                }
                // TODO: if this screen has no button, wire up whatever should complete onboarding
                // (e.g. a swipeable pager's "done" state) and call MainActivity.finishOnboarding().
            }

            private fun findFirstButton(group: ViewGroup): MaterialButton? {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is MaterialButton) return child
                    if (child is ViewGroup) findFirstButton(child)?.let { return it }
                }
                return null
            }
        }
    """.trimIndent() + "\n"

    /**
     * MainActivity hosts every screen's Fragment in one container and resolves the startup flow:
     * Splash (if present) -> Onboarding (if present and not yet completed) -> Home. Branches are
     * only emitted for screen types that actually exist in [types], so e.g. a Home-only project
     * gets a MainActivity with no splash/onboarding code at all.
     */
    private fun buildMainActivityKt(fragments: List<FragmentInfo>, types: Set<ScreenPageType>): String {
        val hasSplash = ScreenPageType.SPLASH in types
        val hasOnboarding = ScreenPageType.ONBOARDING in types
        val hasHome = ScreenPageType.HOME in types
        val landingClassName = if (hasHome) classNameFor(ScreenPageType.HOME) else fragments.first().className

        val sb = StringBuilder()
        sb.appendLine("package $PACKAGE")
        sb.appendLine()
        sb.appendLine("import android.os.Bundle")
        if (hasOnboarding) sb.appendLine("import android.content.Context")
        sb.appendLine("import androidx.appcompat.app.AppCompatActivity")
        if (hasSplash || hasOnboarding) sb.appendLine("import androidx.fragment.app.Fragment")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * Single-Activity host: swaps a full-screen Fragment per screen into fragment_container.")
        if (hasSplash) sb.appendLine(" * Launches to Splash, which calls advanceFromSplash() after a short delay.")
        if (hasOnboarding) sb.appendLine(" * Onboarding is shown once per install; completion is persisted in SharedPreferences.")
        sb.appendLine(" */")
        sb.appendLine("class MainActivity : AppCompatActivity() {")
        sb.appendLine()
        if (hasOnboarding) {
            sb.appendLine("""    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }""")
            sb.appendLine()
        }
        sb.appendLine("    override fun onCreate(savedInstanceState: Bundle?) {")
        sb.appendLine("        super.onCreate(savedInstanceState)")
        sb.appendLine("        setContentView(R.layout.activity_main)")
        sb.appendLine("        if (savedInstanceState == null) {")
        val initial = when {
            hasSplash -> "SplashFragment()"
            hasOnboarding -> "resolvePostSplashFragment()"
            else -> "$landingClassName()"
        }
        sb.appendLine("            showFragment($initial)")
        sb.appendLine("        }")
        sb.appendLine("    }")

        if (hasSplash) {
            sb.appendLine()
            sb.appendLine("    /** Called by SplashFragment once its delay elapses. */")
            sb.appendLine("    fun advanceFromSplash() {")
            val postSplash = if (hasOnboarding) "resolvePostSplashFragment()" else "$landingClassName()"
            sb.appendLine("        showFragment($postSplash)")
            sb.appendLine("    }")
        }

        if (hasOnboarding) {
            sb.appendLine()
            sb.appendLine("    private fun resolvePostSplashFragment(): Fragment =")
            sb.appendLine("        if (prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)) $landingClassName() else OnboardingFragment()")
            sb.appendLine()
            sb.appendLine("    /** Called by OnboardingFragment once its completing action is tapped. */")
            sb.appendLine("    fun finishOnboarding() {")
            sb.appendLine("        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()")
            sb.appendLine("        showFragment($landingClassName())")
            sb.appendLine("    }")
        }

        sb.appendLine()
        val fragmentParamType = if (hasSplash || hasOnboarding) "Fragment" else landingClassName
        sb.appendLine("    private fun showFragment(fragment: $fragmentParamType) {")
        sb.appendLine("        supportFragmentManager.beginTransaction()")
        sb.appendLine("            .replace(R.id.fragment_container, fragment)")
        sb.appendLine("            .commit()")
        sb.appendLine("    }")

        if (hasOnboarding) {
            sb.appendLine()
            sb.appendLine("    private companion object {")
            sb.appendLine("""        const val PREFS_NAME = "app_prefs"""")
            sb.appendLine("""        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"""")
            sb.appendLine("    }")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    // ---- shared resources ---------------------------------------------------------------------

    private fun buildStringsXml(fragments: List<FragmentInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("<resources>")
        sb.appendLine("""    <string name="app_name">Generated App</string>""")
        fragments.forEach { info ->
            info.stringEntries.forEach { (name, value) ->
                sb.appendLine("""    <string name="$name">${value.xmlEscape()}</string>""")
            }
        }
        sb.appendLine("</resources>")
        return sb.toString()
    }

    private fun String.xmlEscape() = replace("&", "&amp;").replace("\"", "&quot;")
        .replace("'", "\\'").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildGradleKts(): String = """
        import org.jetbrains.kotlin.gradle.dsl.JvmTarget

        plugins {
            alias(libs.plugins.android.application)
        }

        android {
            namespace = "org.example.test"
            compileSdk = 37

            defaultConfig {
                applicationId = "org.example.test"
                minSdk = 30
                targetSdk = 37
                versionCode = 1
                versionName = "1.0"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }

        kotlin {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }

        dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.appcompat)
            implementation("com.google.android.material:material:1.12.0")
            implementation("androidx.fragment:fragment-ktx:1.8.5")
        }
    """.trimIndent()

    private fun buildThemesXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="Theme.Test" parent="Theme.Material3.DayNight.NoActionBar" />
        </resources>
    """.trimIndent()

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(destDir, child))
        }
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        if (zipFile.exists()) zipFile.delete()
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
