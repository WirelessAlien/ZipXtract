package com.wirelessalien.zipxtract.adapter

import android.content.Context
import android.icu.text.DateFormat
import android.os.Build
import android.util.SparseBooleanArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ItemFileBinding
import com.wirelessalien.zipxtract.fragment.MainFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date
import java.util.Locale

class FileAdapter(private val context: Context, private val mainFragment: MainFragment?, val files: ArrayList<File>, private var filteredFiles: List<File> = emptyList()) :
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

    inner class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root), View.OnLongClickListener, View.OnClickListener {
        init {
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
            binding.cardView.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            if (v?.id == R.id.card_view) {
                onLongClick(v)
            } else {
                if (mainFragment?.actionMode != null) {
                    mainFragment.toggleSelection(adapterPosition)
                    if (mainFragment.getSelectedItemCount() == 0) {
                        mainFragment.actionMode?.finish()
                    }
                } else {
                    onItemClickListener?.onItemClick(filteredFiles[adapterPosition])
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            mainFragment?.startActionMode(adapterPosition)
            return true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = filteredFiles[position]
        val binding = holder.binding

        binding.fileName.text = file.name

        val dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        binding.fileDate.text = dateFormat.format(Date(getFileTimeOfCreation(file)))

        if (file.isDirectory) {
            binding.fileIcon.setImageResource(R.drawable.ic_folder)
            binding.fileSize.text = context.getString(R.string.folder)
            binding.fileIcon.visibility = View.VISIBLE
            binding.fileExtension.visibility = View.GONE
        } else {
            binding.fileSize.text = bytesToString(file.length())

            when (file.extension.lowercase(Locale.getDefault())) {
                "png", "jpg", "bmp", "jpeg", "gif", "webp" -> {
                    val requestBuilder = Glide.with(context)
                        .asDrawable()
                        .sizeMultiplier(0.25f)

                    Glide.with(context)
                        .load(file)
                        .thumbnail(requestBuilder)
                        .into(binding.fileIcon)
                    binding.fileIcon.visibility = View.VISIBLE
                    binding.fileExtension.visibility = View.GONE
                }
                else -> {
                    binding.fileIcon.visibility = View.GONE
                    binding.fileExtension.visibility = View.VISIBLE
                    binding.fileExtension.text = if (file.extension.isNotEmpty()) {
                        if (file.extension.length > 4) {
                            "FILE"
                        } else {
                            if (file.extension.length == 4) {
                                binding.fileExtension.textSize = 16f
                            } else {
                                binding.fileExtension.textSize = 18f
                            }
                            file.extension.uppercase(Locale.getDefault())
                        }
                    } else {
                        "..."
                    }
                }
            }
        }

        if (selectedItems.get(position, false)) {
            binding.checkIcon.visibility = View.VISIBLE
            binding.linearLayout.setBackgroundColor(context.getColor(R.color.md_theme_primary_90))
        } else {
            binding.checkIcon.visibility = View.GONE
            binding.linearLayout.setBackgroundColor(context.getColor(R.color.md_theme_surface))
        }
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun removeItem(position: Int) {
        files.removeAt(position)
        notifyItemRemoved(position)
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
        return try {
            if (file.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    attr.lastModifiedTime().toMillis()
                } else {
                    file.lastModified()
                }
            } else {
                System.currentTimeMillis()
            }
        } catch (e: NoSuchFileException) {
            System.currentTimeMillis()
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