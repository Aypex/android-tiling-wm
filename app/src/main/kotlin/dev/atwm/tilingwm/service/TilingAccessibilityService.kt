package dev.atwm.tilingwm.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.view.accessibility.AccessibilityEvent
import dev.atwm.tilingwm.engine.MasterStackLayout
import dev.atwm.tilingwm.engine.TilingEngine
import dev.atwm.tilingwm.model.TaskInfo
import dev.atwm.tilingwm.model.TilingConfig

class TilingAccessibilityService : AccessibilityService() {

    companion object {
        var serviceConnection: ShizukuServiceConnection? = null
        var isEnabled: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val debounceMs = 150L
    private val config = TilingConfig()
    private val engine = TilingEngine(config, MasterStackLayout())

    private var screenWidth = 0
    private var screenHeight = 0

    private val retileRunnable = Runnable { retile() }

    override fun onServiceConnected() {
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
        // Orientation change — retile immediately
        handler.removeCallbacks(retileRunnable)
        retile()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(retileRunnable)
        super.onDestroy()
    }

    private fun updateScreenMetrics() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun retile() {
        val svc = serviceConnection?.service ?: return

        try {
            // Fetch current task state from Shizuku
            val taskInts = svc.getVisibleTaskInfo()
            val taskPkgs = svc.getVisibleTaskPackages()

            // Parse into TaskInfo list
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

            val orientation = resources.configuration.orientation
            val layout = engine.computeLayout(tasks, screenWidth, screenHeight, orientation)

            // Apply layout
            for (lb in layout) {
                val task = tasks.find { it.taskId == lb.taskId } ?: continue

                // Switch to freeform if needed
                if (task.windowingMode != 5) {
                    svc.setTaskWindowingMode(task.taskId, 5, true)
                }

                svc.resizeTask(
                    lb.taskId, lb.bounds.left, lb.bounds.top,
                    lb.bounds.right, lb.bounds.bottom
                )
            }
        } catch (e: RemoteException) {
            // Shizuku service died — null the reference so we stop retiling
            serviceConnection = null
        }
    }
}
