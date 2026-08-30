package dev.atwm.tilingwm.util

import android.content.Context
import dev.atwm.tilingwm.model.BarItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TaskbarPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)

    fun getBarItems(): List<BarItem> {
        val json = prefs.getString("bar_items", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { parseBarItem(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setBarItems(items: List<BarItem>) {
        val array = JSONArray()
        items.forEach { array.put(serializeBarItem(it)) }
        prefs.edit().putString("bar_items", array.toString()).apply()
    }

    fun getLayoutIndex(): Int = prefs.getInt("layout_index", 0)
    fun setLayoutIndex(index: Int) = prefs.edit().putInt("layout_index", index).apply()

    fun getStartButtonIconPath(): String? = prefs.getString("start_icon_path", null)
    fun setStartButtonIconPath(path: String?) = prefs.edit().putString("start_icon_path", path).apply()

    fun getWindowGap(): Int = prefs.getInt("window_gap", 0)
    fun setWindowGap(gap: Int) = prefs.edit().putInt("window_gap", gap).apply()

    fun getMasterRatio(): Float = prefs.getFloat("master_ratio", 0.55f)
    fun setMasterRatio(ratio: Float) = prefs.edit().putFloat("master_ratio", ratio).apply()

    /** true = start button on right side (toggle on left). Default false (start on left). */
    fun getStartOnRight(): Boolean = prefs.getBoolean("start_on_right", false)
    fun setStartOnRight(right: Boolean) = prefs.edit().putBoolean("start_on_right", right).apply()

    fun getAccentColor(): Int = prefs.getInt("accent_color", 0xFF4FC3F7.toInt())
    fun setAccentColor(color: Int) = prefs.edit().putInt("accent_color", color).apply()

    private fun parseBarItem(obj: JSONObject): BarItem? {
        return when (obj.optString("type")) {
            "app" -> BarItem.PinnedApp(
                packageName = obj.getString("packageName"),
                componentName = obj.getString("componentName"),
                label = obj.getString("label")
            )
            "folder" -> {
                val appsArray = obj.getJSONArray("apps")
                val apps = (0 until appsArray.length()).mapNotNull {
                    parseBarItem(appsArray.getJSONObject(it)) as? BarItem.PinnedApp
                }
                BarItem.Folder(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    apps = apps
                )
            }
            else -> null
        }
    }

    private fun serializeBarItem(item: BarItem): JSONObject {
        return when (item) {
            is BarItem.PinnedApp -> JSONObject().apply {
                put("type", "app")
                put("packageName", item.packageName)
                put("componentName", item.componentName)
                put("label", item.label)
            }
            is BarItem.Folder -> JSONObject().apply {
                put("type", "folder")
                put("id", item.id)
                put("name", item.name)
                val appsArray = JSONArray()
                item.apps.forEach { appsArray.put(serializeBarItem(it)) }
                put("apps", appsArray)
            }
        }
    }

    companion object {
        fun generateFolderId(): String = UUID.randomUUID().toString().take(8)
    }
}
