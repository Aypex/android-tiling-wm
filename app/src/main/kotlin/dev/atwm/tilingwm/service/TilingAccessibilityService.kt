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
import dev.atwm.tilingwm.chrome.BorderOverlayManager
import dev.atwm.tilingwm.engine.LayoutStrategy
import dev.atwm.tilingwm.engine.MasterStackLayout
import dev.atwm.tilingwm.engine.TilingEngine
import dev.atwm.tilingwm.model.TaskInfo
import dev.atwm.tilingwm.model.TilingConfig
import dev.atwm.tilingwm.util.TaskbarPrefs

class TilingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ATWM-Tiling"
        var serviceConnection: ShizukuServiceConnection? = null
        var isEnabled: Boolean = false
            set(value) {
                field = value
                if (!value) instance?.clearChrome()
            }
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

    private var borders: BorderOverlayManager? = null
    private var prefs: TaskbarPrefs? = null

    // Stable tile order by taskId: getTasks() returns recency order, which
    // would promote whatever window was last touched to master. Windows keep
    // their slot; new windows join the end of the stack.
    private val windowOrder = mutableListOf<Int>()

    override fun onServiceConnected() {
        instance = this
        updateScreenMetrics()
        prefs = TaskbarPrefs(this)
        borders = BorderOverlayManager(this) { ratio ->
            updateConfig(config.copy(masterRatio = ratio))
            forceRetile()
        }
    }

    fun clearChrome() {
        borders?.clear()
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
        clearChrome()
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
            // Can't tile without Shizuku — clear any chrome so border frames
            // aren't stranded on screen with no code path to remove them.
            Log.w(TAG, "retile: no Shizuku service connection")
            clearChrome()
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
            val visibleTileable = tasks.filter { it.packageName !in config.excludedPackages }
            val runningPkgs = visibleTileable.map { it.packageName }.toSet()
            runningAppsCallback?.invoke(runningPkgs)

            // Apply stable ordering
            val visibleIds = visibleTileable.map { it.taskId }
            windowOrder.retainAll(visibleIds)
            for (id in visibleIds) if (id !in windowOrder) windowOrder.add(id)
            val tileableTasks = windowOrder.mapNotNull { id ->
                visibleTileable.find { it.taskId == id }
            }

            // Force all tileable tasks into freeform mode (windowing mode 5)
            for (task in tileableTasks) {
                if (task.windowingMode != 5) {
                    svc.setTaskWindowingMode(task.taskId, 5, true)
                }
            }

            val usableArea = Rect(
                0,
                config.statusBarHeight,
                screenWidth,
                screenHeight - config.navBarHeight -
                    (if (config.taskbarEnabled) config.taskbarHeightPx else 0)
            )
            val isPortrait = resources.configuration.orientation ==
                Configuration.ORIENTATION_PORTRAIT
            val accent = prefs?.getAccentColor() ?: 0xFF4FC3F7.toInt()

            // Single task: resize to fill usable area (no tiling needed)
            if (tileableTasks.size == 1) {
                val task = tileableTasks[0]
                svc.resizeTask(
                    task.taskId, usableArea.left, usableArea.top,
                    usableArea.right, usableArea.bottom
                )
                borders?.update(
                    listOf(usableArea), usableArea, accent,
                    showDivider = false, isPortrait = isPortrait
                )
                return
            }

            // 2+ tasks: compute tiled layout
            val orientation = resources.configuration.orientation
            val layout = engine.computeLayout(tileableTasks, screenWidth, screenHeight, orientation)

            for (lb in layout) {
                svc.resizeTask(
                    lb.taskId, lb.bounds.left, lb.bounds.top,
                    lb.bounds.right, lb.bounds.bottom
                )
            }

            borders?.update(
                layout.map { it.bounds }, usableArea, accent,
                showDivider = currentStrategy is MasterStackLayout,
                isPortrait = isPortrait
            )
        } catch (e: RemoteException) {
            // Connection lost mid-retile — clear chrome so frames aren't
            // stranded, and drop the dead connection.
            Log.e(TAG, "retile: Shizuku connection lost", e)
            clearChrome()
            serviceConnection = null
        }
    }
}
