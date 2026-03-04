package dev.atwm.tilingwm

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.atwm.tilingwm.service.ShizukuServiceConnection
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.service.WindowTilingServiceImpl
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(),
    Shizuku.OnRequestPermissionResultListener,
    Shizuku.OnBinderReceivedListener,
    Shizuku.OnBinderDeadListener {

    companion object {
        private const val REQUEST_CODE = 1
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

        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQUEST_CODE) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                statusText.text = getString(R.string.permission_denied)
                actionButton.text = getString(R.string.grant_permission)
                actionButton.setOnClickListener { Shizuku.requestPermission(REQUEST_CODE) }
            }
        }
    }

    override fun onBinderReceived() {
        checkShizukuState()
    }

    override fun onBinderDead() {
        statusText.text = getString(R.string.shizuku_not_running)
        actionButton.text = getString(R.string.retry)
        actionButton.setOnClickListener { checkShizukuState() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(this)
        Shizuku.removeBinderDeadListener(this)
        super.onDestroy()
    }
}
