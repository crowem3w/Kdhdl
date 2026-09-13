package org.example.test

data class SketchPart(
    val id: Long,
    val kind: PartKind,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var label: String = "",
    var fontSize: Float = 0f,
    // Shared by all members of a multi-select "Group" action (see SketchCanvasView -
    // toggleGroupSelection); null means the part isn't part of any group.
    var groupId: Long? = null,
    // Set via the multi-select "Lock" action. Locked parts can still be selected (individually
    // or via a marquee box) but can't be dragged or resized.
    var locked: Boolean = false,
    // Set via the multi-select "Hide" action. Hidden parts are skipped entirely by drawing and
    // hit-testing, so they're invisible and untouchable until unhidden.
    var hidden: Boolean = false,
)