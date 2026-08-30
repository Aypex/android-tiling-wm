package dev.atwm.tilingwm.taskbar

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.atwm.tilingwm.R

class UndoSnackbar(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    fun show(message: String, accentColor: Int, onUndo: () -> Unit, durationMs: Long = 3000) {
        dismiss()

        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (16 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(ContextCompat.getColor(context, R.color.surface_elevated))
            }
        }

        val label = TextView(context).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        container.addView(label)

        val undoBtn = TextView(context).apply {
            text = context.getString(R.string.undo)
            setTextColor(accentColor)
            textSize = 14f
            val padStart = (16 * density).toInt()
            setPadding(padStart, 0, 0, 0)
            setOnClickListener {
                onUndo()
                dismiss()
            }
        }
        container.addView(undoBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = (64 * density).toInt()
            horizontalMargin = 0.04f
        }

        windowManager.addView(container, params)
        rootView = container

        dismissRunnable = Runnable { dismiss() }
        handler.postDelayed(dismissRunnable!!, durationMs)
    }

    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            rootView = null
        }
    }
}
