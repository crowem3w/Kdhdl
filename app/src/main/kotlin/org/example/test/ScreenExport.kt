package org.example.test

/**
 * One screen's sketch data, handed to PromptGenerator/CodeGenerator by SketchActivity so both
 * generators can see every ScreenPage instead of just the currently active one.
 *
 * Bundles a ScreenPage's identity with the parts drawn on its own SketchCanvasView and that
 * canvas's own pixel size (each screen's SketchCanvasView can size independently - see
 * screenCanvases in SketchActivity).
 */
data class ScreenExport(
    val type: ScreenPageType,
    val name: String,
    val parts: List<SketchPart>,
    val canvasWidthPx: Int,
    val canvasHeightPx: Int,
)
