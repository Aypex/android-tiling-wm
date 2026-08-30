package dev.atwm.tilingwm.engine

import android.graphics.Rect
import dev.atwm.tilingwm.model.TilingConfig

class MonocleLayout : LayoutStrategy {
    override fun calculateBounds(
        usableArea: Rect,
        taskCount: Int,
        config: TilingConfig,
        orientation: Int
    ): List<Rect> {
        return (0 until taskCount).map { Rect(usableArea) }
    }
}
