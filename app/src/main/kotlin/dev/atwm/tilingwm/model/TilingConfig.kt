package dev.atwm.tilingwm.model

data class TilingConfig(
    val masterRatio: Float = 0.55f,
    val statusBarHeight: Int = 100,
    val navBarHeight: Int = 100,
    val windowGap: Int = 0,
    val excludedPackages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "dev.atwm.tilingwm"
    )
)
