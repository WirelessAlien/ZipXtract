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

package com.wirelessalien.zipxtract.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_ARCHIVE_DIR_PATH
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ArchiveSplitZipService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 14
    }

    private var archiveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fileOperationsDao = FileOperationsDao(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archiveName = intent?.getStringExtra(ServiceConstants.EXTRA_ARCHIVE_NAME) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(ServiceConstants.EXTRA_PASSWORD)
        val compressionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD, CompressionMethod::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD) as? CompressionMethod
        } ?: CompressionMethod.STORE

        val compressionLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, CompressionLevel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL) as? CompressionLevel
        } ?: CompressionLevel.NO_COMPRESSION

        val isEncrypted = intent.getBooleanExtra(ServiceConstants.EXTRA_IS_ENCRYPTED, false)

        val encryptionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD, EncryptionMethod::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD) as? EncryptionMethod
        }

        val aesStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_AES_STRENGTH, AesKeyStrength::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_AES_STRENGTH) as? AesKeyStrength
        }
        val jobId = intent.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        if (jobId == null) {
            sendErrorBroadcast(getString(R.string.general_error_msg))
            return START_NOT_STICKY
        }
        val filesToArchive = fileOperationsDao.getFilesForJob(jobId)

        val splitSize = intent.getLongExtra(ServiceConstants.EXTRA_SPLIT_SIZE, 64)

        startForeground(NOTIFICATION_ID, createNotification(0))

        archiveJob = CoroutineScope(Dispatchers.IO).launch {
            createSplitZipFile(archiveName, password, compressionMethod, compressionLevel, isEncrypted, encryptionMethod, aesStrength, filesToArchive, splitSize)
            fileOperationsDao.deleteFilesForJob(jobId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        archiveJob?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ARCHIVE_NOTIFICATION_CHANNEL_ID,
                getString(R.string.compress_archive_notification_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.archive_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(errorMessage: String) {
        sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
    }

    private fun createSplitZipFile(
        archiveName: String,
        password: String?,
        compressionMethod: CompressionMethod,
        compressionLevel: CompressionLevel,
        isEncrypted: Boolean,
        encryptionMethod: EncryptionMethod?,
        aesStrength: AesKeyStrength?,
        selectedFiles: List<String>,
        splitSize: Long
    ) {

        if (selectedFiles.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        try {
            val zipParameters = ZipParameters().apply {
                this.compressionMethod = compressionMethod
                this.compressionLevel = compressionLevel
                this.encryptionMethod = if (isEncrypted) encryptionMethod else null
                this.isEncryptFiles = isEncrypted
                this.aesKeyStrength = if (isEncrypted) aesStrength else null
            }

            val baseDirectory = File(selectedFiles.first()).parentFile
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val archivePath = sharedPreferences.getString(PREFERENCE_ARCHIVE_DIR_PATH, null)
            val parentDir: File

            if (!archivePath.isNullOrEmpty()) {
                parentDir = if (File(archivePath).isAbsolute) {
                    File(archivePath)
                } else {
                    File(Environment.getExternalStorageDirectory(), archivePath)
                }
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
            } else {
                parentDir = File(selectedFiles.first()).parentFile ?: Environment.getExternalStorageDirectory()
            }
            val outputFile = File(parentDir, "$archiveName.zip")

            val zipFile = ZipFile(outputFile)
            if (isEncrypted) {
                zipFile.setPassword(password?.toCharArray())
            }

            zipFile.isRunInThread = true
            val progressMonitor = zipFile.progressMonitor

            try {
                val tempDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.createTempDirectory("tempArchive").toFile()
                } else {
                    val tempDir = File(cacheDir, "tempArchive")
                    tempDir.mkdirs()
                    tempDir
                }
                val renamedTempDir = File(tempDir.parent, archiveName)
                tempDir.renameTo(renamedTempDir)

                selectedFiles.forEach { filePath ->
                    val file = File(filePath)
                    val destFile = File(renamedTempDir, file.relativeTo(baseDirectory!!).path)
                    if (file.isDirectory) {
                        destFile.mkdirs()
                        file.copyRecursively(destFile, overwrite = true)
                    } else {
                        destFile.parentFile?.mkdirs()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } else {
                            file.inputStream().use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }

                    destFile.setLastModified(file.lastModified())
                }

                zipFile.createSplitZipFileFromFolder(renamedTempDir, zipParameters, true, splitSize)

                while (!progressMonitor.state.equals(ProgressMonitor.State.READY)) {
                    val progress = progressMonitor.percentDone
                    updateProgress(progress)
                }

                if (progressMonitor.result == ProgressMonitor.Result.SUCCESS) {
                    showCompletionNotification()
                    scanForNewFiles(outputFile.parentFile ?: Environment.getExternalStorageDirectory())
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(EXTRA_DIR_PATH, outputFile.parent))
                } else {
                    showErrorNotification(getString(R.string.zip_creation_failed))
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, progressMonitor.result))
                }

                renamedTempDir.deleteRecursively()

            } catch (e: ZipException) {
                e.printStackTrace()
                showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_ARCHIVE_PROGRESS).putExtra(
            EXTRA_PROGRESS, progress))
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.zip_creation_failed))
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showCompletionNotification() {
        stopForegroundService()

        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.zip_creation_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun scanForNewFiles(directory: File) {
        val files = directory.listFiles()
        if (files != null) {
            val paths = files.map { it.absolutePath }.toTypedArray()
            MediaScannerConnection.scanFile(this, paths, null, null)
        }
    }
}