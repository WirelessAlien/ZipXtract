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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.util.size
import androidx.recyclerview.widget.RecyclerView
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ItemFileBinding
import com.wirelessalien.zipxtract.fragment.SevenZipFragment
import java.util.Locale

class ArchiveItemAdapter(
    private val context: Context,
    private var items: List<SevenZipFragment.ArchiveItem>
) : RecyclerView.Adapter<ArchiveItemAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClick(item: SevenZipFragment.ArchiveItem)
    }

    private var onItemClickListener: OnItemClickListener? = null
    private var onFileLongClickListener: OnFileLongClickListener? = null
    private val selectedItems = android.util.SparseBooleanArray()

    interface OnFileLongClickListener {
        fun onFileLongClick(item: SevenZipFragment.ArchiveItem, view: View)
    }

    fun toggleSelection(position: Int) {
        if (selectedItems.get(position, false)) {
            selectedItems.delete(position)
        } else {
            selectedItems.put(position, true)
        }
        notifyItemChanged(position)
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<SevenZipFragment.ArchiveItem> {
        val items = mutableListOf<SevenZipFragment.ArchiveItem>()
        for (i in 0 until selectedItems.size) {
            items.add(this.items[selectedItems.keyAt(i)])
        }
        return items
    }

    inner class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener, View.OnLongClickListener {
        init {
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
        }

        override fun onClick(v: View?) {
            onItemClickListener?.onItemClick(items[bindingAdapterPosition])
        }

        override fun onLongClick(v: View?): Boolean {
            onFileLongClickListener?.onFileLongClick(items[bindingAdapterPosition], itemView)
            return true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.fileName.text = item.path.substringAfterLast('/')

        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        val dateString = item.lastModified?.let { dateFormat.format(it) } ?: ""

        if (item.isDirectory) {
            binding.fileIcon.setImageResource(R.drawable.ic_folder)
            val folderString = context.getString(R.string.folder)
            binding.fileSize.text = String.format("%s \u2022 %s", folderString, dateString)
            binding.fileIcon.visibility = View.VISIBLE
            binding.fileExtension.visibility = View.GONE
        } else {
            val sizeString = bytesToString(item.size)
            binding.fileSize.text = String.format("%s \u2022 %s", sizeString, dateString)
            binding.fileIcon.visibility = View.GONE
            binding.fileExtension.visibility = View.VISIBLE
            val extension = item.path.substringAfterLast('.', "")
            binding.fileExtension.text = if (extension.isNotEmpty()) {
                if (extension.length > 4) {
                    "FILE"
                } else {
                    if (extension.length == 4) {
                        binding.fileExtension.textSize = 16f
                    } else {
                        binding.fileExtension.textSize = 18f
                    }
                    extension.uppercase(Locale.getDefault())
                }
            } else {
                "..."
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
    fun updateItems(newItems: List<SevenZipFragment.ArchiveItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getItem(position: Int): SevenZipFragment.ArchiveItem {
        return items[position]
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
        return items.size
    }

    fun setOnItemClickListener(onItemClickListener: OnItemClickListener) {
        this.onItemClickListener = onItemClickListener
    }

    fun setOnFileLongClickListener(onFileLongClickListener: OnFileLongClickListener) {
        this.onFileLongClickListener = onFileLongClickListener
    }
}
