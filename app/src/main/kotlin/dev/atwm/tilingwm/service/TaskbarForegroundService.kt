package dev.atwm.tilingwm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.atwm.tilingwm.MainActivity
import dev.atwm.tilingwm.R
import dev.atwm.tilingwm.taskbar.TaskbarOverlay

class TaskbarForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "taskbar_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_TILING = "dev.atwm.tilingwm.TOGGLE_TILING"
    }

    private var overlay: TaskbarOverlay? = null
    private val handler = Handler(Looper.getMainLooper())

    // Surfaces the "tiling on but accessibility service dead" state on the
    // taskbar. Indicator only — restoring the service is done on Shizuku
    // connect (user intent), never forced mid-session.
    private val healthCheck = object : Runnable {
        override fun run() {
            val broken = TilingAccessibilityService.isEnabled &&
                TilingAccessibilityService.instance == null
            overlay?.view?.setServiceWarning(broken)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        overlay = TaskbarOverlay(this).also { it.show() }
        handler.post(healthCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_TILING) {
            TilingAccessibilityService.isEnabled = !TilingAccessibilityService.isEnabled
            TilingAccessibilityService.instance?.forceRetile()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(healthCheck)
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.taskbar_notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, TaskbarForegroundService::class.java).apply {
            action = ACTION_TOGGLE_TILING
        }
        val togglePending = PendingIntent.getService(
            this, 0, toggleIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.taskbar_running))
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.toggle_tiling),
                    togglePending
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}
