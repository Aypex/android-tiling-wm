package dev.atwm.tilingwm.engine

import android.graphics.Rect
import dev.atwm.tilingwm.model.LayoutBounds
import dev.atwm.tilingwm.model.TaskInfo
import dev.atwm.tilingwm.model.TilingConfig

class TilingEngine(
    private val config: TilingConfig,
    var strategy: LayoutStrategy
) {
    /**
     * Given visible tasks and screen dimensions, compute target bounds for each.
     * Filters out excluded packages and non-freeform tasks.
     * Returns empty list if 0-1 tileable tasks (single task gets no resize).
     */
    fun computeLayout(
        tasks: List<TaskInfo>,
        screenWidth: Int,
        screenHeight: Int,
        orientation: Int
    ): List<LayoutBounds> {
        val tileable = tasks.filter { it.packageName !in config.excludedPackages }

        if (tileable.size < 2) return emptyList()

        val bottomInset = config.navBarHeight +
            if (config.taskbarEnabled) config.taskbarHeightPx else 0

        val usableArea = Rect(
            0,
            config.statusBarHeight,
            screenWidth,
            screenHeight - bottomInset
        )

        val bounds = strategy.calculateBounds(usableArea, tileable.size, config, orientation)

        return tileable.zip(bounds) { task, rect ->
            LayoutBounds(task.taskId, rect)
        }
    }
}
