package dev.atwm.tilingwm.util

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache

object IconCache {

    private lateinit var cache: LruCache<String, Drawable>

    fun init(context: Context) {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        cache = LruCache(cacheSize)
    }

    fun getIcon(context: Context, componentName: ComponentName): Drawable? {
        val key = componentName.flattenToString()
        cache.get(key)?.let { return it }

        return try {
            val pm = context.packageManager
            val info = pm.getActivityInfo(componentName, 0)
            val icon = info.loadIcon(pm)
            cache.put(key, icon)
            icon
        } catch (e: Exception) {
            null
        }
    }

    fun evict(componentName: ComponentName) {
        cache.remove(componentName.flattenToString())
    }

    fun clear() {
        if (::cache.isInitialized) cache.evictAll()
    }
}
