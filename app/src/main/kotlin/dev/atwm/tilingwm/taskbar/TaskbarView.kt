package dev.atwm.tilingwm.taskbar

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.atwm.tilingwm.R
import dev.atwm.tilingwm.engine.ColumnsLayout
import dev.atwm.tilingwm.engine.LayoutStrategy
import dev.atwm.tilingwm.engine.MasterStackLayout
import dev.atwm.tilingwm.engine.MonocleLayout
import dev.atwm.tilingwm.model.BarItem
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.util.AppEntry
import dev.atwm.tilingwm.util.AppLauncher
import dev.atwm.tilingwm.util.IconCache
import dev.atwm.tilingwm.util.TaskbarPrefs

class TaskbarView(context: Context) : LinearLayout(context) {

    val startZone: FrameLayout
    val centerZone: LinearLayout
    val toggleZone: FrameLayout

    private val startButton: ImageView
    private val layoutButton: ImageView
    private var appDrawer: AppDrawerPopup? = null
    private var folderPopup: FolderPopup? = null
    private var settingsPanel: SettingsPanel? = null
    private var undoSnackbar: UndoSnackbar? = null

    private val layoutStrategies: List<LayoutStrategy> = listOf(
        MasterStackLayout(),
        ColumnsLayout(),
        MonocleLayout()
    )
    private val layoutIcons = intArrayOf(
        R.drawable.ic_layout_master,
        R.drawable.ic_layout_columns,
        R.drawable.ic_layout_monocle
    )
    private val layoutContentDescriptions by lazy { arrayOf(
        context.getString(R.string.cd_layout_master),
        context.getString(R.string.cd_layout_columns),
        context.getString(R.string.cd_layout_monocle)
    ) }
    private var currentLayoutIndex = 0

    private val gapPresets = intArrayOf(0, 8, 16)
    private var currentGapIndex = 0

    private lateinit var taskbarPrefs: TaskbarPrefs
    private var currentBarItems: List<BarItem> = emptyList()
    private var currentRunningPackages: Set<String> = emptySet()

