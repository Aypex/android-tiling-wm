package dev.atwm.tilingwm.taskbar

import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.atwm.tilingwm.R
import dev.atwm.tilingwm.model.BarItem
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.util.AppLauncher
import dev.atwm.tilingwm.util.IconCache

class FolderPopup(
    private val context: Context,
    private val folder: BarItem.Folder
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    fun show() {
        if (rootView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.folder_popup, null)
        val grid = view.findViewById<RecyclerView>(R.id.folder_grid)
        val root = view.findViewById<FrameLayout>(R.id.folder_root)
        val title = view.findViewById<TextView>(R.id.folder_title)

        title.text = folder.name
        grid.layoutManager = GridLayoutManager(context, 3)
        grid.adapter = FolderAdapter(folder.apps)

        root.setOnClickListener { dismiss() }

        val taskbarHeightPx = (56 * context.resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            (240 * context.resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = taskbarHeightPx + (8 * context.resources.displayMetrics.density).toInt()
        }

        windowManager.addView(view, params)
        rootView = view
    }

    fun dismiss() {
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            rootView = null
        }
    }

    val isShowing: Boolean get() = rootView != null

    private inner class FolderAdapter(
        private val apps: List<BarItem.PinnedApp>
    ) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.app_drawer_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            val cn = ComponentName.unflattenFromString(app.componentName)
            if (cn != null) {
                holder.icon.setImageDrawable(IconCache.getIcon(context, cn))
            }
            holder.itemView.setOnClickListener {
                if (cn != null) {
                    AppLauncher.launch(context, cn)
                    TilingAccessibilityService.instance?.forceRetile()
                }
                dismiss()
            }
        }

        override fun getItemCount() = apps.size
    }
}
