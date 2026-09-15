package org.example.test

import kotlin.math.roundToInt

object PromptGenerator {

    /**
     * Builds a single prompt describing every screen in [screens] plus, when more than one
     * screen exists, the navigation flow between them: Splash auto-advances to Onboarding
     * (first run only, persisted) or Home; Home is the default screen on every later launch;
     * Blank is a freeform screen not part of that automatic flow. Mirrors the single-Activity +
     * Fragments architecture CodeGenerator scaffolds, so a prompt built from the same sketch
     * describes the same app CodeGenerator would export.
     */
    fun build(screens: List<ScreenExport>, density: Float): String {
        val nonEmpty = screens.filter { it.parts.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            return "Build a Material 3 Expressive Android screen. Nothing has been sketched yet — " +
                "long-press a screen's blank canvas to add some parts, then generate the prompt again."
        }

        val lines = StringBuilder()
        lines.appendLine("Build this as a real, usable Android app in Material 3 Expressive style.")
        lines.appendLine(
            "Each screen below is a rough sketch of intent, not a pixel-exact spec — use standard " +
                "Material 3 components, sensible defaults, and complete anything a real app of this " +
                "kind would need. Support light and dark mode."
        )

        if (screens.size > 1) {
            lines.appendLine()
            lines.appendLine("Use a single-Activity app with one Fragment per screen below. Navigation:")
            appendNavigationFlow(lines, screens.map { it.type }.toSet())
        }

        screens.forEach { screen ->
            lines.appendLine()
            lines.appendLine("## ${screen.name} screen")
            if (screen.parts.isEmpty()) {
                lines.appendLine("Nothing sketched on this screen yet — leave it as a simple placeholder for now.")
            } else {
                val wDp = (screen.canvasWidthPx / density).roundToInt()
                val hDp = (screen.canvasHeightPx / density).roundToInt()
                lines.appendLine("Target a phone screen (about ${wDp}×${hDp}dp). Parts, top to bottom:")
                screen.parts.sortedBy { it.y }.forEachIndexed { i, part ->
                    lines.appendLine("${i + 1}. ${describe(part, screen.canvasWidthPx, screen.canvasHeightPx, density)}")
                }
            }
        }

        lines.appendLine()
        lines.appendLine("General:")
        lines.appendLine("- Use Material 3 color roles and dynamic color where available; standard ripple/elevation/state-layer behavior on every component.")
        lines.appendLine("- Keep spacing consistent with 8dp/16dp margins.")
        lines.appendLine("- Make every interactive part actually functional, not just decorative.")
        return lines.toString().trim()
    }

    /**
     * Describes how the screens hand off to one another, based on which ScreenPageTypes are
     * actually present. Only Splash/Onboarding/Home carry navigation meaning; Blank is called
     * out as a screen the app doesn't route to automatically.
     */
    private fun appendNavigationFlow(lines: StringBuilder, types: Set<ScreenPageType>) {
        val hasSplash = ScreenPageType.SPLASH in types
        val hasOnboarding = ScreenPageType.ONBOARDING in types
        val hasHome = ScreenPageType.HOME in types
        val hasBlank = ScreenPageType.BLANK in types

        val postSplashTarget = when {
            hasOnboarding -> "Onboarding the very first time the app is opened after install, or straight to Home on every later launch"
            hasHome -> "Home"
            else -> "the next screen"
        }
        if (hasSplash) {
            lines.appendLine("- App launches to Splash. After a short delay (about 1–1.5s) it automatically continues to $postSplashTarget.")
        }
        if (hasOnboarding) {
            lines.appendLine(
                "- Onboarding is shown only once per install" +
                    (if (hasSplash) "" else " (the first time the app is opened)") +
                    ". Persist a \"has completed onboarding\" flag (e.g. SharedPreferences) and set it once the " +
                    "user finishes/dismisses it, then continue to" + (if (hasHome) " Home." else " the app's main screen.")
            )
        }
        if (hasHome) {
            lines.appendLine("- Home is the default screen shown whenever the app is reopened, once the first-run flow above (if any) has completed.")
        }
        if (hasBlank) {
            lines.appendLine("- Blank is a freeform screen, not part of the automatic flow above — wire up how it's reached as needed.")
        }
    }

    private fun describe(part: SketchPart, canvasW: Int, canvasH: Int, density: Float): String {
        val pos = positionOf(part, canvasW, canvasH)
        val wDp = (part.w / density).roundToInt()
        val hDp = (part.h / density).roundToInt()
        val label = if (part.label.isNotBlank()) " labeled \"${part.label}\"" else ""
        val noun = part.kind.promptNoun.replaceFirstChar { it.uppercase() }
        return "$noun$label, $pos, about ${wDp}×${hDp}dp."
    }

    private fun positionOf(part: SketchPart, canvasW: Int, canvasH: Int): String {
        val cx = part.x + part.w / 2f
        val cy = part.y + part.h / 2f
        val h = when {
            cy < canvasH / 3f -> "top"
            cy < canvasH * 2f / 3f -> "middle"
            else -> "bottom"
        }
        val v = when {
            cx < canvasW / 3f -> "left"
            cx < canvasW * 2f / 3f -> "center"
            else -> "right"
        }
        return if (h == "middle" && v == "center") "centered on screen" else "$h $v"
    }
}
