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
import android.os.FileObserver
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.model.FileItem
import com.wirelessalien.zipxtract.repository.FileRepository
import com.wirelessalien.zipxtract.service.Archive7zService
import com.wirelessalien.zipxtract.service.ArchiveSplitZipService
import com.wirelessalien.zipxtract.service.ArchiveTarService
import com.wirelessalien.zipxtract.service.ArchiveZipService
import com.wirelessalien.zipxtract.service.CompressCsArchiveService
import com.wirelessalien.zipxtract.service.CopyMoveService
import com.wirelessalien.zipxtract.service.DeleteFilesService
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.util.ArrayList

class MainViewModel(application: Application) : AndroidViewModel(application) {

    sealed class OperationEvent {
        data class Start(val type: String) : OperationEvent()
        data class Progress(val progress: Int, val type: String) : OperationEvent()
        data class Complete(val path: String?, val type: String) : OperationEvent()
        data class Error(val message: String?, val type: String) : OperationEvent()
    }

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val repository = FileRepository(application, sharedPreferences)

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _storageInfo = MutableStateFlow<FileRepository.StorageInfo?>(null)
    val storageInfo: StateFlow<FileRepository.StorageInfo?> = _storageInfo.asStateFlow()

    private val _storageWarning = MutableStateFlow<Boolean>(false)
    val storageWarning: StateFlow<Boolean> = _storageWarning.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _quickSearchResults = MutableStateFlow<List<FileItem>>(emptyList())
    val quickSearchResults: StateFlow<List<FileItem>> = _quickSearchResults.asStateFlow()

    private val _operationEvent = MutableSharedFlow<OperationEvent>()
    val operationEvent: SharedFlow<OperationEvent> = _operationEvent.asSharedFlow()

    private var currentPath: String? = null
    private var currentQuery: String? = null
    
