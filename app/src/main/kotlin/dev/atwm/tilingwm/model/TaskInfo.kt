package dev.atwm.tilingwm.model

import android.graphics.Rect

data class TaskInfo(
    val taskId: Int,
    val packageName: String,
    val bounds: Rect,
    val windowingMode: Int  // 1=FULLSCREEN, 5=FREEFORM
)
