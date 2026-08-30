package dev.atwm.tilingwm.taskbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.atwm.tilingwm.R

class SettingsPanel(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    var onLayoutSelected: ((Int) -> Unit)? = null
    var onGapSelected: ((Int) -> Unit)? = null
    var onSwapSides: (() -> Unit)? = null
    var onChangeIcon: (() -> Unit)? = null
    var onAccentSelected: ((Int) -> Unit)? = null

    private val layoutIcons = intArrayOf(
        R.drawable.ic_layout_master,
        R.drawable.ic_layout_columns,
        R.drawable.ic_layout_monocle
    )
    private val layoutNames = arrayOf("Master", "Rows", "Monocle")
    private val gapLabels = arrayOf("None", "Small", "Large")

    private val accentPresets = intArrayOf(
        0xFF4FC3F7.toInt(),
        0xFF81C784.toInt(),
        0xFFFFB74D.toInt(),
        0xFFF06292.toInt(),
        0xFFBA68C8.toInt(),
        0xFFFFFFFF.toInt()
    )
    private val accentNames = arrayOf("Blue", "Green", "Orange", "Pink", "Purple", "White")

    // Color cache from resources
    private val colorSurface = ContextCompat.getColor(context, R.color.surface)
    private val colorSurfaceElevated = ContextCompat.getColor(context, R.color.surface_elevated)
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorTextSecondary = ContextCompat.getColor(context, R.color.text_secondary)

    // State
    private var accentColor: Int = ContextCompat.getColor(context, R.color.accent_default)
    private var currentLayoutIdx = 0
    private var currentGapIdx = 0
    private var density = 0f

    // View references for live updates
    private val layoutViews = mutableListOf<LinearLayout>()
    private val gapViews = mutableListOf<TextView>()
    private val accentViews = mutableListOf<View>()

    fun show(currentLayoutIndex: Int, currentGapIndex: Int, accentColor: Int) {
        if (rootView != null) return
        this.accentColor = accentColor
        this.currentLayoutIdx = currentLayoutIndex
        this.currentGapIdx = currentGapIndex
        this.density = context.resources.displayMetrics.density

        layoutViews.clear()
        gapViews.clear()
        accentViews.clear()

        val view = LayoutInflater.from(context).inflate(R.layout.settings_panel, null)
        val backdrop = view.findViewById<FrameLayout>(R.id.settings_backdrop)
        val layoutOptions = view.findViewById<LinearLayout>(R.id.layout_options)
        val gapOptions = view.findViewById<LinearLayout>(R.id.gap_options)
        val accentOptions = view.findViewById<LinearLayout>(R.id.accent_options)

        // Layout buttons
        layoutIcons.forEachIndexed { i, iconRes ->
            val btn = makeOptionButton(iconRes, layoutNames[i], i == currentLayoutIdx)
            btn.setOnClickListener {
                currentLayoutIdx = i
                onLayoutSelected?.invoke(i)
                refreshLayoutOptions()
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < layoutIcons.size - 1) lp.marginEnd = (8 * density).toInt()
            layoutOptions.addView(btn, lp)
            layoutViews.add(btn)
        }

        // Gap buttons
        gapLabels.forEachIndexed { i, label ->
            val btn = makeTextButton(label, i == currentGapIdx)
            btn.setOnClickListener {
                currentGapIdx = i
                onGapSelected?.invoke(i)
                refreshGapOptions()
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < gapLabels.size - 1) lp.marginEnd = (8 * density).toInt()
            gapOptions.addView(btn, lp)
            gapViews.add(btn)
        }

        // Accent color presets
        accentPresets.forEachIndexed { i, color ->
            val circle = makeColorCircle(color, color == accentColor, accentNames[i])
            circle.setOnClickListener {
                this.accentColor = color
                onAccentSelected?.invoke(color)
                refreshAccentOptions()
                // Rebuild layout/gap chips with new accent border
                refreshLayoutOptions()
                refreshGapOptions()
            }
            val size = (36 * density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            if (i < accentPresets.size - 1) lp.marginEnd = (12 * density).toInt()
            accentOptions.addView(circle, lp)
            accentViews.add(circle)
        }

        // Taskbar buttons — swap stays open, change icon dismisses (launches picker)
        view.findViewById<TextView>(R.id.btn_swap_sides).setOnClickListener {
            onSwapSides?.invoke()
        }
        view.findViewById<TextView>(R.id.btn_change_icon).setOnClickListener {
            onChangeIcon?.invoke()
            dismiss()
        }

        // Tap backdrop to dismiss
        backdrop.setOnClickListener { dismiss() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(view, params)
        rootView = view
    }

    fun dismiss() {
        rootView?.let {
            windowManager.removeView(it)
            rootView = null
        }
    }

    val isShowing: Boolean get() = rootView != null

    // -- Live visual updates --

    private fun refreshLayoutOptions() {
        layoutViews.forEachIndexed { i, container ->
            val selected = i == currentLayoutIdx
            container.background = makeChipBackground(selected)
            val icon = container.getChildAt(0) as ImageView
            val text = container.getChildAt(1) as TextView
            icon.imageTintList = ColorStateList.valueOf(if (selected) colorTextPrimary else colorTextSecondary)
            text.setTextColor(if (selected) colorTextPrimary else colorTextSecondary)
        }
    }

    private fun refreshGapOptions() {
        gapViews.forEachIndexed { i, tv ->
            val selected = i == currentGapIdx
            tv.background = makeChipBackground(selected)
            tv.setTextColor(if (selected) colorTextPrimary else colorTextSecondary)
        }
    }

    private fun refreshAccentOptions() {
        accentViews.forEachIndexed { i, v ->
            val color = accentPresets[i]
            val selected = color == accentColor
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke((2 * density).toInt(), colorTextPrimary)
            }
        }
    }

    // -- View builders --

    private fun makeOptionButton(iconRes: Int, label: String, selected: Boolean): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = makeChipBackground(selected)
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(if (selected) colorTextPrimary else colorTextSecondary)
            val size = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        container.addView(icon)

        val text = TextView(context).apply {
            this.text = label
            textSize = 12f
            setTextColor(if (selected) colorTextPrimary else colorTextSecondary)
            gravity = Gravity.CENTER
            val mt = (4 * density).toInt()
            setPadding(0, mt, 0, 0)
        }
        container.addView(text)

        return container
    }

    private fun makeTextButton(label: String, selected: Boolean): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(if (selected) colorTextPrimary else colorTextSecondary)
            gravity = Gravity.CENTER
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = makeChipBackground(selected)
        }
    }

    private fun makeColorCircle(color: Int, selected: Boolean, name: String): View {
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke((2 * density).toInt(), colorTextPrimary)
            }
            contentDescription = name
        }
    }

    private fun makeChipBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            setColor(if (selected) colorSurfaceElevated else colorSurface)
            if (selected) setStroke((1 * density).toInt(), accentColor)
        }
    }
}