    private var searchJob: Job? = null
    private var quickSearchJob: Job? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModelScope.launch {
                when (intent?.action) {
                    BroadcastConstants.ACTION_EXTRACTION_PROGRESS -> {
                        val progress = intent.getIntExtra(BroadcastConstants.EXTRA_PROGRESS, 0)
                        _operationEvent.emit(OperationEvent.Progress(progress, "EXTRACT"))
                    }
                    BroadcastConstants.ACTION_EXTRACTION_COMPLETE -> {
                        val dirPath = intent.getStringExtra(BroadcastConstants.EXTRA_DIR_PATH)
                        _operationEvent.emit(OperationEvent.Complete(dirPath, "EXTRACT"))
                    }
                    BroadcastConstants.ACTION_EXTRACTION_ERROR -> {
                        val errorMessage = intent.getStringExtra(BroadcastConstants.EXTRA_ERROR_MESSAGE)
                        _operationEvent.emit(OperationEvent.Error(errorMessage, "EXTRACT"))
                    }
                    BroadcastConstants.ACTION_ARCHIVE_PROGRESS -> {
                        val progress = intent.getIntExtra(BroadcastConstants.EXTRA_PROGRESS, 0)
                        _operationEvent.emit(OperationEvent.Progress(progress, "ARCHIVE"))
                    }
                    BroadcastConstants.ACTION_ARCHIVE_COMPLETE -> {
                        val dirPath = intent.getStringExtra(BroadcastConstants.EXTRA_DIR_PATH)
                        _operationEvent.emit(OperationEvent.Complete(dirPath, "ARCHIVE"))
                    }
                    BroadcastConstants.ACTION_ARCHIVE_ERROR -> {
                        val errorMessage = intent.getStringExtra(BroadcastConstants.EXTRA_ERROR_MESSAGE)
                        _operationEvent.emit(OperationEvent.Error(errorMessage, "ARCHIVE"))
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
            addAction(BroadcastConstants.ACTION_ARCHIVE_COMPLETE)
            addAction(BroadcastConstants.ACTION_ARCHIVE_ERROR)
            addAction(BroadcastConstants.ACTION_ARCHIVE_PROGRESS)
        }
        ContextCompat.registerReceiver(getApplication(), broadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(broadcastReceiver)
    }

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

    fun checkStorage(path: String, requiredSize: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stat = StatFs(path)
                val availableSize = stat.availableBytes
                val safeRequiredSize = (requiredSize * 1.1).toLong()
                _storageWarning.value = availableSize < safeRequiredSize
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startCompressService(file: String, compressionFormat: String, destinationPath: String?) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("ARCHIVE"))
        }
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), CompressCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT, compressionFormat)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startArchiveTarService(file: List<String>, archiveName: String, compressionFormat: String, destinationPath: String? = null, compressionLevel: Int = 3) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("ARCHIVE"))
        }
        val jobId = repository.addFilesForJob(file)
        val intent = Intent(getApplication(), ArchiveTarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT, compressionFormat)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, destinationPath: String? = null) {
        val jobId = repository.addFilesForJob(filesToArchive)
        val intent = Intent(getApplication(), ArchiveZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ServiceConstants.EXTRA_AES_STRENGTH, aesStrength)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startSplitZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, splitSize: Long?, destinationPath: String? = null) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("ARCHIVE"))
        }
        val jobId = repository.addFilesForJob(filesToArchive)
        val intent = Intent(getApplication(), ArchiveSplitZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ServiceConstants.EXTRA_AES_STRENGTH, aesStrength)
            putExtra(ServiceConstants.EXTRA_SPLIT_SIZE, splitSize)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startSevenZService(password: String?, archiveName: String, compressionLevel: Int, solid: Boolean, threadCount: Int, filesToArchive: List<String>, destinationPath: String? = null) {
        val jobId = repository.addFilesForJob(filesToArchive)
        val intent = Intent(getApplication(), Archive7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_SOLID, solid)
            putExtra(ServiceConstants.EXTRA_THREAD_COUNT, threadCount)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startExtractionService(file: String, password: String?, destinationPath: String?) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("EXTRACT"))
        }
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startExtractionCsService(file: String, destinationPath: String?) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("EXTRACT"))
        }
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startRarExtractionService(file: String, password: String?, destinationPath: String?) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("EXTRACT"))
        }
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractRarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startMulti7zExtractionService(file: String, password: String?, destinationPath: String?) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("EXTRACT"))
        }
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractMultipart7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startMultiZipExtractionService(file: String, password: String?, destinationPath: String?) {
        viewModelScope.launch {
            _operationEvent.emit(OperationEvent.Start("EXTRACT"))
        }
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractMultipartZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startCopyMoveService(filesToCopyMove: List<String>, destinationPath: String, isCopyAction: Boolean) {
        val jobId = repository.addFilesForJob(filesToCopyMove)
        val intent = Intent(getApplication(), CopyMoveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
            putExtra(ServiceConstants.EXTRA_IS_COPY_ACTION, isCopyAction)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startDeleteFilesService(filesToDelete: List<String>) {
        val jobId = repository.addFilesForJob(filesToDelete)
        val intent = Intent(getApplication(), DeleteFilesService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun addFilesForJob(files: List<String>): String {
        return repository.addFilesForJob(files)
    }

    fun getFilesForJob(jobId: String): List<String> {
        return repository.getFilesForJob(jobId)
    }

    fun convertToBytes(size: Long, unit: String): Long {
        return size.times(when (unit) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            "GB" -> 1024L * 1024 * 1024
            else -> 1024L
        })
    }

    fun getMultiZipPartsCount(selectedFilesSize: Long, splitZipSize: Long): Long {
        if (splitZipSize <= 0) {
            return Long.MAX_VALUE
        }
        val division = selectedFilesSize / splitZipSize
        val remainder = selectedFilesSize % splitZipSize
        return if (remainder > 0) division + 1 else division
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
