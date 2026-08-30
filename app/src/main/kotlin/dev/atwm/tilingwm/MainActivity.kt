package dev.atwm.tilingwm

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.atwm.tilingwm.model.TilingConfig
import dev.atwm.tilingwm.service.ShizukuServiceConnection
import dev.atwm.tilingwm.service.TaskbarForegroundService
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.service.WindowTilingServiceImpl
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(),
    Shizuku.OnRequestPermissionResultListener,
    Shizuku.OnBinderReceivedListener,
    Shizuku.OnBinderDeadListener {

    companion object {
        private const val REQUEST_CODE = 1
        private const val REQUEST_OVERLAY = 2
        private const val REQUEST_NOTIFICATION = 3
    }

    private val serviceConnection = ShizukuServiceConnection()
    private var tilingEnabled = false

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var accessibilityButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        accessibilityButton = findViewById(R.id.accessibility_button)

        accessibilityButton.setOnClickListener { openAccessibilitySettings() }

        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListener(this)
        Shizuku.addBinderDeadListener(this)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        // Check overlay permission first
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = getString(R.string.overlay_permission_required)
            actionButton.text = getString(R.string.grant_overlay)
            actionButton.setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            }
            actionButton.visibility = android.view.View.VISIBLE
            return
        }

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            // Continue regardless — notification denial shouldn't block functionality
        }

        checkShizukuState()
    }

    private fun checkShizukuState() {
        try {
            if (!Shizuku.pingBinder()) {
                statusText.text = getString(R.string.shizuku_not_running)
                actionButton.text = getString(R.string.retry)
                actionButton.setOnClickListener { checkShizukuState() }
                actionButton.visibility = android.view.View.VISIBLE
                return
            }
        } catch (e: Exception) {
            statusText.text = getString(R.string.shizuku_not_installed)
            actionButton.visibility = android.view.View.GONE
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService()
        } else {
            statusText.text = getString(R.string.permission_required)
            actionButton.text = getString(R.string.grant_permission)
            actionButton.setOnClickListener { Shizuku.requestPermission(REQUEST_CODE) }
            actionButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun bindUserService() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, WindowTilingServiceImpl::class.java.name)
        ).daemon(false).processNameSuffix("tiling").version(BuildConfig.VERSION_CODE)

        Shizuku.bindUserService(args, serviceConnection)
        TilingAccessibilityService.serviceConnection = serviceConnection

        // Compute taskbar height in pixels and update config
        val taskbarHeightPx = (56 * resources.displayMetrics.density).toInt()
        TilingAccessibilityService.updateConfig(
            TilingAccessibilityService.config.copy(taskbarHeightPx = taskbarHeightPx)
        )

        // Start taskbar foreground service
        startForegroundService(
            Intent(this, TaskbarForegroundService::class.java)
        )

        statusText.text = getString(R.string.connected)
        actionButton.text = getString(R.string.start_tiling)
        actionButton.setOnClickListener { toggleTiling() }
        actionButton.visibility = android.view.View.VISIBLE
        accessibilityButton.visibility = android.view.View.VISIBLE
    }

    private fun toggleTiling() {
        tilingEnabled = !tilingEnabled
        TilingAccessibilityService.isEnabled = tilingEnabled
        actionButton.text = if (tilingEnabled) {
            getString(R.string.stop_tiling)
        } else {
            getString(R.string.start_tiling)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            checkPermissionsAndStart()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) {
            // Continue regardless of result
            checkShizukuState()
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                statusText.text = getString(R.string.permission_denied)
                actionButton.text = getString(R.string.grant_permission)
                actionButton.setOnClickListener { Shizuku.requestPermission(REQUEST_CODE) }
            }
        }
    }

    override fun onBinderReceived() {
        checkPermissionsAndStart()
    }

    override fun onBinderDead() {
        statusText.text = getString(R.string.shizuku_not_running)
        actionButton.text = getString(R.string.retry)
        actionButton.setOnClickListener { checkShizukuState() }
    }

    override fun onResume() {
        super.onResume()
        // Self-heal on return to the setup screen: covers the OS pruning the
        // accessibility service while the app process was already alive
        // (fresh binds heal in ShizukuServiceConnection.onServiceConnected).
        try {
            serviceConnection.service?.ensureAccessibilityService(
                ShizukuServiceConnection.ACCESSIBILITY_COMPONENT
            )
        } catch (_: Exception) {
            // Non-fatal; taskbar warning indicator covers it
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(this)
        Shizuku.removeBinderDeadListener(this)
        super.onDestroy()
    }
}
