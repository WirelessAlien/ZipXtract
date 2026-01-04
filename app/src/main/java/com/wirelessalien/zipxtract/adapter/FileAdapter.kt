/*
 *  Copyright (C) 2023  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package com.wirelessalien.zipxtract.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.icu.text.DateFormat
import android.util.SparseBooleanArray
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.util.size
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ItemFileBinding
import com.wirelessalien.zipxtract.fragment.MainFragment
import com.wirelessalien.zipxtract.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

class FileAdapter(private val context: Context, private val mainFragment: MainFragment?, val files: ArrayList<FileItem>, private var filteredFiles: List<FileItem> = emptyList()) :
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
        val fileItem = filteredFiles[position]

        if (fileItem.isDirectory) {
            toggleDirectorySelection(position)
        } else {
            if (selectedItems.get(position, false)) {
                selectedItems.delete(position)
            } else {
                selectedItems.put(position, true)
            }
        }
        notifyItemChanged(position)
    }

    private fun toggleDirectorySelection(position: Int) {
        val directory = filteredFiles[position].file

        if (selectedItems.get(position, false)) {
            selectedItems.delete(position)
        } else {
            selectedItems.put(position, true)
        }

        for (file in directory.listFiles() ?: emptyArray()) {
            val index = filteredFiles.indexOfFirst { it.file == file }
            if (index != -1) {
                toggleSelection(index)
            }
        }
    }

    suspend fun getSelectedFilesPaths(): List<String> = withContext(Dispatchers.IO) {
        val selectedPaths = mutableListOf<String>()

        for (i in 0 until selectedItems.size) {
            val fileItem = filteredFiles[selectedItems.keyAt(i)]
            selectedPaths.add(fileItem.file.absolutePath)

            if (fileItem.isDirectory) {
                val directoryPaths = getFilesPathsInsideDirectory(fileItem.file)
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
            binding.fileIcon.setOnClickListener(this)
            binding.fileExtension.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            if (v?.id == R.id.card_view || v?.id == R.id.file_icon || v?.id == R.id.file_extension) {
                if (mainFragment != null && mainFragment.actionMode == null) {
                    mainFragment.startActionMode(bindingAdapterPosition)
                } else if (mainFragment != null) {
                    mainFragment.toggleSelection(bindingAdapterPosition)
                    if (mainFragment.getSelectedItemCount() == 0) {
                        mainFragment.actionMode?.finish()
                    }
                }
            } else {
                if (mainFragment?.actionMode != null) {
                    mainFragment.toggleSelection(bindingAdapterPosition)
                    if (mainFragment.getSelectedItemCount() == 0) {
                        mainFragment.actionMode?.finish()
                    }
                } else {
                    onItemClickListener?.onItemClick(filteredFiles[bindingAdapterPosition].file)
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            mainFragment?.startActionMode(bindingAdapterPosition)
            return true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val fileItem = filteredFiles[position]
        val file = fileItem.file
        val binding = holder.binding

        binding.fileName.text = file.name

        val dateFormat =
            DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        val dateString = dateFormat.format(Date(fileItem.lastModified))

        if (fileItem.isDirectory) {
            binding.fileIcon.setImageResource(R.drawable.ic_folder)
            val folderString = context.getString(R.string.folder)
            binding.fileSize.text = String.format("%s \u2022 %s", folderString, dateString)
            binding.fileIcon.visibility = View.VISIBLE
            binding.fileExtension.visibility = View.GONE
        } else {
            val sizeString = bytesToString(fileItem.size)
            binding.fileSize.text = String.format("%s \u2022 %s", sizeString, dateString)

            val imageLoadingOptions = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(48, 48)
                .format(DecodeFormat.PREFER_RGB_565)

            when (file.extension.lowercase(Locale.getDefault())) {
                "png", "jpg", "bmp", "jpeg", "gif", "webp" -> {
                    val requestBuilder = Glide.with(context)
                        .asDrawable()
                        .apply(imageLoadingOptions)
                        .sizeMultiplier(0.25f)

                    Glide.with(context)
                        .load(file)
                        .apply(imageLoadingOptions)
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

            val typedValue = TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerLowest,
                typedValue,
                true
            )

            val color = if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                typedValue.data
            } else {
                ContextCompat.getColor(context, typedValue.resourceId)
            }

            binding.linearLayout.backgroundTintList = ColorStateList.valueOf(color)
        } else {
            binding.checkIcon.visibility = View.GONE
            binding.linearLayout.background = AppCompatResources.getDrawable(context, R.drawable.item_background)
            binding.linearLayout.backgroundTintList = null
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun removeItem(position: Int) {
        files.removeAt(position)
        notifyItemRemoved(position)
    }

    fun updateFilesAndFilter(newFiles: List<FileItem>, query: String? = null) {
        val newFilteredFiles = if (!query.isNullOrBlank()) {
            newFiles.filter { it.file.name.contains(query, true) || it.isDirectory }
        } else {
            newFiles.toList()
        }

        val diffCallback = FileDiffCallback(filteredFiles, newFilteredFiles)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        files.clear()
        files.addAll(newFiles)
        filteredFiles = newFilteredFiles

        diffResult.dispatchUpdatesTo(this)
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