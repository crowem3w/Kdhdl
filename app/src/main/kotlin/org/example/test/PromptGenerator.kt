package org.example.test

import kotlin.math.roundToInt

object PromptGenerator {

    fun build(parts: List<SketchPart>, canvasWidthPx: Int, canvasHeightPx: Int, density: Float): String {
        if (parts.isEmpty()) {
            return "Build a Material 3 Expressive Android screen. Nothing has been sketched yet — " +
                "long-press the blank canvas to add some parts, then generate the prompt again."
        }

        val ordered = parts.sortedBy { it.y }
        val wDp = (canvasWidthPx / density).roundToInt()
        val hDp = (canvasHeightPx / density).roundToInt()

        val lines = StringBuilder()
        lines.appendLine("Build this as a real, usable Android app in Material 3 Expressive style.")
        lines.appendLine(
            "The layout below is a rough sketch of intent, not a pixel-exact spec — use standard " +
                "Material 3 components, sensible defaults, and complete anything a real app of this " +
                "kind would need."
        )
        lines.appendLine()
        lines.appendLine("Target a phone screen (about ${wDp}×${hDp}dp), light and dark mode. Parts, top to bottom:")
        ordered.forEachIndexed { i, part ->
            lines.appendLine("${i + 1}. ${describe(part, canvasWidthPx, canvasHeightPx, density)}")
        }
        lines.appendLine()
        lines.appendLine("General:")
        lines.appendLine("- Use Material 3 color roles and dynamic color where available; standard ripple/elevation/state-layer behavior on every component.")
        lines.appendLine("- Keep spacing consistent with 8dp/16dp margins.")
        lines.appendLine("- Make every interactive part actually functional, not just decorative.")
        return lines.toString().trim()
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