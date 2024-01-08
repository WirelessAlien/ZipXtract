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

package com.wirelessalien.zipxtract

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileAdapter(
    val fileList: MutableList<File>,
    private val onDeleteClickListener: OnDeleteClickListener,
    private val onFileClickListener: OnFileClickListener
) :
    RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.list_item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = fileList[position]
        holder.fileName.text = file.name

        // Set up the delete button click listener
        holder.deleteButton.setOnClickListener {
            onDeleteClickListener.onDeleteClick(file)
        }

        // Set up the file click listener
        holder.fileName.setOnClickListener {
            onFileClickListener.onFileClick(file)
        }

    }

    override fun getItemCount(): Int {
        return fileList.size
    }

    // ViewHolder class with references to UI elements
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileName: TextView = itemView.findViewById(R.id.textFileName)
        val deleteButton: Button = itemView.findViewById(R.id.deleteBtn)
    }

    // Interface for handling delete button clicks
    interface OnDeleteClickListener {
        fun onDeleteClick(file: File)
    }

    interface OnFileClickListener {
        fun onFileClick(file: File)
    }

    fun updateFileList(newFileList: MutableList<File>) {
        fileList.clear()
        fileList.addAll(newFileList)
        notifyDataSetChanged()
    }
}
