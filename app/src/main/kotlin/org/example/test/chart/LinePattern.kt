package org.example.test.chart

enum class LinePattern(val label: String) {
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted");

    companion object {
        val selectable: List<LinePattern> = listOf(SOLID, DASHED, DOTTED)
    }
}
