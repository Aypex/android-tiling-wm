package dev.atwm.tilingwm

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import dev.atwm.tilingwm.service.ShizukuServiceConnection
import dev.atwm.tilingwm.service.TaskbarForegroundService
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.service.WindowTilingServiceImpl
import rikka.shizuku.Shizuku

class LauncherActivity : Activity() {

    private val binderListener = Shizuku.OnBinderReceivedListener { tryBindShizuku() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent window showing system wallpaper
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        // Empty content — the overlay taskbar is the real UI
        setContentView(View(this))

        // If not set up yet (no overlay permission), redirect to setup
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            return
        }

        // Start taskbar overlay service
        startForegroundService(Intent(this, TaskbarForegroundService::class.java))

        // Bind Shizuku for tiling (silently fails if unavailable)
        Shizuku.addBinderReceivedListener(binderListener)
        tryBindShizuku()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home button pressed while already visible — no action needed
    }

    @Deprecated("Launcher home screen")
    override fun onBackPressed() {
        // Home screen: back button is a no-op
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun tryBindShizuku() {
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

            val connection = ShizukuServiceConnection()
            val args = Shizuku.UserServiceArgs(
                ComponentName(packageName, WindowTilingServiceImpl::class.java.name)
            ).daemon(false).processNameSuffix("tiling").version(BuildConfig.VERSION_CODE)

            Shizuku.bindUserService(args, connection)
            TilingAccessibilityService.serviceConnection = connection

            val taskbarHeightPx = (56 * resources.displayMetrics.density).toInt()
            TilingAccessibilityService.updateConfig(
                TilingAccessibilityService.config.copy(taskbarHeightPx = taskbarHeightPx)
            )
        } catch (_: Exception) {
            // Shizuku not available — taskbar works, tiling waits
        }
    }
}
