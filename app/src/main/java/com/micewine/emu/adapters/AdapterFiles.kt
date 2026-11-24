package com.micewine.emu.adapters

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.micewine.emu.R
import com.micewine.emu.activities.MainActivity.Companion.ACTION_SELECT_FILE_MANAGER
import com.micewine.emu.activities.MainActivity.Companion.customRootFSPath
import com.micewine.emu.activities.MainActivity.Companion.fileManagerCwd
import com.micewine.emu.activities.MainActivity.Companion.fileManagerDefaultDir
import com.micewine.emu.activities.MainActivity.Companion.selectedFile
import com.micewine.emu.activities.MainActivity.Companion.usrDir
import com.micewine.emu.core.WineWrapper.extractIcon
import com.micewine.emu.fragments.FloatingFileManagerFragment.Companion.refreshFiles
import java.io.File
import java.util.Locale

class AdapterFiles(
    private val fileList: List<FileList>,
    private val context: Context,
    private val isFloatFilesDialog: Boolean,
    private val onRootFsSelected: ((String) -> Unit)? = null
) : RecyclerView.Adapter<AdapterFiles.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_files_item, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = fileList[position].file
        holder.fileName.text =
            if (fileManagerCwd == fileManagerDefaultDir) file.name.uppercase(Locale.ROOT)
            else file.name

        if (file.isDirectory) {
            holder.icon.setImageResource(R.drawable.ic_folder)
        } else if (file.isFile && file.name.endsWith(".exe", ignoreCase = true)) {
            val output = "$usrDir/icons/${file.nameWithoutExtension}-icon.ico"
            extractIcon(file, output)
            if (File(output).exists()) {
                holder.icon.setImageBitmap(BitmapFactory.decodeFile(output))
            } else {
                holder.icon.setImageResource(R.drawable.ic_log)
            }
        } else {
            holder.icon.setImageResource(R.drawable.ic_log)
        }

        // Capture the bound file directly. This avoids relying on RecyclerView
        // adapter positions when the floating RootFS picker refreshes its list.
        val click = View.OnClickListener {
            handleFileClick(file)
        }
        holder.itemView.setOnClickListener(click)
        holder.fileName.setOnClickListener(click)
        holder.icon.setOnClickListener(click)
    }

    private fun handleFileClick(file: File) {

        if (isFloatFilesDialog) {
            when {
                file.name == ".." -> {
                    fileManagerCwd = File(fileManagerCwd).parent ?: fileManagerCwd
                    refreshFiles()
                }
                file.isDirectory -> {
                    fileManagerCwd = file.absolutePath
                    refreshFiles()
                }
                file.isFile -> {
                    val name = file.name.lowercase(Locale.ROOT)
                    val supportedRootFs = name.endsWith(".zip") ||
                        name.endsWith(".rat") ||
                        name.endsWith(".rat.xz") ||
                        name.endsWith(".tar.xz")

                    if (supportedRootFs) {
                        val path = file.canonicalPath
                        customRootFSPath = path
                        Toast.makeText(context, "RootFS selected", Toast.LENGTH_SHORT).show()
                        onRootFsSelected?.invoke(path)
                    } else {
                        Toast.makeText(
                            context,
                            "Please select a .rat or .zip RootFS file",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            val intent = Intent(ACTION_SELECT_FILE_MANAGER).apply {
                putExtra("selectedFile", file.absolutePath)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun getItemCount(): Int = fileList.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnLongClickListener {
        val fileName: TextView = itemView.findViewById(R.id.title_preferences_model)
        val icon: ImageView = itemView.findViewById(R.id.set_img)

        init {
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnLongClickListener(this)
        }

        override fun onLongClick(v: View): Boolean {
            val position = adapterPosition
            if (position == RecyclerView.NO_POSITION) return false
            selectedFile = fileList[position].file.absolutePath
            return (fileManagerCwd == fileManagerDefaultDir) || selectedFile == ".."
        }
    }

    class FileList(var file: File)
}
