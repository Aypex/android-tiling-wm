package dev.atwm.tilingwm.service

import android.annotation.SuppressLint
import android.graphics.Rect
import android.util.Log
import dev.atwm.tilingwm.IWindowTilingService

/**
 * Runs in Shizuku's privileged process (UID 2000).
 * Accesses IActivityTaskManager via hidden API to resize and remode tasks.
 */
@SuppressLint("PrivateApi")
class WindowTilingServiceImpl : IWindowTilingService.Stub() {

    companion object {
        private const val TAG = "ATWM-Shizuku"
        private var dumpedMethods = false
    }

    private val atm: Any by lazy {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "activity_task")

        val atmStubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = atmStubClass.getMethod("asInterface", android.os.IBinder::class.java)
        asInterface.invoke(null, binder)!!
    }

    private val atmClass: Class<*> by lazy {
        Class.forName("android.app.IActivityTaskManager")
    }

    // Cached reflection methods
    private val resizeTaskMethod by lazy {
        atmClass.getMethod("resizeTask", Int::class.java, Rect::class.java, Int::class.java)
    }

    // setTaskWindowingMode(int,int,boolean) was removed from IActivityTaskManager
    // in Android 17. startActivityFromRecents(taskId, options) with a launch
    // windowing mode in the options re-modes an existing task the same way
    // `am start --windowingMode N` does.
    private val startActivityFromRecentsMethod by lazy {
        atmClass.getMethod("startActivityFromRecents", Int::class.java, android.os.Bundle::class.java)
    }

    private val setLaunchWindowingModeMethod by lazy {
        android.app.ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.java)
    }

    private val getTasksMethod by lazy {
        atmClass.getMethod("getTasks", Int::class.java, Boolean::class.java, Boolean::class.java, Int::class.java)
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            resizeTaskMethod.invoke(atm, taskId, Rect(left, top, right, bottom), 0)
        } catch (e: Exception) {
            Log.e(TAG, "resizeTask($taskId) failed", e)
        }
    }

    override fun setTaskWindowingMode(taskId: Int, windowingMode: Int, toTop: Boolean) {
        try {
            val options = android.app.ActivityOptions.makeBasic()
            setLaunchWindowingModeMethod.invoke(options, windowingMode)
            startActivityFromRecentsMethod.invoke(atm, taskId, options.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "setTaskWindowingMode($taskId -> $windowingMode) failed", e)
            if (!dumpedMethods) {
                dumpedMethods = true
                val interesting = atmClass.methods
                    .filter { m ->
                        m.name.contains("indowing") || m.name.contains("esize") ||
                        m.name.contains("Task") || m.name.contains("Freeform")
                    }
                    .joinToString("\n") { m ->
                        m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
                    }
                Log.e(TAG, "IActivityTaskManager candidate methods:\n$interesting")
            }
        }
    }

    override fun getVisibleTaskInfo(): IntArray {
        try {
            @Suppress("UNCHECKED_CAST")
            val tasks = getTasksMethod.invoke(atm, 20, false, false, 0) as List<Any>

            val filteredTasks = tasks.filter { task ->
                val isVisible = task.javaClass.getField("isVisible").getBoolean(task)
                val isRunning = task.javaClass.getField("isRunning").getBoolean(task)
                isVisible && isRunning
            }

            val result = IntArray(filteredTasks.size * 6)
            filteredTasks.forEachIndexed { i, task ->
                val offset = i * 6
                result[offset] = task.javaClass.getField("taskId").getInt(task)

                // TaskInfo.bounds was removed in Android 17; windowConfiguration
                // carries the same rect on all supported versions.
                val configuration = task.javaClass.getField("configuration").get(task)!!
                val windowConfig = configuration.javaClass.getField("windowConfiguration").get(configuration)!!
                val bounds = windowConfig.javaClass.getMethod("getBounds").invoke(windowConfig) as Rect
                result[offset + 1] = bounds.left
                result[offset + 2] = bounds.top
                result[offset + 3] = bounds.right
                result[offset + 4] = bounds.bottom

                val getWindowingMode = windowConfig.javaClass.getMethod("getWindowingMode")
                result[offset + 5] = getWindowingMode.invoke(windowConfig) as Int
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "getVisibleTaskInfo failed", e)
            return IntArray(0)
        }
    }

    override fun getVisibleTaskPackages(): Array<String> {
        try {
            @Suppress("UNCHECKED_CAST")
            val tasks = getTasksMethod.invoke(atm, 20, false, false, 0) as List<Any>

            val filteredTasks = tasks.filter { task ->
                val isVisible = task.javaClass.getField("isVisible").getBoolean(task)
                val isRunning = task.javaClass.getField("isRunning").getBoolean(task)
                isVisible && isRunning
            }

            return filteredTasks.map { task ->
                val topActivity = task.javaClass.getField("topActivity").get(task)
                if (topActivity != null) {
                    val getPackageName = topActivity.javaClass.getMethod("getPackageName")
                    getPackageName.invoke(topActivity) as? String ?: ""
                } else {
                    ""
                }
            }.toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "getVisibleTaskPackages failed", e)
            return emptyArray()
        }
    }

    override fun ensureAccessibilityService(component: String) {
        try {
            val current = exec("settings", "get", "secure", "enabled_accessibility_services")
                .trim().let { if (it == "null") "" else it }
            if (current.split(':').contains(component)) return
            val updated = if (current.isEmpty()) component else "$current:$component"
            exec("settings", "put", "secure", "enabled_accessibility_services", updated)
            exec("settings", "put", "secure", "accessibility_enabled", "1")
            Log.i(TAG, "re-enabled accessibility service (OS had pruned it)")
        } catch (e: Exception) {
            Log.e(TAG, "ensureAccessibilityService failed", e)
        }
    }

    private fun exec(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    override fun destroy() {
        // Shizuku signals service shutdown by calling destroy(); the process
        // must exit itself or it leaks (one orphan per rebind/reinstall).
        Log.i(TAG, "destroy: exiting user service process")
        System.exit(0)
    }
}
