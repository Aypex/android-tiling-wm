package dev.atwm.tilingwm.taskbar

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import dev.atwm.tilingwm.StartButtonPickerActivity
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.util.TaskbarPrefs

class TaskbarOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    var view: TaskbarView? = null
        private set

    private lateinit var taskbarPrefs: TaskbarPrefs

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        dpToPx(56),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
    }

    fun show() {
        if (view != null) return
        taskbarPrefs = TaskbarPrefs(context)

        val taskbarView = TaskbarView(context)
        windowManager.addView(taskbarView, layoutParams)
        view = taskbarView

        // Load persisted start button icon
        taskbarView.setStartButtonIcon(taskbarPrefs.getStartButtonIconPath())

        // Long-press start button to pick custom icon
        taskbarView.onStartLongPress = {
            val intent = Intent(context, StartButtonPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        // Load persisted layout index
        val savedLayoutIndex = taskbarPrefs.getLayoutIndex()
        taskbarView.setLayoutIndex(savedLayoutIndex)

        // Persist layout index on change
        taskbarView.onLayoutToggle = {
            taskbarPrefs.setLayoutIndex(taskbarView.getCurrentLayoutIndex())
        }

        // Tell the tiling engine how tall the taskbar is so it reserves space
        val taskbarPx = dpToPx(56)
        TilingAccessibilityService.updateConfig(
            TilingAccessibilityService.config.copy(taskbarHeightPx = taskbarPx)
        )

        // Load persisted window gap and apply to config
        val savedGap = taskbarPrefs.getWindowGap()
        if (savedGap != 0) {
            val gapConfig = TilingAccessibilityService.config.copy(windowGap = savedGap)
            TilingAccessibilityService.updateConfig(gapConfig)
        }

        // Load persisted master ratio
        val savedRatio = taskbarPrefs.getMasterRatio()
        if (savedRatio != 0.55f) {
            val ratioConfig = TilingAccessibilityService.config.copy(masterRatio = savedRatio)
            TilingAccessibilityService.updateConfig(ratioConfig)
        }

        // Load persisted bar items and display them
        val barItems = taskbarPrefs.getBarItems()
        taskbarView.updateBarItems(barItems, emptySet())

        // Register for running apps changes to update indicators
        TilingAccessibilityService.setOnRunningAppsChanged { runningPackages ->
            val currentItems = taskbarPrefs.getBarItems()
            taskbarView.updateBarItems(currentItems, runningPackages)
        }
    }

    fun hide() {
        // Unregister callback
        TilingAccessibilityService.setOnRunningAppsChanged(null)

        view?.let {
            it.dismissDrawer()
            it.dismissFolderPopup()
            it.dismissSettingsPanel()
            it.dismissUndoSnackbar()
            windowManager.removeView(it)
            view = null
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
