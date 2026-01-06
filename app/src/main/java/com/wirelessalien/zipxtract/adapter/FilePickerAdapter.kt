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
import android.os.Build
import android.util.SparseBooleanArray
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.util.size
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ItemFileBinding
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date
import java.util.Locale

class FilePickerAdapter(
    private val context: Context,
    val files: ArrayList<File>,
    private var filteredFiles: List<File> = emptyList()
) :
    RecyclerView.Adapter<FilePickerAdapter.ViewHolder>() {

    interface ActionModeProvider {
        fun startActionMode(position: Int)
        fun toggleSelection(position: Int)
        fun getSelectedItemCount(): Int
        val actionMode: androidx.appcompat.view.ActionMode?
    }

    interface OnItemClickListener {
        fun onItemClick(file: File, filePath: String = file.absolutePath)
    }

    private var onItemClickListener: OnItemClickListener? = null

    private val selectedItems = SparseBooleanArray()

    fun toggleSelection(position: Int) {
        if (selectedItems.get(position, false)) {
            selectedItems.delete(position)
        } else {
            selectedItems.put(position, true)
        }
        notifyItemChanged(position)
    }

    inner class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        init {
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return

            val file = filteredFiles[position]
            if (file.isDirectory) {
                onItemClickListener?.onItemClick(file)
            } else {
                toggleSelection(position)
                (context as? ActionModeProvider)?.toggleSelection(position)
            }
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
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())
        val dateString = dateFormat.format(Date(getFileTimeOfCreation(file)))

        if (file.isDirectory) {
            binding.fileIcon.setImageResource(R.drawable.ic_folder)
            val folderString = context.getString(R.string.folder)
            binding.fileSize.text = String.format("%s \u2022 %s", folderString, dateString)
            binding.fileIcon.visibility = View.VISIBLE
            binding.fileExtension.visibility = View.GONE
        } else {
            val sizeString = bytesToString(file.length())
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

    fun getSelectedItems(): List<File> {
        val items = mutableListOf<File>()
        for (i in 0 until selectedItems.size) {
            items.add(filteredFiles[selectedItems.keyAt(i)])
        }
        return items
    }

    @SuppressLint("NotifyDataSetChanged")
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
        } catch (_: NoSuchFileException) {
            0L
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
}