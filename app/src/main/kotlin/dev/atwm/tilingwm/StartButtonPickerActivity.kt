package dev.atwm.tilingwm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.atwm.tilingwm.util.TaskbarPrefs
import java.io.File

class StartButtonPickerActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(pickIntent, REQUEST_PICK_IMAGE)
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val destFile = File(filesDir, "start_icon.png")
                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    TaskbarPrefs(this).setStartButtonIconPath(destFile.absolutePath)
                } catch (_: Exception) {
                    // Copy failed — ignore
                }
            }
        }

        finish()
    }
}
