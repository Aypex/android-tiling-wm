package dev.atwm.tilingwm.taskbar

import android.content.Context
import android.graphics.PixelFormat
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.atwm.tilingwm.R
import dev.atwm.tilingwm.service.TilingAccessibilityService
import dev.atwm.tilingwm.util.AppEntry
import dev.atwm.tilingwm.util.AppLauncher
import dev.atwm.tilingwm.util.IconCache

class AppDrawerPopup(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    var onAppLongPress: ((AppEntry) -> Unit)? = null

    fun show() {
        if (rootView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.app_drawer, null)
        val grid = view.findViewById<RecyclerView>(R.id.app_grid)
        val root = view.findViewById<FrameLayout>(R.id.drawer_root)
        val searchInput = view.findViewById<EditText>(R.id.drawer_search)

        // Use real display height for accurate overlay positioning
        val realScreenHeight = windowManager.maximumWindowMetrics.bounds.height()
        val density = context.resources.displayMetrics.density
        val taskbarPx = (56 * density).toInt()
        val drawerWindowHeight = realScreenHeight - taskbarPx

        // Constrain the content panel to ~75% of the drawer window height
        val drawerContent = view.findViewById<View>(R.id.drawer_content)
        drawerContent.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (drawerWindowHeight * 0.75f).toInt()
        ).apply {
            gravity = Gravity.BOTTOM
        }

        // Populate from the warm cache if available (instant); otherwise show the
        // window now with an empty grid and load the app list off the main thread,
        // so the drawer appears immediately instead of stalling on enumeration.
        val adapter = AppAdapter(AppLauncher.peekCachedApps() ?: emptyList())

        grid.layoutManager = GridLayoutManager(context, 5)
        grid.adapter = adapter

        // Search filtering
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })

        // Tap outside grid to dismiss
        root.setOnClickListener { dismiss() }
        grid.setOnClickListener { /* consume */ }

        // Leave the bottom 56dp uncovered so the taskbar remains tappable.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            drawerWindowHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(view, params)
        rootView = view

        // Cold cache: load off the main thread, then fill the grid.
        if (AppLauncher.peekCachedApps() == null) {
            Thread {
                val apps = AppLauncher.getInstalledApps(context)
                view.post { if (rootView === view) adapter.setApps(apps) }
            }.apply { isDaemon = true }.start()
        }
    }

    fun dismiss() {
        rootView?.let {
            windowManager.removeView(it)
            rootView = null
        }
    }

    val isShowing: Boolean get() = rootView != null

    private inner class AppAdapter(
        allApps: List<AppEntry>
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var allApps: List<AppEntry> = allApps
        private var filteredApps: List<AppEntry> = allApps
        private var currentQuery: String = ""

        /** Swap in the loaded app list (async cold-load path), preserving any filter. */
        fun setApps(apps: List<AppEntry>) {
            allApps = apps
            filter(currentQuery)
        }

        fun filter(query: String) {
            currentQuery = query
            filteredApps = if (query.isBlank()) {
                allApps
            } else {
                allApps.filter { it.label.contains(query, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }

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
            val app = filteredApps[position]
            holder.label.text = app.label
            holder.icon.setImageDrawable(
                IconCache.getIcon(context, app.componentName)
            )

            holder.itemView.setOnClickListener {
                AppLauncher.launch(context, app.componentName)
                TilingAccessibilityService.instance?.forceRetile()
                dismiss()
            }

            holder.itemView.setOnLongClickListener {
                onAppLongPress?.invoke(app)
                true
            }
        }

        override fun getItemCount() = filteredApps.size
    }
}
