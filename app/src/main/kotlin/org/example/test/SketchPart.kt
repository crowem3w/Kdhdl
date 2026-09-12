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
)