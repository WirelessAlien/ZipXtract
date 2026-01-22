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

package com.wirelessalien.zipxtract.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wirelessalien.zipxtract.fragment.SevenZipFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.RandomAccessFile
import java.util.Date

class SevenZipViewModel(application: Application) : AndroidViewModel(application) {

    private val _archiveItems = MutableStateFlow<List<SevenZipFragment.ArchiveItem>>(emptyList())
    val archiveItems: StateFlow<List<SevenZipFragment.ArchiveItem>> = _archiveItems.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var inArchive: IInArchive? = null
    var archivePath: String? = null
    var currentPath: String = ""

    fun openArchive(path: String) {
        archivePath = path
        viewModelScope.launch(Dispatchers.IO) {
            try {
                closeArchive()
                val randomAccessFile = RandomAccessFile(path, "r")
                inArchive = SevenZip.openInArchive(null, RandomAccessFileInStream(randomAccessFile))
                loadArchiveItems(currentPath)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun loadArchiveItems(path: String) {
        currentPath = path
        val children = mutableMapOf<String, SevenZipFragment.ArchiveItem>()

        try {
            inArchive?.let { archive ->
                val count = archive.numberOfItems
                for (i in 0 until count) {
                    val itemPath = archive.getProperty(i, PropID.PATH) as String

                    if (itemPath.replace("\\", "/").let { p -> p.startsWith(currentPath) && p != currentPath && (currentPath.isEmpty() || p.substring(currentPath.length).removePrefix("/").isNotEmpty()) }) {
                        val relativePath = itemPath.substring(currentPath.length).removePrefix("/")

                        val separatorIndex = relativePath.indexOf('/')
                        if (separatorIndex > -1) {
                            // It's in a subdirectory
                            val dirName = relativePath.substring(0, separatorIndex)
                            val dirPath = if (currentPath.isEmpty()) dirName else "$currentPath/$dirName"
                            if (!children.containsKey(dirPath)) {
                                children[dirPath] = SevenZipFragment.ArchiveItem(dirPath, true, 0, null)
                            }
                        } else {
                            // It's a direct child file
                            val isDirectory = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                            val size = archive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                            val lastModified = archive.getProperty(i, PropID.LAST_MODIFICATION_TIME) as? Date
                            if (!children.containsKey(itemPath)) {
                                children[itemPath] = SevenZipFragment.ArchiveItem(itemPath, isDirectory, size, lastModified)
                            }
                        }
                    }
                }
                _archiveItems.value = children.values.toList().sortedBy { it.path }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _error.value = e.message
        }
    }

    fun reloadArchive() {
        archivePath?.let { openArchive(it) }
    }

    private fun closeArchive() {
        try {
            inArchive?.close()
            inArchive = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeArchive()
    }
}
