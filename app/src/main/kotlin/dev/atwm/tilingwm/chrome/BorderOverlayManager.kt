package dev.atwm.tilingwm.chrome

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Draws border frames around tiled windows, plus a draggable divider on the
 * master/stack split so the split ratio can be resized by touch.
 *
 * Frames are visual-only (FLAG_NOT_TOUCHABLE) so app content underneath stays
 * interactive; only the divider strip consumes input. This is the experiment
 * for interactive-resize latency: divider drags are throttled to
 * [DRAG_DISPATCH_MS] and each dispatch triggers a full retile.
 */
class BorderOverlayManager(
    private val context: Context,
    private val onMasterRatioChanged: (Float) -> Unit
) {
    companion object {
        private const val TAG = "ATWM-Borders"
        private const val DRAG_DISPATCH_MS = 33L
        private const val MIN_RATIO = 0.2f
        private const val MAX_RATIO = 0.8f
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private val borderWidthPx = (3 * density).toInt()
    private val dividerTouchPx = (32 * density).toInt()

    private val frames = mutableListOf<View>()
    private var divider: FrameLayout? = null

    private var usable = Rect()
    private var portrait = true
    private var lastDragDispatch = 0L

    fun update(
        windowBounds: List<Rect>,
        usableArea: Rect,
        accentColor: Int,
        showDivider: Boolean,
        isPortrait: Boolean
    ) {
        usable = Rect(usableArea)
        portrait = isPortrait

        // Frames: reuse existing views, add/remove to match count
        while (frames.size < windowBounds.size) frames.add(addFrame())
        while (frames.size > windowBounds.size) removeView(frames.removeLast())
        windowBounds.forEachIndexed { i, r ->
            val v = frames[i]
            (v.background as GradientDrawable).setStroke(borderWidthPx, accentColor)
            wm.updateViewLayout(v, frameParams(r))
        }

        if (showDivider && windowBounds.isNotEmpty()) {
            updateDivider(windowBounds[0], accentColor)
        } else {
            divider?.let { removeView(it) }
            divider = null
        }
    }

    fun clear() {
        frames.forEach { removeView(it) }
        frames.clear()
        divider?.let { removeView(it) }
        divider = null
    }

    private fun addFrame(): View {
        val v = View(context)
        v.background = GradientDrawable().apply {
            setColor(0)
            setStroke(borderWidthPx, 0)
        }
        wm.addView(v, frameParams(Rect(0, 0, 1, 1)))
        return v
    }

    private fun frameParams(r: Rect) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
    }

    /** Divider strip straddles the master window's trailing edge. */
    private fun updateDivider(masterBounds: Rect, accentColor: Int) {
        val d = divider ?: createDivider().also { divider = it }
        val handle = d.getChildAt(0)
        (handle.background as GradientDrawable).setColor(accentColor)

        val params = if (portrait) {
            dividerParams(
                Rect(
                    usable.left, masterBounds.bottom - dividerTouchPx / 2,
                    usable.right, masterBounds.bottom + dividerTouchPx / 2
                )
            )
        } else {
            dividerParams(
                Rect(
                    masterBounds.right - dividerTouchPx / 2, usable.top,
                    masterBounds.right + dividerTouchPx / 2, usable.bottom
                )
            )
        }
        handle.layoutParams = FrameLayout.LayoutParams(
            if (portrait) (48 * density).toInt() else (6 * density).toInt(),
            if (portrait) (6 * density).toInt() else (48 * density).toInt(),
            Gravity.CENTER
        )
        wm.updateViewLayout(d, params)
    }

    private fun createDivider(): FrameLayout {
        val strip = FrameLayout(context)
        val handle = View(context)
        handle.background = GradientDrawable().apply {
            cornerRadius = 3 * density
            setColor(0)
        }
        strip.addView(handle)

        strip.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastDragDispatch >= DRAG_DISPATCH_MS) {
                        lastDragDispatch = now
                        dispatchRatio(ev)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dispatchRatio(ev)
                    v.performClick()
                    true
                }
                else -> true
            }
        }
        wm.addView(strip, dividerParams(Rect(0, 0, 1, 1)))
        return strip
    }

    private fun dispatchRatio(ev: MotionEvent) {
        val ratio = if (portrait) {
            (ev.rawY - usable.top) / usable.height()
        } else {
            (ev.rawX - usable.left) / usable.width()
        }
        val clamped = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        Log.d(TAG, "divider drag -> ratio=$clamped")
        onMasterRatioChanged(clamped)
    }

    private fun dividerParams(r: Rect) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
    }

    private fun removeView(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
            // Already detached
        }
    }
}
