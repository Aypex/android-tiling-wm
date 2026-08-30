package dev.atwm.tilingwm.engine

import android.content.res.Configuration
import android.graphics.Rect
import dev.atwm.tilingwm.model.TilingConfig

class ColumnsLayout : LayoutStrategy {
    override fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect> {
        val gap = config.windowGap
        val results = mutableListOf<Rect>()

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape: side-by-side columns
            val colWidth = (usableArea.width() - gap * (taskCount - 1)) / taskCount
            for (i in 0 until taskCount) {
                val left = usableArea.left + i * (colWidth + gap)
                val right = if (i == taskCount - 1) usableArea.right else left + colWidth
                results.add(Rect(left, usableArea.top, right, usableArea.bottom))
            }
        } else {
            // Portrait: full-width stacked rows
            val rowHeight = (usableArea.height() - gap * (taskCount - 1)) / taskCount
            for (i in 0 until taskCount) {
                val top = usableArea.top + i * (rowHeight + gap)
                val bottom = if (i == taskCount - 1) usableArea.bottom else top + rowHeight
                results.add(Rect(usableArea.left, top, usableArea.right, bottom))
            }
        }

        return results
    }
}
