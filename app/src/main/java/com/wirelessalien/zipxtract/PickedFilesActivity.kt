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

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class PickedFilesActivity : AppCompatActivity(), FileAdapter.OnDeleteClickListener {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private var fileList = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selected_files)

        recyclerView = findViewById(R.id.recyclerViewFilesDialog)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fileList = getFilesInCacheDirectory(cacheDir)

            adapter = FileAdapter(fileList, this, object : FileAdapter.OnFileClickListener {
                override fun onFileClick(file: File) {
                    // do nothing
                }
            })

        recyclerView.adapter = adapter
    }

    private fun getFilesInCacheDirectory(directory: File): MutableList<File> {
        val filesList = mutableListOf<File>()

        // Recursive function to traverse the directory
        fun traverseDirectory(file: File) {
            if (file.isDirectory) {
                file.listFiles()?.forEach {
                    traverseDirectory(it)
                }
            } else {
                filesList.add(file)
            }
        }

        traverseDirectory(directory)
        return filesList
    }

    override fun onDeleteClick(file: File) {
        val cacheFile = File(cacheDir, file.name)
        cacheFile.delete()

        // use relative path to delete the file from cache directory
        val relativePath = file.path.replace("${cacheDir}/", "")
        val fileToDelete = File(cacheDir, relativePath)
        fileToDelete.delete()
        val parentFolder = fileToDelete.parentFile
        if (parentFolder != null) {
            if (parentFolder.listFiles()?.isEmpty() == true) {
                parentFolder.delete()
            }
        }

        adapter.fileList.remove(file)
        adapter.notifyDataSetChanged()
    }
}

