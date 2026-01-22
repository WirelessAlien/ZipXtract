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
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.model.FileItem
import com.wirelessalien.zipxtract.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayList

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val repository = FileRepository(application, sharedPreferences)

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _storageInfo = MutableStateFlow<FileRepository.StorageInfo?>(null)
    val storageInfo: StateFlow<FileRepository.StorageInfo?> = _storageInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _quickSearchResults = MutableStateFlow<List<FileItem>>(emptyList())
    val quickSearchResults: StateFlow<List<FileItem>> = _quickSearchResults.asStateFlow()

    private var currentPath: String? = null
    private var currentQuery: String? = null
    
    private var searchJob: Job? = null
    private var quickSearchJob: Job? = null

    fun updateFiles(newFiles: List<FileItem>) {
        _files.value = newFiles
    }

    fun loadFiles(path: String?, sortBy: String, sortAscending: Boolean) {
        currentPath = path
        currentQuery = null
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val fileList = repository.getFiles(path, sortBy, sortAscending)
            _files.value = fileList ?: emptyList()
            _isLoading.value = false
        }
        updateStorageInfo(path)
    }

    fun searchFiles(query: String?, fastSearch: Boolean) {
        currentQuery = query
        if (query.isNullOrEmpty()) {
            // Reload current path if query is cleared
            val sortBy = sharedPreferences.getString("sortBy", "SORT_BY_NAME") ?: "SORT_BY_NAME"
            val sortAscending = sharedPreferences.getBoolean("sortAscending", true)
            loadFiles(currentPath, sortBy, sortAscending)
            return
        }

        searchJob?.cancel()
        _isLoading.value = true
        _error.value = null

        searchJob = viewModelScope.launch {
            val flow = if (fastSearch) {
                 repository.searchFilesWithMediaStore(query)
            } else {
                 val root = if (currentPath != null) File(currentPath!!) else android.os.Environment.getExternalStorageDirectory()
                 repository.searchAllFiles(root, query)
            }

            flow.flowOn(Dispatchers.IO)
                .catch { e ->
                    _error.value = e.message
                    _isLoading.value = false
                }
                .collect { result ->
                    _files.value = result
                    _isLoading.value = false
                }
        }
    }

    fun updateStorageInfo(path: String?) {
        if (path == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val info = repository.getStorageInfo(path)
            _storageInfo.value = info
        }
    }

    fun quickSearch(query: String) {
        quickSearchJob?.cancel()
        quickSearchJob = viewModelScope.launch {
            repository.searchFilesWithMediaStore(query, 50)
                .flowOn(Dispatchers.IO)
                .collect { result ->
                    _quickSearchResults.value = result
                }
        }
    }

    @Suppress("DEPRECATION")
    fun processFileEvents(events: List<Pair<Int, File>>, sortBy: String, sortAscending: Boolean) {
         viewModelScope.launch(Dispatchers.Default) {
            _files.update { currentFiles ->
                val snapshotFiles = ArrayList(currentFiles)
                
                for ((event, file) in events) {
                    when {
                        (event and FileObserver.CREATE) != 0 || (event and FileObserver.MOVED_TO) != 0 -> {
                            val existingPosition = snapshotFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
                            if (existingPosition != -1) {
                                snapshotFiles[existingPosition] = FileItem.fromFile(file)
                            } else {
                                snapshotFiles.add(FileItem.fromFile(file))
                            }
                        }
                        (event and FileObserver.DELETE) != 0 || (event and FileObserver.DELETE_SELF) != 0 || (event and FileObserver.MOVED_FROM) != 0 -> {
                            val position = snapshotFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
                            if (position != -1) {
                                snapshotFiles.removeAt(position)
                            }
                        }
                        (event and FileObserver.MODIFY) != 0 -> {
                            val position = snapshotFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
                            if (position != -1) {
                                snapshotFiles[position] = FileItem.fromFile(file)
                            }
                        }
                    }
                }
                
                val comparator = when (sortBy) {
                    "SORT_BY_NAME" -> compareBy<FileItem> { it.file.name }
                    "SORT_BY_SIZE" -> compareBy { it.size }
                    "SORT_BY_MODIFIED" -> compareBy { it.lastModified }
                    "SORT_BY_EXTENSION" -> compareBy { it.file.extension }
                    else -> compareBy { it.file.name }
                }

                val finalComparator = if (sortAscending) {
                    compareBy<FileItem> { !it.isDirectory }.then(comparator)
                } else {
                    compareBy<FileItem> { !it.isDirectory }.then(comparator.reversed())
                }

                snapshotFiles.sortWith(finalComparator)
                snapshotFiles
            }
         }
    }
}
