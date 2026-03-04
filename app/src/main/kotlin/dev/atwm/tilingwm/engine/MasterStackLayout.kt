package dev.atwm.tilingwm.engine

import android.content.res.Configuration
import android.graphics.Rect
import dev.atwm.tilingwm.model.TilingConfig

class MasterStackLayout : LayoutStrategy {
    override fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect> {
        val gap = config.windowGap
        val results = mutableListOf<Rect>()

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            val masterWidth = ((usableArea.width() - gap) * config.masterRatio).toInt()
            val stackCount = taskCount - 1

            // Master
            results.add(Rect(
                usableArea.left,
                usableArea.top,
                usableArea.left + masterWidth,
                usableArea.bottom
            ))

            // Stack — divide right column evenly
            val stackLeft = usableArea.left + masterWidth + gap
            val stackHeight = (usableArea.height() - gap * (stackCount - 1)) / stackCount
            for (i in 0 until stackCount) {
                val top = usableArea.top + i * (stackHeight + gap)
                val bottom = if (i == stackCount - 1) usableArea.bottom
                             else top + stackHeight
                results.add(Rect(stackLeft, top, usableArea.right, bottom))
            }
        } else {
            // Landscape: master on top, stack splits bottom
            val masterHeight = ((usableArea.height() - gap) * config.masterRatio).toInt()
            val stackCount = taskCount - 1

            // Master
            results.add(Rect(
                usableArea.left,
                usableArea.top,
                usableArea.right,
                usableArea.top + masterHeight
            ))

            // Stack — divide bottom row evenly
            val stackTop = usableArea.top + masterHeight + gap
            val stackWidth = (usableArea.width() - gap * (stackCount - 1)) / stackCount
            for (i in 0 until stackCount) {
                val left = usableArea.left + i * (stackWidth + gap)
                val right = if (i == stackCount - 1) usableArea.right
                            else left + stackWidth
                results.add(Rect(left, stackTop, right, usableArea.bottom))
            }
        }

        return results
    }
}
