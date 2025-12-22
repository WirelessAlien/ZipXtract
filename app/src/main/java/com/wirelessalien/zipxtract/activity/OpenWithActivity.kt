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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.DialogArchiveTypeBinding
import com.wirelessalien.zipxtract.databinding.DialogCrashLogBinding
import com.wirelessalien.zipxtract.databinding.PasswordInputOpenWithBinding
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractRarService
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

    private lateinit var fileOperationsDao: FileOperationsDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileOperationsDao = FileOperationsDao(this)

        handleCrashLog()

        when (intent?.action) {
            Intent.ACTION_VIEW -> handleViewIntent()
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    handleSingleSendIntent(uri)
                } else {
                    Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    handleMultipleSendIntent(uris)
                } else {
                    Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            else -> {
                Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleCrashLog() {
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

            val dialogBinding = DialogCrashLogBinding.inflate(layoutInflater)
            dialogBinding.crashLogText.text = crashLog.toString()

            MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
                .setTitle(getString(R.string.crash_log))
                .setView(dialogBinding.root)
                .setPositiveButton(getString(R.string.copy_text)) { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("ZipXtract Crash Log", crashLog.toString())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@OpenWithActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.close), null)
                .show()
            crashLogFile.delete()
        }
    }

    private fun handleViewIntent() {
        val uri = intent?.data
        if (uri != null) {
            processFileAndCheckEncryption(uri)
        } else {
            Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun processFileAndCheckEncryption(uri: Uri) {
        val progressDialog = MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setTitle(getString(R.string.copying_files))
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val filePath = getRealPathFromURI(uri, this@OpenWithActivity)

            var isEncrypted = true
            var isZip = false

            if (filePath != null) {
                val file = File(filePath)
                if (file.extension.equals("zip", ignoreCase = true)) {
                    isZip = true
                    try {
                        net.lingala.zip4j.ZipFile(file).use { zipFile ->
                            isEncrypted = zipFile.isEncrypted
                        }
                    } catch (e: Exception) {
                        // isEncrypted remains true
                    }
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (filePath != null) {
                    if (isZip && !isEncrypted) {
                        handleFileExtraction(filePath, null)
                        finish()
                    } else {
                        showPasswordInputDialog(filePath)
                    }
                } else {
                    Toast.makeText(this@OpenWithActivity, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun handleSingleSendIntent(uri: Uri) {
        val progressDialog = MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setTitle(getString(R.string.copying_files))
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val filePath = getRealPathFromURI(uri, this@OpenWithActivity)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (filePath != null) {
                    showArchiveTypeDialog(listOf(filePath))
                } else {
                    Toast.makeText(this@OpenWithActivity, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun handleMultipleSendIntent(uris: List<Uri>) {
        val progressDialog = MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setTitle(getString(R.string.copying_files))
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val filePaths = getRealPathsFromURIs(uris, this@OpenWithActivity)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (filePaths.isNotEmpty()) {
                    showArchiveTypeDialog(filePaths)
                } else {
                    Toast.makeText(this@OpenWithActivity, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }


    private fun showArchiveTypeDialog(filePaths: List<String>) {
        if (filePaths.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val binding = DialogArchiveTypeBinding.inflate(layoutInflater)
        val chipGroup = binding.chipGroupArchiveTypes

        val archiveTypes = arrayOf("ZIP", "7Z", "TAR")
        archiveTypes.forEach { type ->
            val chip = Chip(this).apply {
                text = type
                isCheckable = true
            }
            chipGroup.addView(chip)
            if (type == "ZIP") {
                chip.isChecked = true
            }
        }

        MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setTitle(getString(R.string.select_archive_type_title))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val selectedChipId = chipGroup.checkedChipId
                if (selectedChipId != View.NO_ID) {
                    val selectedChip = chipGroup.findViewById<Chip>(selectedChipId)
                    val selectedType = selectedChip.text.toString()
                    val jobId = fileOperationsDao.addFilesForJob(filePaths)
                    val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_CREATE_ARCHIVE
                        putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
                        putExtra(MainActivity.EXTRA_ARCHIVE_TYPE, selectedType)
                    }
                    startActivity(mainActivityIntent)
                    finish()
                } else {
                    Toast.makeText(this, getString(R.string.general_error_msg), Toast.LENGTH_SHORT).show()
                }
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun showPasswordInputDialog(filePath: String) {
        val dialogBinding = PasswordInputOpenWithBinding.inflate(layoutInflater)
        val passwordEditText = dialogBinding.passwordInput

        MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                handleFileExtraction(filePath, password)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                handleFileExtraction(filePath, null)
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }

    private fun handleFileExtraction(filePath: String, password: String?) {
        val fileExtension = filePath.split('.').takeLast(2).joinToString(".").lowercase()
        val supportedExtensions = listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz", "tar.zstd", "tar.zst")

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
            it.moveToFirst()
            val name = it.getString(nameIndex)
            val file = File(context.filesDir, name)
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val outputStream = FileOutputStream(file)
                    inputStream.use { input ->
                        outputStream.use { output ->
                            FileUtils.copyStream(input, output)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return file.path
        }
        return null
    }

    private fun getRealPathsFromURIs(uris: List<Uri>, context: Context): List<String> {
        val copiedPaths = mutableListOf<String>()

        for (uri in uris) {
            val returnCursor = context.contentResolver.query(uri, null, null, null, null)
            returnCursor?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                val name = cursor.getString(nameIndex)
                val file = File(context.filesDir, name)

                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val outputStream = FileOutputStream(file)
                        inputStream.use { input ->
                            outputStream.use { output ->
                                FileUtils.copyStream(input, output)
                            }
                        }
                        copiedPaths.add(file.path)
                    } else {
                        Log.e("OpenWithActivity", "Failed to open input stream for URI: $uri")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return copiedPaths
    }

    private fun startExtractionService(file: String, password: String?) {
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(this, ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startExtractionCsService(file: String) {
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(this, ExtractCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startRarExtractionService(file: String, password: String?) {
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(this, ExtractRarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}