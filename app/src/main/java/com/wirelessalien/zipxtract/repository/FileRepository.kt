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

package com.wirelessalien.zipxtract.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.StorageHelper
import com.wirelessalien.zipxtract.model.FileItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.file.Files

class FileRepository(private val context: Context, private val sharedPreferences: SharedPreferences) {

    private val fileOperationsDao by lazy { FileOperationsDao(context) }

    fun addFilesForJob(files: List<String>): String {
        return fileOperationsDao.addFilesForJob(files)
    }

    fun addFilePairsForJob(files: List<Pair<String, String?>>): String {
        return fileOperationsDao.addFilePairsForJob(files)
    }

    fun getFilesForJob(jobId: String): List<String> {
        return fileOperationsDao.getFilesForJob(jobId)
    }

    fun deleteFilesForJob(jobId: String) {
        fileOperationsDao.deleteFilesForJob(jobId)
    }

    fun getFiles(currentPath: String?, sortBy: String, sortAscending: Boolean, onlyDirectories: Boolean = false): List<FileItem>? {
        val files = ArrayList<FileItem>()
        val directories = ArrayList<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)

        val directory = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

        if (!directory.canRead()) {
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.newDirectoryStream(directory.toPath()).use { directoryStream ->
                    for (path in directoryStream) {
                        try {
                            val item = FileItem.fromPath(path)
                            if (!showHiddenFiles && item.file.name.startsWith(".")) continue
                            if (item.isDirectory) directories.add(item) 
                            else if (!onlyDirectories) files.add(item)
                        } catch (e: Exception) {
                            val file = path.toFile()
                            if (!showHiddenFiles && file.name.startsWith(".")) continue
                            if (file.isDirectory) directories.add(FileItem.fromFile(file)) 
                            else if (!onlyDirectories) files.add(FileItem.fromFile(file))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                fallbackListFiles(directory, showHiddenFiles, directories, files, onlyDirectories)
            }
        } else {
            fallbackListFiles(directory, showHiddenFiles, directories, files, onlyDirectories)
        }

        if (files.isEmpty() && directories.isEmpty()) {
            return ArrayList()
        }

        sortFiles(directories, files, sortBy)

        if (!sortAscending) {
            directories.reverse()
            files.reverse()
        }

        val combinedList = ArrayList<FileItem>()
        combinedList.addAll(directories)
        if (!onlyDirectories) {
            combinedList.addAll(files)
        }

        return combinedList
    }

    private fun fallbackListFiles(
        directory: File,
        showHiddenFiles: Boolean,
        directories: ArrayList<FileItem>,
        files: ArrayList<FileItem>,
        onlyDirectories: Boolean
    ) {
        val fileList = directory.listFiles()
        if (fileList != null) {
            for (file in fileList) {
                if (!showHiddenFiles && file.name.startsWith(".")) continue

                if (file.isDirectory) {
                    directories.add(FileItem.fromFile(file))
                } else if (!onlyDirectories) {
                    files.add(FileItem.fromFile(file))
                }
            }
        }
    }

    private fun sortFiles(directories: ArrayList<FileItem>, files: ArrayList<FileItem>, sortBy: String) {
        when (sortBy) {
            "SORT_BY_NAME" -> {
                directories.sortBy { it.file.name }
                files.sortBy { it.file.name }
            }
            "SORT_BY_SIZE" -> {
                directories.sortBy { it.size }
                files.sortBy { it.size }
            }
            "SORT_BY_MODIFIED" -> {
                directories.sortBy { it.lastModified }
                files.sortBy { it.lastModified }
            }
            "SORT_BY_EXTENSION" -> {
                directories.sortBy { it.file.extension }
                files.sortBy { it.file.extension }
            }
        }
    }

    fun searchAllFiles(directory: File, query: String): Flow<List<FileItem>> = flow {
        val results = mutableListOf<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)
        var lastEmitTime = 0L

        suspend fun searchRecursively(dir: File) {
            val files = dir.listFiles() ?: return

            for (file in files) {
                val filePath = file.absolutePath
                if (StorageHelper.isAndroidDataDir(filePath, context)) {
                    continue
                }

                if (!showHiddenFiles && file.name.startsWith(".")) continue

                if (!currentCoroutineContext().isActive) return

                if (file.isDirectory) {
                    if (file.name.contains(query, true)) {
                        results.add(FileItem.fromFile(file))
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastEmitTime > 300) {
                            emit(results.toList())
                            lastEmitTime = currentTime
                        }
                    }
                    searchRecursively(file)
                } else if (file.name.contains(query, true)) {
                    results.add(FileItem.fromFile(file))
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastEmitTime > 300) {
                        emit(results.toList())
                        lastEmitTime = currentTime
                    }
                }
            }
        }

        searchRecursively(directory)
        emit(results.toList())
    }

    fun searchFilesWithMediaStore(query: String, limit: Int = -1): Flow<List<FileItem>> = flow {
        val results = mutableListOf<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            context.contentResolver.query(
                queryUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                var lastEmitTime = 0L
                while (cursor.moveToNext()) {
                    if (!currentCoroutineContext().isActive) break

                    val filePath = cursor.getString(dataColumn)

                    if (filePath == null || StorageHelper.isAndroidDataDir(filePath, context)) {
                        continue
                    }

                    val file = File(filePath)

                    if (!showHiddenFiles && file.name.startsWith(".")) continue

                    if (file.exists()) {
                        results.add(FileItem.fromFile(file))
                        if (limit > 0 && results.size >= limit) {
                            break
                        }
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastEmitTime > 300) {
                            emit(results.toList())
                            lastEmitTime = currentTime
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // exceptions
        }
        emit(results.toList())
    }

    fun getStorageInfo(path: String): StorageInfo? {
         try {
            // Determine which storage volume the path belongs to
            val sdCardPath = StorageHelper.getSdCardPath(context)
            val rootPath = if (sdCardPath != null && path.startsWith(sdCardPath)) {
                sdCardPath
            } else {
                Environment.getExternalStorageDirectory().absolutePath
            }

            val stat = StatFs(rootPath)
            val totalSize = stat.totalBytes
            val availableSize = stat.availableBytes

            return StorageInfo(totalSize, availableSize)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    data class StorageInfo(val totalSize: Long, val availableSize: Long)
}
