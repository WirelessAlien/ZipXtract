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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.model.FileItem
import com.wirelessalien.zipxtract.repository.ArchiveRepository
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    sealed class OperationEvent {
        data class Progress(val progress: Int) : OperationEvent()
        data class Complete(val path: String?) : OperationEvent()
        data class Error(val message: String?) : OperationEvent()
    }

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val repository = ArchiveRepository(application, sharedPreferences)

    private val _archiveFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val archiveFiles: StateFlow<List<FileItem>> = _archiveFiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _operationEvent = MutableSharedFlow<OperationEvent>()
    val operationEvent: SharedFlow<OperationEvent> = _operationEvent.asSharedFlow()

    private var currentQuery: String? = null
    private var searchJob: Job? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModelScope.launch {
                when (intent?.action) {
                    BroadcastConstants.ACTION_EXTRACTION_PROGRESS -> {
                        val progress = intent.getIntExtra(BroadcastConstants.EXTRA_PROGRESS, 0)
                        _operationEvent.emit(OperationEvent.Progress(progress))
                    }
                    BroadcastConstants.ACTION_EXTRACTION_COMPLETE -> {
                        val dirPath = intent.getStringExtra(BroadcastConstants.EXTRA_DIR_PATH)
                        _operationEvent.emit(OperationEvent.Complete(dirPath))
                    }
                    BroadcastConstants.ACTION_EXTRACTION_ERROR -> {
                        val errorMessage = intent.getStringExtra(BroadcastConstants.EXTRA_ERROR_MESSAGE)
                        _operationEvent.emit(OperationEvent.Error(errorMessage))
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BroadcastConstants.ACTION_EXTRACTION_COMPLETE)
            addAction(BroadcastConstants.ACTION_EXTRACTION_ERROR)
            addAction(BroadcastConstants.ACTION_EXTRACTION_PROGRESS)
        }
        ContextCompat.registerReceiver(getApplication(), broadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(broadcastReceiver)
    }

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

    fun startExtractionService(file: String, password: String?, destinationPath: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startExtractionCsService(file: String, destinationPath: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startRarExtractionService(file: String, password: String?, destinationPath: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractRarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startMulti7zExtractionService(file: String, password: String?, destinationPath: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractMultipart7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startMultiZipExtractionService(file: String, password: String?, destinationPath: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractMultipartZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }
}
