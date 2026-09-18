package org.example.test

// Which of the two Buttons-panel previews (see buildButtonCategoryPanel() in
// ComponentsPanel.kt) a placed PartKind.BUTTON part should be drawn as. Null (the default) means
// "not placed via that panel" - such parts keep PartKind.BUTTON's own generic fill/border/text
// (see SketchCanvasView.drawPart()).
enum class ButtonStyle { FRAMELESS, FRAMED }

// Which segment of the Design/Prototype toggle at the top of the Button object panel (see
// buildButtonObjectPanelContent() in ButtonObjectPanel.kt, wired up in SketchActivity's
// openButtonObjectPanel()) a placed Button part currently has selected. Stored on the part
// itself, rather than only in the panel's UI state, so reopening the panel on a given button
// later shows whichever mode it was last left on instead of always resetting to Design.
// CUSTOM is customButton, the frameless icon button beside the Design/Prototype segments -
// selecting it is mutually exclusive with Design/Prototype (same as Design vs. Prototype
// themselves), and additionally flips the panel's top corners to flat/square with a localized
// edge shadow under customButton - see updateButtonObjectPanelCornerState() in SketchActivity.
enum class ButtonObjectMode { DESIGN, PROTOTYPE, CUSTOM }

data class SketchPart(
    val id: Long,
    val kind: PartKind,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var label: String = "",
    var fontSize: Float = 0f,
    
    
    var groupId: Long? = null,
    
    
    var locked: Boolean = false,
    
    
    var hidden: Boolean = false,
    
    
    
    var name: String = "",
    var buttonStyle: ButtonStyle? = null,
    // Which Align option (see buildButtonCategoryPanel()'s AlignOptionEntry ids in
    // ComponentsPanel.kt: "Justify"/"Start"/"End"/"Centered"/"Stack") this button's label should
    // be drawn with. Only meaningful for buttonStyle == FRAMED, matching the panel's own preview
    // (see drawFramedButtonLabel() in SketchCanvasView.kt).
    var buttonAlign: String = "Centered",
    // See ButtonObjectMode above. Defaults to Design, matching the Button object panel's own
    // default active segment.
    var objectPanelMode: ButtonObjectMode = ButtonObjectMode.DESIGN,
)