    var onStartLongPress: (() -> Unit)? = null
    var onLayoutToggle: (() -> Unit)? = null
    var onBarItemsChanged: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.taskbar_view, this, true)
        startZone = findViewById(R.id.start_zone)
        centerZone = findViewById(R.id.center_zone)
        toggleZone = findViewById(R.id.toggle_zone)

        IconCache.init(context)
        // Warm the app-drawer list in the background so the first open is instant
        // instead of stalling ~400ms enumerating apps on the main thread.
        AppLauncher.preload(context)
        taskbarPrefs = TaskbarPrefs(context)

        // Apply start button position preference
        if (taskbarPrefs.getStartOnRight()) {
            applyStartOnRight()
        }

        // Sync gap index from prefs
        val savedGap = taskbarPrefs.getWindowGap()
        currentGapIndex = gapPresets.indexOf(savedGap).coerceAtLeast(0)

        // Start button
        startButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_start)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.cd_open_app_drawer)
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        startZone.addView(startButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        startButton.setOnClickListener {
            toggleAppDrawer()
        }

        startButton.setOnLongClickListener {
            showSettingsPanel()
            true
        }

        // Layout toggle button
        layoutButton = ImageView(context).apply {
            setImageResource(layoutIcons[currentLayoutIndex])
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = layoutContentDescriptions[currentLayoutIndex]
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        toggleZone.addView(layoutButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        layoutButton.setOnClickListener {
            currentLayoutIndex = (currentLayoutIndex + 1) % layoutStrategies.size
            layoutButton.setImageResource(layoutIcons[currentLayoutIndex])
            layoutButton.contentDescription = layoutContentDescriptions[currentLayoutIndex]
            TilingAccessibilityService.updateStrategy(layoutStrategies[currentLayoutIndex])
            TilingAccessibilityService.instance?.forceRetile()
            taskbarPrefs.setLayoutIndex(currentLayoutIndex)
            onLayoutToggle?.invoke()
        }

        layoutButton.setOnLongClickListener {
            showSettingsPanel()
            true
        }
    }

    /**
     * Warning state: tiling is enabled but the accessibility service is not
     * running (e.g. the OS pruned it after a force-stop and Shizuku is not
     * available to self-heal). Tints the layout button red.
     */
    fun setServiceWarning(broken: Boolean) {
        if (broken) {
            layoutButton.setColorFilter(0xFFE05555.toInt())
        } else {
            layoutButton.clearColorFilter()
        }
    }

    // -- Settings panel (long-press start or layout toggle) --

    private fun showSettingsPanel() {
        if (settingsPanel?.isShowing == true) {
            settingsPanel?.dismiss()
            settingsPanel = null
            return
        }

        val panel = SettingsPanel(context)

        panel.onLayoutSelected = { idx ->
            setLayoutIndex(idx)
            taskbarPrefs.setLayoutIndex(idx)
            TilingAccessibilityService.instance?.forceRetile()
        }

        panel.onGapSelected = { idx ->
            currentGapIndex = idx
            val gap = gapPresets[idx]
            TilingAccessibilityService.updateConfig(
                TilingAccessibilityService.config.copy(windowGap = gap)
            )
            TilingAccessibilityService.instance?.forceRetile()
            taskbarPrefs.setWindowGap(gap)
        }

        panel.onSwapSides = { toggleStartPosition() }

        panel.onChangeIcon = { onStartLongPress?.invoke() }

        panel.onAccentSelected = { color ->
            taskbarPrefs.setAccentColor(color)
            updateBarItems(currentBarItems, currentRunningPackages)
        }

        panel.show(currentLayoutIndex, currentGapIndex, taskbarPrefs.getAccentColor())
        settingsPanel = panel
    }

    // -- Position swapping --

    private fun toggleStartPosition() {
        val newRight = !taskbarPrefs.getStartOnRight()
        taskbarPrefs.setStartOnRight(newRight)
        rearrangeZones(newRight)
    }

    private fun applyStartOnRight() {
        rearrangeZones(true)
    }

    private fun rearrangeZones(startOnRight: Boolean) {
        val container = startZone.parent as LinearLayout
        container.removeAllViews()
        if (startOnRight) {
            toggleZone.layoutParams = (toggleZone.layoutParams as MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = resources.getDimension(R.dimen.taskbar_padding).toInt()
            }
            startZone.layoutParams = (startZone.layoutParams as MarginLayoutParams).apply {
                marginEnd = 0
                marginStart = resources.getDimension(R.dimen.taskbar_padding).toInt()
            }
            container.addView(toggleZone)
            container.addView(centerZone)
            container.addView(startZone)
        } else {
            startZone.layoutParams = (startZone.layoutParams as MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = resources.getDimension(R.dimen.taskbar_padding).toInt()
            }
            toggleZone.layoutParams = (toggleZone.layoutParams as MarginLayoutParams).apply {
                marginEnd = 0
                marginStart = resources.getDimension(R.dimen.taskbar_padding).toInt()
            }
            container.addView(startZone)
            container.addView(centerZone)
            container.addView(toggleZone)
        }
    }

    // -- Layout index --

    fun setLayoutIndex(index: Int) {
        if (index in layoutStrategies.indices) {
            currentLayoutIndex = index
            layoutButton.setImageResource(layoutIcons[currentLayoutIndex])
            layoutButton.contentDescription = layoutContentDescriptions[currentLayoutIndex]
            TilingAccessibilityService.updateStrategy(layoutStrategies[currentLayoutIndex])
        }
    }

    fun getCurrentLayoutIndex(): Int = currentLayoutIndex

    // -- Start button icon --

    fun setStartButtonIcon(path: String?) {
        if (path != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    startButton.setImageBitmap(bitmap)
                    return
                }
            } catch (_: Exception) {}
        }
        startButton.setImageResource(R.drawable.ic_start)
    }

    // -- Bar items (pinned apps + folders) --

    fun updateBarItems(items: List<BarItem>, runningPackages: Set<String>) {
        currentBarItems = items
        currentRunningPackages = runningPackages

        centerZone.removeAllViews()

        for ((index, item) in items.withIndex()) {
            when (item) {
                is BarItem.PinnedApp -> addPinnedAppView(item, index, runningPackages)
                is BarItem.Folder -> addFolderView(item, index, runningPackages)
            }
        }
    }

    private fun addPinnedAppView(app: BarItem.PinnedApp, index: Int, runningPackages: Set<String>) {
        val itemView = LayoutInflater.from(context)
            .inflate(R.layout.taskbar_app_item, centerZone, false)
        val icon = itemView.findViewById<ImageView>(R.id.bar_app_icon)
        val runningDot = itemView.findViewById<View>(R.id.running_dot)

        val cn = ComponentName.unflattenFromString(app.componentName)
        if (cn != null) {
            icon.setImageDrawable(IconCache.getIcon(context, cn))
        }
        icon.contentDescription = app.label

        runningDot.visibility = if (app.packageName in runningPackages) View.VISIBLE else View.GONE
        runningDot.background.mutate().setTint(taskbarPrefs.getAccentColor())

        itemView.setOnClickListener {
            if (cn != null) {
                AppLauncher.launch(context, cn)
                TilingAccessibilityService.instance?.forceRetile()
            }
        }

        itemView.setOnLongClickListener {
            handlePinnedAppLongPress(app, index)
            true
        }

        centerZone.addView(itemView)
    }

    private fun addFolderView(folder: BarItem.Folder, index: Int, runningPackages: Set<String>) {
        val itemView = LayoutInflater.from(context)
            .inflate(R.layout.taskbar_app_item, centerZone, false)
        val icon = itemView.findViewById<ImageView>(R.id.bar_app_icon)
        val runningDot = itemView.findViewById<View>(R.id.running_dot)

        val firstApp = folder.apps.firstOrNull()
        if (firstApp != null) {
            val cn = ComponentName.unflattenFromString(firstApp.componentName)
            if (cn != null) {
                icon.setImageDrawable(IconCache.getIcon(context, cn))
            }
        } else {
            icon.setImageResource(R.drawable.ic_start)
        }
        icon.contentDescription = folder.name

        val anyRunning = folder.apps.any { it.packageName in runningPackages }
        runningDot.visibility = if (anyRunning) View.VISIBLE else View.GONE
        runningDot.background.mutate().setTint(taskbarPrefs.getAccentColor())

        itemView.setOnClickListener {
            dismissFolderPopup()
            val popup = FolderPopup(context, folder)
            popup.show()
            folderPopup = popup
        }

        itemView.setOnLongClickListener {
            handleFolderLongPress(folder, index)
            true
        }

        centerZone.addView(itemView)
    }

    private fun handlePinnedAppLongPress(app: BarItem.PinnedApp, index: Int) {
        val items = currentBarItems.toMutableList()
        items.removeAt(index)
        taskbarPrefs.setBarItems(items)
        updateBarItems(items, currentRunningPackages)
        onBarItemsChanged?.invoke()

        undoSnackbar?.dismiss()
        undoSnackbar = UndoSnackbar(context).apply {
            show(
                "${app.label} unpinned",
                taskbarPrefs.getAccentColor(),
                onUndo = {
                    val restored = currentBarItems.toMutableList()
                    restored.add(index.coerceAtMost(restored.size), app)
                    taskbarPrefs.setBarItems(restored)
                    updateBarItems(restored, currentRunningPackages)
                    onBarItemsChanged?.invoke()
                }
            )
        }
    }

    private fun handleFolderLongPress(folder: BarItem.Folder, index: Int) {
        val dialog = FolderEditDialog(
            context = context,
            folder = folder,
            onSave = { updatedFolder ->
                val items = currentBarItems.toMutableList()
                items[index] = updatedFolder
                taskbarPrefs.setBarItems(items)
                updateBarItems(items, currentRunningPackages)
                onBarItemsChanged?.invoke()
            },
            onDelete = {
                val items = currentBarItems.toMutableList()
                items.removeAt(index)
                taskbarPrefs.setBarItems(items)
                updateBarItems(items, currentRunningPackages)
                onBarItemsChanged?.invoke()
            }
        )
        dialog.show()
    }

    fun pinApp(appEntry: AppEntry) {
        val pinnedApp = BarItem.PinnedApp(
            packageName = appEntry.packageName,
            componentName = appEntry.componentName.flattenToString(),
            label = appEntry.label
        )
        val items = currentBarItems.toMutableList()
        if (items.none { it is BarItem.PinnedApp && it.componentName == pinnedApp.componentName }) {
            items.add(pinnedApp)
            taskbarPrefs.setBarItems(items)
            updateBarItems(items, currentRunningPackages)
            onBarItemsChanged?.invoke()
        }
    }

    // -- App drawer --

    private fun toggleAppDrawer() {
        val drawer = appDrawer
        if (drawer != null && drawer.isShowing) {
            drawer.dismiss()
            appDrawer = null
        } else {
            val newDrawer = AppDrawerPopup(context)
            newDrawer.onAppLongPress = { appEntry ->
                showDrawerContextMenu(appEntry, newDrawer)
            }
            newDrawer.show()
            appDrawer = newDrawer
        }
    }

    private fun showDrawerContextMenu(appEntry: AppEntry, drawer: AppDrawerPopup) {
        val items = mutableListOf<String>()
        items.add(context.getString(R.string.launch_windowed))
        items.add(context.getString(R.string.pin_to_taskbar))
        items.add(context.getString(R.string.create_folder))

        // Add "Add to [folder]" for each existing folder
        val folders = currentBarItems.filterIsInstance<BarItem.Folder>()
        for (folder in folders) {
            items.add(context.getString(R.string.add_to_folder, folder.name))
        }

        val dialog = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(appEntry.label)
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        // Launch in Window with explicit bounds
                        AppLauncher.launchWindowed(context, appEntry.componentName)
                        TilingAccessibilityService.instance?.forceRetile()
                    }
                    which == 1 -> {
                        // Pin to Taskbar
                        pinApp(appEntry)
                    }
                    which == 2 -> {
                        // New Folder with this app as first member
                        val pinnedApp = BarItem.PinnedApp(
                            packageName = appEntry.packageName,
                            componentName = appEntry.componentName.flattenToString(),
                            label = appEntry.label
                        )
                        val folder = BarItem.Folder(
                            id = "folder_${System.currentTimeMillis()}",
                            name = context.getString(R.string.new_folder_name),
                            apps = listOf(pinnedApp)
                        )
                        val barItems = currentBarItems.toMutableList()
                        barItems.add(folder)
                        taskbarPrefs.setBarItems(barItems)
                        updateBarItems(barItems, currentRunningPackages)
                        onBarItemsChanged?.invoke()
                    }
                    else -> {
                        // Add to existing folder
                        val folderIndex = which - 3
                        val targetFolder = folders[folderIndex]
                        val pinnedApp = BarItem.PinnedApp(
                            packageName = appEntry.packageName,
                            componentName = appEntry.componentName.flattenToString(),
                            label = appEntry.label
                        )
                        // Only add if not already in the folder
                        if (targetFolder.apps.none { it.componentName == pinnedApp.componentName }) {
                            val updatedFolder = targetFolder.copy(
                                apps = targetFolder.apps + pinnedApp
                            )
                            val barItems = currentBarItems.toMutableList()
                            val idx = barItems.indexOf(targetFolder)
                            if (idx >= 0) {
                                barItems[idx] = updatedFolder
                                taskbarPrefs.setBarItems(barItems)
                                updateBarItems(barItems, currentRunningPackages)
                                onBarItemsChanged?.invoke()
                            }
                        }
                    }
                }
                drawer.dismiss()
                appDrawer = null
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Required for showing AlertDialog from an overlay
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    fun dismissDrawer() {
        appDrawer?.dismiss()
        appDrawer = null
    }

    fun dismissFolderPopup() {
        folderPopup?.dismiss()
        folderPopup = null
    }

    fun dismissSettingsPanel() {
        settingsPanel?.dismiss()
        settingsPanel = null
    }

    fun dismissUndoSnackbar() {
        undoSnackbar?.dismiss()
        undoSnackbar = null
    }

    fun getAppDrawer(): AppDrawerPopup? = appDrawer
}
