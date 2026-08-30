package dev.atwm.tilingwm.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import dev.atwm.tilingwm.IWindowTilingService

class ShizukuServiceConnection : ServiceConnection {
    var service: IWindowTilingService? = null
        private set

    val isConnected: Boolean
        get() = service != null

    companion object {
        const val ACCESSIBILITY_COMPONENT =
            "dev.atwm.tilingwm/dev.atwm.tilingwm.service.TilingAccessibilityService"
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = IWindowTilingService.Stub.asInterface(binder)
        // Self-heal: Android 17 prunes force-stopped apps from the enabled
        // accessibility services list. The user launching ATWM is the intent
        // signal to restore it; mid-session we only indicate, never force.
        try {
            service?.ensureAccessibilityService(ACCESSIBILITY_COMPONENT)
        } catch (_: Exception) {
            // Non-fatal; the taskbar warning indicator covers this
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }
}
