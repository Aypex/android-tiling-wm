package dev.atwm.tilingwm.service

import android.annotation.SuppressLint
import android.graphics.Rect
import dev.atwm.tilingwm.IWindowTilingService

/**
 * Runs in Shizuku's privileged process (UID 2000).
 * Accesses IActivityTaskManager via hidden API to resize and remode tasks.
 */
@SuppressLint("PrivateApi")
class WindowTilingServiceImpl : IWindowTilingService.Stub() {

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

    private val setTaskWindowingModeMethod by lazy {
        atmClass.getMethod("setTaskWindowingMode", Int::class.java, Int::class.java, Boolean::class.java)
    }

    private val getTasksMethod by lazy {
        atmClass.getMethod("getTasks", Int::class.java, Boolean::class.java, Boolean::class.java, Int::class.java)
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            resizeTaskMethod.invoke(atm, taskId, Rect(left, top, right, bottom), 0)
        } catch (e: Exception) {
            // Reflection failure — silently ignore, task stays at current bounds
        }
    }

    override fun setTaskWindowingMode(taskId: Int, windowingMode: Int, toTop: Boolean) {
        try {
            setTaskWindowingModeMethod.invoke(atm, taskId, windowingMode, toTop)
        } catch (e: Exception) {
            // Reflection failure — silently ignore
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

                val bounds = task.javaClass.getField("bounds").get(task) as Rect
                result[offset + 1] = bounds.left
                result[offset + 2] = bounds.top
                result[offset + 3] = bounds.right
                result[offset + 4] = bounds.bottom

                val configuration = task.javaClass.getField("configuration").get(task)!!
                val windowConfig = configuration.javaClass.getField("windowConfiguration").get(configuration)!!
                val getWindowingMode = windowConfig.javaClass.getMethod("getWindowingMode")
                result[offset + 5] = getWindowingMode.invoke(windowConfig) as Int
            }
            return result
        } catch (e: Exception) {
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
            return emptyArray()
        }
    }

    override fun destroy() {
        // Called by Shizuku when unbinding. No cleanup needed.
    }
}
