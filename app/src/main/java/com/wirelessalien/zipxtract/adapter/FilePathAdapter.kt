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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wirelessalien.zipxtract.databinding.ListItemFileBinding
import java.io.File

class FilePathAdapter(private val filePaths: MutableList<String>, private val onDeleteClick: (String) -> Unit) :
    RecyclerView.Adapter<FilePathAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ListItemFileBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.deleteBtn.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val filePath = filePaths[position]
                    onDeleteClick(filePath)
                }
            }
        }

        fun bind(filePath: String) {
            binding.textFileName.text = filePath

            val file = File(filePath)
            if (file.isDirectory) {
                binding.deleteBtn.visibility = View.GONE
            } else {
                binding.deleteBtn.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ListItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val filePath = filePaths[position]
        holder.bind(filePath)
    }

    override fun getItemCount(): Int {
        return filePaths.size
    }

    fun removeFilePath(filePath: String) {
        val position = filePaths.indexOf(filePath)
        if (position != -1) {
            filePaths.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, filePaths.size)
        }
    }
}