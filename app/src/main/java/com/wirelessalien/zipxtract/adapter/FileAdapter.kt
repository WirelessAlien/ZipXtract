package com.wirelessalien.zipxtract.adapter

import android.content.Context
import android.graphics.Color
import android.icu.text.DateFormat
import android.os.Build
import android.util.SparseBooleanArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(private val context: Context, private val mainActivity: MainActivity, val files: ArrayList<File>, private var filteredFiles: List<File> = emptyList()) :
    RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClick(file: File, filePath: String = file.absolutePath)
    }

    interface OnFileLongClickListener {
        fun onFileLongClick(file: File, view: View)
    }

    private var onItemClickListener: OnItemClickListener? = null
    private var onFileLongClickListener: OnFileLongClickListener? = null

    private val selectedItems = SparseBooleanArray()

    fun toggleSelection(position: Int) {
        val file = filteredFiles[position]

        if (file.isDirectory) {
            toggleDirectorySelection(position)
        } else {
            if (selectedItems.get(position, false)) {
                selectedItems.delete(position)
            } else {
                selectedItems.put(position, true)
            }
        }
        notifyDataSetChanged()
    }

    private fun toggleDirectorySelection(position: Int) {
        val directory = filteredFiles[position]

        if (selectedItems.get(position, false)) {
            selectedItems.delete(position)
        } else {
            selectedItems.put(position, true)
        }

        for (file in directory.listFiles() ?: emptyArray()) {
            val index = filteredFiles.indexOf(file)
            if (index != -1) {
                toggleSelection(index)
            }
        }
    }

    suspend fun getSelectedFilesPaths(): List<String> = withContext(Dispatchers.IO) {
        val selectedPaths = mutableListOf<String>()

        for (i in 0 until selectedItems.size()) {
            val file = filteredFiles[selectedItems.keyAt(i)]
            selectedPaths.add(file.absolutePath)

            if (file.isDirectory) {
                val directoryPaths = getFilesPathsInsideDirectory(file)
                selectedPaths.addAll(directoryPaths)
            }
        }
        selectedPaths
    }

    private suspend fun getFilesPathsInsideDirectory(directory: File): List<String> = withContext(
        Dispatchers.IO) {
        val innerPaths = mutableListOf<String>()
        for (file in directory.listFiles() ?: emptyArray()) {
            innerPaths.add(file.absolutePath)
            if (file.isDirectory) {
                innerPaths.addAll(getFilesPathsInsideDirectory(file))
            }
        }
        innerPaths
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnLongClickListener, View.OnClickListener {
        val fileIcon: ImageView = itemView.findViewById(R.id.file_icon)
        val fileName: TextView = itemView.findViewById(R.id.file_name)
        val fileSize: TextView = itemView.findViewById(R.id.file_size)
        val fileDate: TextView = itemView.findViewById(R.id.file_date)

        init {
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
        }

        override fun onClick(v: View?) {
            if (mainActivity.actionMode != null) {
                mainActivity.toggleSelection(adapterPosition)
                if (mainActivity.getSelectedItemCount() == 0) {
                    mainActivity.actionMode?.finish()
                }
            } else {
                onItemClickListener?.onItemClick(filteredFiles[adapterPosition])
            }
        }

        override fun onLongClick(v: View?): Boolean {
            mainActivity.startActionMode(adapterPosition)
            return true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = filteredFiles[position]

        holder.fileName.text = truncateFileName(file.name, 35)

        val dateFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        } else {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        }
        holder.fileDate.text = dateFormat.format(Date(getFileTimeOfCreation(file)))


        if (file.isDirectory) {
            holder.fileIcon.setImageResource(R.drawable.ic_folder)
            holder.fileSize.text = context.getString(R.string.folder)
        } else {
            holder.fileSize.text = bytesToString(file.length())

            when (file.extension) {
                "avi" -> holder.fileIcon.setImageResource(R.drawable.ic_avi)
                "bin" -> holder.fileIcon.setImageResource(R.drawable.ic_bin)
                "doc" -> holder.fileIcon.setImageResource(R.drawable.ic_doc)
                "docx" -> holder.fileIcon.setImageResource(R.drawable.ic_docx)
                "exe" -> holder.fileIcon.setImageResource(R.drawable.ic_unknown)
                "mkv" -> holder.fileIcon.setImageResource(R.drawable.ic_mkv)
                "mov" -> holder.fileIcon.setImageResource(R.drawable.ic_mov)
                "mp3" -> holder.fileIcon.setImageResource(R.drawable.ic_mp3)
                "mp4" -> holder.fileIcon.setImageResource(R.drawable.ic_mp4)
                "pdf" -> holder.fileIcon.setImageResource(R.drawable.ic_pdf)
                "ppt" -> holder.fileIcon.setImageResource(R.drawable.ic_ppt)
                "txt" -> holder.fileIcon.setImageResource(R.drawable.ic_txt)
                "xls" -> holder.fileIcon.setImageResource(R.drawable.ic_xls)
                "xlsx" -> holder.fileIcon.setImageResource(R.drawable.ic_xlsx)
                "rar" -> holder.fileIcon.setImageResource(R.drawable.ic_rar)
                "zip" -> holder.fileIcon.setImageResource(R.drawable.ic_zip)
                "apk" -> holder.fileIcon.setImageResource(R.drawable.ic_apk)
                "png", "jpg", "bmp", "jpeg" -> {
                    val requestBuilder = Glide.with(context)
                        .asDrawable()
                        .sizeMultiplier(0.25f)

                    Glide.with(context)
                        .load(file)
                        .thumbnail(requestBuilder)
                        .into(holder.fileIcon)
                }
                else -> holder.fileIcon.setImageResource(R.drawable.ic_unknown)
            }
        }
        if (selectedItems.get(position, false)) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.md_theme_outline))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

    }

    private fun truncateFileName(fileName: String, maxLength: Int): String {
        return if (fileName.length <= maxLength) {
            fileName
        } else {
            fileName.substring(0, maxLength - 3) + "..."
        }
    }

    fun updateFilesAndFilter(newFiles: ArrayList<File>, query: String? = null) {
        files.clear()
        files.addAll(newFiles)

        filteredFiles = if (!query.isNullOrBlank()) {
            files.filter { it.name.contains(query, true) || it.isDirectory }
        } else {
            files
        }

        notifyDataSetChanged()
    }

    private fun getFileTimeOfCreation(file: File): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            attr.lastModifiedTime().toMillis()
        } else {
            file.lastModified()
        }
    }

    private fun bytesToString(bytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            bytes < kilobyte -> "$bytes B"
            bytes < megabyte -> String.format("%.2f KB", bytes.toFloat() / kilobyte)
            bytes < gigabyte -> String.format("%.2f MB", bytes.toFloat() / megabyte)
            else -> String.format("%.2f GB", bytes.toFloat() / gigabyte)
        }
    }

    override fun getItemCount(): Int {
        return filteredFiles.size
    }

    fun setOnItemClickListener(onItemClickListener: OnItemClickListener) {
        this.onItemClickListener = onItemClickListener
    }

    fun setOnFileLongClickListener(onFileLongClickListener: OnFileLongClickListener) {
        this.onFileLongClickListener = onFileLongClickListener
    }
}