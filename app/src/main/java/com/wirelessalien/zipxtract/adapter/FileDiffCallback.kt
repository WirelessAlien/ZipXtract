package com.wirelessalien.zipxtract.adapter

import androidx.recyclerview.widget.DiffUtil
import com.wirelessalien.zipxtract.model.FileItem

class FileDiffCallback(
    private val oldList: List<FileItem>,
    private val newList: List<FileItem>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        // Unique identifier is the absolute path
        return oldList[oldItemPosition].file.absolutePath == newList[newItemPosition].file.absolutePath
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        
        // Compare cached properties
        return oldItem.lastModified == newItem.lastModified &&
               oldItem.size == newItem.size &&
               oldItem.isDirectory == newItem.isDirectory
    }
}
