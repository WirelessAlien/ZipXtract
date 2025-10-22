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

package com.wirelessalien.zipxtract.helper

import android.content.ContentValues
import android.content.Context
import androidx.core.database.sqlite.transaction
import com.wirelessalien.zipxtract.helper.FileDbHelper.Companion.COLUMN_FILE_PATH
import com.wirelessalien.zipxtract.helper.FileDbHelper.Companion.COLUMN_ITEM_NAME
import com.wirelessalien.zipxtract.helper.FileDbHelper.Companion.COLUMN_JOB_ID
import com.wirelessalien.zipxtract.helper.FileDbHelper.Companion.TABLE_NAME
import java.util.UUID

class FileOperationsDao(context: Context) {

    private val dbHelper = FileDbHelper(context)

    fun addFilesForJob(files: List<String>): String {
        val filePairs = files.map { it to null }
        return addFilePairsForJob(filePairs)
    }

    fun addFilePairsForJob(files: List<Pair<String, String?>>): String {
        val jobId = UUID.randomUUID().toString()
        val db = dbHelper.writableDatabase
        db.transaction {
            try {
                for ((filePath, itemName) in files) {
                    val values = ContentValues().apply {
                        put(COLUMN_JOB_ID, jobId)
                        put(COLUMN_FILE_PATH, filePath)
                        put(COLUMN_ITEM_NAME, itemName)
                    }
                    insert(TABLE_NAME, null, values)
                }
            } finally {
            }
        }
        return jobId
    }

    fun getFilesForJob(jobId: String): List<String> {
        return getFilePairsForJob(jobId).map { it.first }
    }

    fun getFileForJob(jobId: String): String? {
        return getFilePairsForJob(jobId).firstOrNull()?.first
    }

    fun getFilePairsForJob(jobId: String): List<Pair<String, String?>> {
        val files = mutableListOf<Pair<String, String?>>()
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COLUMN_FILE_PATH, COLUMN_ITEM_NAME),
            "$COLUMN_JOB_ID = ?",
            arrayOf(jobId),
            null,
            null,
            null
        )
        with(cursor) {
            while (moveToNext()) {
                val filePath = getString(getColumnIndexOrThrow(COLUMN_FILE_PATH))
                val itemName = getString(getColumnIndexOrThrow(COLUMN_ITEM_NAME))
                files.add(filePath to itemName)
            }
        }
        cursor.close()
        return files
    }



    fun deleteFilesForJob(jobId: String) {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, "$COLUMN_JOB_ID = ?", arrayOf(jobId))
    }
}