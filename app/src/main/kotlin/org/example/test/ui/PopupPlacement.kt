package org.example.test.ui

import android.graphics.Rect
import android.view.View

object PopupPlacement {

    data class Location(val x: Int, val y: Int)

    fun below(
        anchor: View,
        width: Int,
        height: Int,
        safeArea: Rect,
        gapPx: Int = 0,
    ): Location {
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val anchorLeft = anchorLoc[0]
        val anchorTop = anchorLoc[1]
        val anchorBottom = anchorTop + anchor.height

        val spaceBelow = safeArea.bottom - anchorBottom
        val spaceAbove = anchorTop - safeArea.top

        val y = if (height + gapPx <= spaceBelow || spaceBelow >= spaceAbove) {
            (anchorBottom + gapPx).coerceAtMost((safeArea.bottom - height).coerceAtLeast(safeArea.top))
        } else {
            (anchorTop - gapPx - height).coerceAtLeast(safeArea.top)
        }

        val minX = safeArea.left
        val maxX = (safeArea.right - width).coerceAtLeast(minX)
        val x = anchorLeft.coerceIn(minX, maxX)

        return Location(x, y)
    }
}
