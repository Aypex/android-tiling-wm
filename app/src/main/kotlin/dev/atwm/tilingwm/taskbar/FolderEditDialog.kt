package dev.atwm.tilingwm.taskbar

import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.atwm.tilingwm.R
import dev.atwm.tilingwm.model.BarItem
import dev.atwm.tilingwm.util.IconCache

class FolderEditDialog(
    private val context: Context,
    private var folder: BarItem.Folder,
    private val onSave: (BarItem.Folder) -> Unit,
    private val onDelete: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private val currentApps = folder.apps.toMutableList()

    fun show() {
        if (rootView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.folder_edit_dialog, null)
        val nameEdit = view.findViewById<EditText>(R.id.folder_name_edit)
        val appList = view.findViewById<RecyclerView>(R.id.folder_app_list)
        val saveBtn = view.findViewById<Button>(R.id.save_button)
        val deleteBtn = view.findViewById<Button>(R.id.delete_button)
        val cancelBtn = view.findViewById<Button>(R.id.cancel_button)
        val backdrop = view.findViewById<FrameLayout>(R.id.dialog_backdrop)

        nameEdit.setText(folder.name)

        appList.layoutManager = LinearLayoutManager(context)
        appList.adapter = EditAdapter()

        saveBtn.setOnClickListener {
            val newName = nameEdit.text.toString().ifBlank { folder.name }
            onSave(folder.copy(name = newName, apps = currentApps.toList()))
            dismiss()
        }

        deleteBtn.setOnClickListener {
            onDelete()
            dismiss()
        }

        cancelBtn.setOnClickListener { dismiss() }
        backdrop.setOnClickListener { dismiss() }

        val params = WindowManager.LayoutParams(
            (300 * context.resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
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

    private inner class EditAdapter : RecyclerView.Adapter<EditAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.edit_app_icon)
            val label: TextView = view.findViewById(R.id.edit_app_label)
            val removeBtn: View = view.findViewById(R.id.remove_app_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.folder_edit_app_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = currentApps[position]
            holder.label.text = app.label
            val cn = ComponentName.unflattenFromString(app.componentName)
            if (cn != null) {
                holder.icon.setImageDrawable(IconCache.getIcon(context, cn))
            }
            holder.removeBtn.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    currentApps.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }

        override fun getItemCount() = currentApps.size
    }
}
