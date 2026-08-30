package dev.atwm.tilingwm.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.atwm.tilingwm.engine.LayoutStrategy
import dev.atwm.tilingwm.engine.MasterStackLayout
import dev.atwm.tilingwm.engine.TilingEngine
import dev.atwm.tilingwm.model.TaskInfo
import dev.atwm.tilingwm.model.TilingConfig

class TilingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ATWM-Tiling"
        var serviceConnection: ShizukuServiceConnection? = null
        var isEnabled: Boolean = false
        var instance: TilingAccessibilityService? = null

        var config = TilingConfig()
            private set
        var currentStrategy: LayoutStrategy = MasterStackLayout()
            private set

        fun updateConfig(newConfig: TilingConfig) {
            config = newConfig
            instance?.engine = TilingEngine(config, currentStrategy)
        }

        fun updateStrategy(strategy: LayoutStrategy) {
            currentStrategy = strategy
            instance?.engine = TilingEngine(config, currentStrategy)
        }

        private var runningAppsCallback: ((Set<String>) -> Unit)? = null

        fun setOnRunningAppsChanged(callback: ((Set<String>) -> Unit)?) {
            runningAppsCallback = callback
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val debounceMs = 150L
    private var engine = TilingEngine(config, currentStrategy)

    private var screenWidth = 0
    private var screenHeight = 0

    private val retileRunnable = Runnable { retile() }

    override fun onServiceConnected() {
        instance = this
        updateScreenMetrics()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isEnabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handler.removeCallbacks(retileRunnable)
                handler.postDelayed(retileRunnable, debounceMs)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        handler.removeCallbacks(retileRunnable)
        retile()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(retileRunnable)
        instance = null
        super.onDestroy()
    }

    fun forceRetile() {
        handler.removeCallbacks(retileRunnable)
        retile()
    }

    private fun updateScreenMetrics() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        detectInsets()
    }

    private fun detectInsets() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            val newConfig = config.copy(
                statusBarHeight = insets.top,
                navBarHeight = insets.bottom
            )
            if (newConfig != config) {
                updateConfig(newConfig)
            }
        } catch (_: Exception) {
            // Fallback: keep existing values
        }
    }

    private fun retile() {
        val svc = serviceConnection?.service
        if (svc == null) {
            Log.w(TAG, "retile: no Shizuku service connection")
            return
        }

        try {
            val taskInts = svc.getVisibleTaskInfo()
            val taskPkgs = svc.getVisibleTaskPackages()
            Log.d(TAG, "retile: ${taskInts.size / 6} visible tasks, pkgs=${taskPkgs.joinToString()}")

            val tasks = mutableListOf<TaskInfo>()
            val count = taskInts.size / 6
            for (i in 0 until count) {
                val offset = i * 6
                tasks.add(TaskInfo(
                    taskId = taskInts[offset],
                    packageName = taskPkgs[i],
                    bounds = Rect(
                        taskInts[offset + 1], taskInts[offset + 2],
                        taskInts[offset + 3], taskInts[offset + 4]
                    ),
                    windowingMode = taskInts[offset + 5]
                ))
            }

            // Notify running apps callback
            val tileableTasks = tasks.filter { it.packageName !in config.excludedPackages }
            val runningPkgs = tileableTasks.map { it.packageName }.toSet()
            runningAppsCallback?.invoke(runningPkgs)

            // Force all tileable tasks into freeform mode (windowing mode 5)
            for (task in tileableTasks) {
                if (task.windowingMode != 5) {
                    svc.setTaskWindowingMode(task.taskId, 5, true)
                }
            }

            // Single task: resize to fill usable area (no tiling needed)
            if (tileableTasks.size == 1) {
                val task = tileableTasks[0]
                val bottom = screenHeight - config.navBarHeight -
                    (if (config.taskbarEnabled) config.taskbarHeightPx else 0)
                svc.resizeTask(task.taskId, 0, config.statusBarHeight, screenWidth, bottom)
                return
            }

            // 2+ tasks: compute tiled layout
            val orientation = resources.configuration.orientation
            val layout = engine.computeLayout(tasks, screenWidth, screenHeight, orientation)

            for (lb in layout) {
                svc.resizeTask(
                    lb.taskId, lb.bounds.left, lb.bounds.top,
                    lb.bounds.right, lb.bounds.bottom
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "retile: Shizuku connection lost", e)
            serviceConnection = null
        }
    }
}
