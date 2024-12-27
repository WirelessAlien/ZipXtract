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

package com.wirelessalien.zipxtract.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractRarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream

class OpenWithActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileName = "Crash_Log.txt"
        val crashLogFile = File(cacheDir, fileName)
        if (crashLogFile.exists()) {
            val crashLog = StringBuilder()
            try {
                val reader = BufferedReader(FileReader(crashLogFile))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    crashLog.append(line)
                    crashLog.append('\n')
                }
                reader.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            val dialogView = layoutInflater.inflate(R.layout.dialog_crash_log, null)
            val textView = dialogView.findViewById<TextView>(R.id.crash_log_text)
            textView.text = crashLog.toString()

            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.crash_log))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.copy_text)) { _: DialogInterface?, _: Int ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("ShowCase Crash Log", crashLog.toString())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@OpenWithActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.close), null)
                .show()
            crashLogFile.delete()
        }

        val uri = intent?.data
        if (uri != null) {
            showPasswordInputDialog(uri)
        } else {
            Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showPasswordInputDialog(uri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_open_with, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressIndicator)

        MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                progressBar.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.IO).launch {
                    val filePath = getRealPathFromURI(uri, this@OpenWithActivity)
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        if (filePath != null) {
                            handleFileExtraction(filePath, password)
                        } else {
                            Log.i("OpenWithActivity", "Failed to get file path")
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                progressBar.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.IO).launch {
                    val filePath = getRealPathFromURI(uri, this@OpenWithActivity)
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        if (filePath != null) {
                            handleFileExtraction(filePath, null)
                        } else {
                            Log.i("OpenWithActivity", "Failed to get file path")
                        }
                    }
                }
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }

    private fun handleFileExtraction(filePath: String, password: String?) {
        val fileExtension = filePath.split('.').takeLast(2).joinToString(".").lowercase()
        val supportedExtensions = listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz")

        when {
            supportedExtensions.any { fileExtension.endsWith(it) } -> {
                startExtractionCsService(filePath)
            }
            File(filePath).extension == "rar" -> {
                startRarExtractionService(filePath, password)
            }
            else -> {
                startExtractionService(filePath, password)
            }
        }
    }

    private fun getRealPathFromURI(uri: Uri, context: Context): String? {
        val returnCursor = context.contentResolver.query(uri, null, null, null, null)
        returnCursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            it.moveToFirst()
            val name = it.getString(nameIndex)
            it.getLong(sizeIndex).toString()
            val file = File(context.filesDir, name)
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val outputStream = FileOutputStream(file)
                var read = 0
                val maxBufferSize = 1 * 1024 * 1024
                val bytesAvailable: Int = inputStream?.available() ?: 0
                val bufferSize = bytesAvailable.coerceAtMost(maxBufferSize)
                val buffers = ByteArray(bufferSize)
                while (inputStream?.read(buffers).also { it1 ->
                        if (it1 != null) {
                            read = it1
                        }
                    } != -1) {
                    outputStream.write(buffers, 0, read)
                }
                inputStream?.close()
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return file.path
        }
        return null
    }

    private fun startExtractionService(file: String, password: String?) {
        val intent = Intent(this, ExtractArchiveService::class.java).apply {
            putExtra(ExtractArchiveService.EXTRA_FILE_PATH, file)
            putExtra(ExtractArchiveService.EXTRA_PASSWORD, password)
            putExtra(ExtractArchiveService.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startExtractionCsService(file: String) {
        val intent = Intent(this, ExtractCsArchiveService::class.java).apply {
            putExtra(ExtractCsArchiveService.EXTRA_FILE_PATH, file)
            putExtra(ExtractCsArchiveService.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startRarExtractionService(file: String, password: String?) {
        val intent = Intent(this, ExtractRarService::class.java).apply {
            putExtra(ExtractRarService.EXTRA_FILE_PATH, file)
            putExtra(ExtractRarService.EXTRA_PASSWORD, password)
            putExtra(ExtractRarService.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
