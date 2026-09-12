package org.example.test

import android.graphics.Color

enum class PartKind(
    val displayLabel: String,
    val defaultW: Float,
    val defaultH: Float,
    val fillColor: Int,
    val cornerRadius: Float,
    val promptNoun: String,
    val hasBorder: Boolean = true,
) {
    BUTTON("Button", 140f, 56f, Color.parseColor("#EADDFF"), 28f, "filled button"),
    FAB("FAB", 64f, 64f, Color.parseColor("#D0BCFF"), 20f, "floating action button"),
    CARD("Card", 200f, 140f, Color.parseColor("#FEF7FF"), 16f, "card"),
    TEXT_FIELD("Text field", 220f, 56f, Color.parseColor("#F7F2FA"), 8f, "outlined text field"),
    CHIP("Chip", 96f, 40f, Color.parseColor("#E8DEF8"), 20f, "chip"),
    CHECKBOX("Checkbox", 40f, 40f, Color.parseColor("#FFFFFF"), 6f, "checkbox"),
    SWITCH("Switch", 64f, 36f, Color.parseColor("#E8DEF8"), 18f, "switch"),
    TOP_APP_BAR("Top app bar", 0f, 64f, Color.parseColor("#ECE6F0"), 0f, "top app bar"),
    NAV_BAR("Nav bar", 0f, 80f, Color.parseColor("#ECE6F0"), 0f, "bottom navigation bar"),
    TEXT("Text", 160f, 32f, Color.TRANSPARENT, 0f, "text label", hasBorder = false),
    IMAGE("Image", 200f, 120f, Color.parseColor("#E6E0E9"), 8f, "image placeholder"),
}