package org.example.test

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

object CodeGenerator {


    fun generateProjectZip(
        context: Context,
        parts: List<SketchPart>,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        density: Float,
    ): File {
        val workDir = File(context.cacheDir, "generated_project").apply {
            deleteRecursively()
            mkdirs()
        }


        copyAssetDir(context, "boilerplate", workDir)


        val wDp = (canvasWidthPx / density).roundToInt()
        val hDp = (canvasHeightPx / density).roundToInt()

        File(workDir, "app/src/main/res/layout/activity_main.xml")
            .writeText(buildLayoutXml(parts, density))

        File(workDir, "app/src/main/res/values/strings.xml")
            .writeText(buildStringsXml(parts))

        File(workDir, "app/build.gradle.kts")
            .writeText(buildGradleKts())

        File(workDir, "app/src/main/res/values/themes.xml")
            .writeText(buildThemesXml())


        File(workDir, "app/src/main/kotlin/org/example/test/MainActivity.kt").let { f ->
            val original = f.readText()
            f.writeText(
                original.replaceFirst(
                    "package org.example.test",
                    "package org.example.test\n\n// Generated from a ${wDp}x${hDp}dp sketch with ${parts.size} part(s).",
                )
            )
        }


        val zipFile = File(context.cacheDir, "generated_app.zip")
        zipDirectory(workDir, zipFile)
        return zipFile
    }



    private fun buildLayoutXml(parts: List<SketchPart>, density: Float): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine(
            """<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"""" +
                """
    android:layout_width="match_parent"
    android:layout_height="match_parent">
"""
        )

        if (parts.isEmpty()) {
            sb.appendLine(
                """
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:textSize="24sp"
        android:text="@string/hello_world" />
"""
            )
        } else {
            parts.sortedBy { it.y }.forEachIndexed { i, part ->
                sb.appendLine(viewXmlFor(part, i, density))
            }
        }

        sb.appendLine("</FrameLayout>")
        return sb.toString()
    }

    private fun viewXmlFor(part: SketchPart, index: Int, density: Float): String {
        val marginStart = (part.x / density).roundToInt()
        val marginTop = (part.y / density).roundToInt()
        val wDp = (part.w / density).roundToInt()
        val hDp = (part.h / density).roundToInt()
        val id = "part_$index"
        val labelRes = "@string/label_$index"
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
        android:text="$labelRes" />"""

            PartKind.FAB -> """
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:contentDescription="$labelRes" />"""

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
        android:hint="$labelRes">

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
        android:text="$labelRes" />"""

            PartKind.CHECKBOX -> """
    <CheckBox
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:text="$labelRes" />"""

            PartKind.SWITCH -> """
    <com.google.android.material.materialswitch.MaterialSwitch
        android:id="@+id/$id"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:text="$labelRes" />"""

            PartKind.TOP_APP_BAR -> """
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/$id"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:layout_gravity="top"
        android:title="$labelRes" />"""

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
        android:text="$labelRes" />"""

            PartKind.IMAGE -> """
    <ImageView
        android:id="@+id/$id"
        android:layout_width="${wDp}dp"
        android:layout_height="${hDp}dp"
        android:layout_gravity="$gravity"
        $marginAttrs
        android:scaleType="centerCrop"
        android:background="#E6E0E9"
        android:contentDescription="$labelRes" />"""
        }
    }



    private fun buildStringsXml(parts: List<SketchPart>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("<resources>")
        sb.appendLine("""    <string name="app_name">Generated App</string>""")
        sb.appendLine("""    <string name="hello_world">Hello World!</string>""")
        parts.sortedBy { it.y }.forEachIndexed { i, part ->
            val text = part.label.ifBlank { part.kind.displayLabel }.xmlEscape()
            sb.appendLine("""    <string name="label_$i">$text</string>""")
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