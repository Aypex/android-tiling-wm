package dev.atwm.tilingwm.model

data class TilingConfig(
    val masterRatio: Float = 0.55f,
    val statusBarHeight: Int = 0,
    val navBarHeight: Int = 0,
    val windowGap: Int = 0,
    val taskbarHeightPx: Int = 0,
    val taskbarEnabled: Boolean = true,
    val excludedPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "dev.atwm.tilingwm"
    )
)
