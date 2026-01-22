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
import android.provider.MediaStore
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.StorageHelper
import com.wirelessalien.zipxtract.model.FileItem
import java.io.File

class ArchiveRepository(private val context: Context, private val sharedPreferences: SharedPreferences) {

    private val fileOperationsDao by lazy { FileOperationsDao(context) }

    fun addFilesForJob(files: List<String>): String {
        return fileOperationsDao.addFilesForJob(files)
    }

    private val archiveExtensions = listOf(
        "rar",
        "r00",
        "001",
        "7z",
        "7z.001",
        "zip",
        "tar",
        "gz",
        "bz2",
        "xz",
        "lz4",
        "lzma",
        "sz"
    )

    fun getArchiveFiles(query: String?, extension: String?, sortBy: String, sortAscending: Boolean): List<FileItem> {
        val archiveFiles = ArrayList<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (extension != null) {
            selectionParts.add("${MediaStore.Files.FileColumns.DATA} LIKE ?")
            selectionArgs.add("%.$extension")
        } else {
            val extensionSelection = archiveExtensions.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            }
            selectionParts.add("($extensionSelection)")
            selectionArgs.addAll(archiveExtensions.map { "%.$it" })
        }

        if (!query.isNullOrBlank()) {
            selectionParts.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            selectionArgs.add("%$query%")
        }

        val finalSelection = selectionParts.joinToString(" AND ")
        val finalSelectionArgs = selectionArgs.toTypedArray()

        val sortOrderColumn = when (sortBy) {
            "SORT_BY_NAME" -> MediaStore.Files.FileColumns.DISPLAY_NAME
            "SORT_BY_SIZE" -> MediaStore.Files.FileColumns.SIZE
            "SORT_BY_MODIFIED" -> MediaStore.Files.FileColumns.DATE_MODIFIED
            "SORT_BY_EXTENSION" -> MediaStore.Files.FileColumns.DISPLAY_NAME // Fallback for extension sort
            else -> MediaStore.Files.FileColumns.DISPLAY_NAME
        }
        val sortDirection = if (sortAscending) "ASC" else "DESC"
        val sortOrder = "$sortOrderColumn $sortDirection"

        try {
            context.contentResolver.query(
                uri,
                projection,
                finalSelection,
                finalSelectionArgs,
                sortOrder
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)

                    if (path != null && StorageHelper.isAndroidDataDir(path, context)) {
                        continue
                    }

                    if (path != null) {
                        val file = File(path)

                        if (!showHiddenFiles && file.name.startsWith(".")) continue

                        if (file.isFile && archiveExtensions.contains(file.extension.lowercase())) {
                            archiveFiles.add(FileItem.fromFile(file))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (sortBy == "SORT_BY_EXTENSION") {
            if (sortAscending) {
                archiveFiles.sortBy { it.file.extension }
            } else {
                archiveFiles.sortByDescending { it.file.extension }
            }
        }

        return archiveFiles
    }
}
