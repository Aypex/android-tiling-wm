package dev.atwm.tilingwm.util

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.UserHandle
import android.view.WindowManager

data class AppEntry(
    val label: String,
    val componentName: ComponentName,
    val packageName: String
)

object AppLauncher {

    // Enumerating + labeling + sorting all launchable apps costs hundreds of ms
    // on a device with many apps (~400ms for 386 apps), and it was re-run on
    // every drawer open. Cache the result and refresh it off the main thread.
    @Volatile
    private var cachedApps: List<AppEntry>? = null

    /** Cached list if already loaded, else null (caller can load async). */
    fun peekCachedApps(): List<AppEntry>? = cachedApps

    /** Kick off a background load so the first drawer open is warm. Idempotent. */
    fun preload(context: Context) {
        if (cachedApps != null) return
        val app = context.applicationContext
        Thread {
            val apps = loadInstalledApps(app)
            cachedApps = apps
        }.apply { isDaemon = true }.start()
    }

    /** Returns the cache if present, otherwise loads synchronously and caches. */
    fun getInstalledApps(context: Context): List<AppEntry> {
        cachedApps?.let { return it }
        return loadInstalledApps(context).also { cachedApps = it }
    }

    /** Drop the cache so the next load re-enumerates (e.g. after install/remove). */
    fun invalidateCache() { cachedApps = null }

    private fun loadInstalledApps(context: Context): List<AppEntry> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userHandle = android.os.Process.myUserHandle()
        val activities = launcherApps.getActivityList(null, userHandle)

        return activities
            .map { info ->
                AppEntry(
                    label = info.label.toString(),
                    componentName = info.componentName,
                    packageName = info.componentName.packageName
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, componentName: ComponentName) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = componentName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Use reflection to set freeform windowing mode (mode 5)
            val options = ActivityOptions.makeBasic()
            try {
                val method = ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode", Int::class.javaPrimitiveType
                )
                method.invoke(options, 5)
            } catch (e: Exception) {
                // Fallback: try without windowing mode
            }

            context.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            // App launch failed — silently ignore
        }
    }

    /**
     * Launch in a freeform window with explicit bounds.
     * Default: 70% screen width, 65% usable height, centered.
     */
    fun launchWindowed(context: Context, componentName: ComponentName) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenBounds = wm.maximumWindowMetrics.bounds
            val config = dev.atwm.tilingwm.service.TilingAccessibilityService.config

            val usableTop = config.statusBarHeight
            val usableBottom = screenBounds.height() - config.navBarHeight -
                (if (config.taskbarEnabled) config.taskbarHeightPx else 0)
            val usableHeight = usableBottom - usableTop
            val screenWidth = screenBounds.width()

            val winWidth = (screenWidth * 0.70f).toInt()
            val winHeight = (usableHeight * 0.65f).toInt()
            val left = (screenWidth - winWidth) / 2
            val top = usableTop + (usableHeight - winHeight) / 2

            val bounds = Rect(left, top, left + winWidth, top + winHeight)

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = componentName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val options = ActivityOptions.makeBasic()
            try {
                val method = ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode", Int::class.javaPrimitiveType
                )
                method.invoke(options, 5)
            } catch (_: Exception) {}
            options.setLaunchBounds(bounds)

            context.startActivity(intent, options.toBundle())
        } catch (_: Exception) {}
    }
}
