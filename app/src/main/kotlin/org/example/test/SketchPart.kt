package org.example.test

// Which of the two Buttons-panel previews (see buildButtonCategoryPanel() in
// ComponentsPanel.kt) a placed PartKind.BUTTON part should be drawn as. Null (the default) means
// "not placed via that panel" - such parts keep PartKind.BUTTON's own generic fill/border/text
// (see SketchCanvasView.drawPart()).
enum class ButtonStyle { FRAMELESS, FRAMED }

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
)