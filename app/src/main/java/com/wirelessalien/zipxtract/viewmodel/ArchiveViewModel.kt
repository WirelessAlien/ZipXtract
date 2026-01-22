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
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.model.FileItem
import com.wirelessalien.zipxtract.repository.ArchiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val repository = ArchiveRepository(application, sharedPreferences)

    private val _archiveFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val archiveFiles: StateFlow<List<FileItem>> = _archiveFiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentQuery: String? = null
    private var searchJob: Job? = null

    fun loadArchiveFiles(extension: String?, sortBy: String, sortAscending: Boolean) {
        currentQuery = null
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = repository.getArchiveFiles(null, extension, sortBy, sortAscending)
                _archiveFiles.value = files
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchFiles(query: String?, sortBy: String, sortAscending: Boolean) {
        currentQuery = query
        
        searchJob?.cancel()
        _isLoading.value = true
        _error.value = null

        searchJob = viewModelScope.launch(Dispatchers.IO) {
             try {
                 val files = repository.getArchiveFiles(query, null, sortBy, sortAscending)
                 _archiveFiles.value = files
             } catch (e: Exception) {
                 _error.value = e.message
             } finally {
                 _isLoading.value = false
             }
        }
    }
}
