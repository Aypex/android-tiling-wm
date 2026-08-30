package dev.atwm.tilingwm.model

sealed class BarItem {
    data class PinnedApp(
        val packageName: String,
        val componentName: String,
        val label: String
    ) : BarItem()

    data class Folder(
        val id: String,
        val name: String,
        val apps: List<PinnedApp>
    ) : BarItem()
}
