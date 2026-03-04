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

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = IWindowTilingService.Stub.asInterface(binder)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }
}
