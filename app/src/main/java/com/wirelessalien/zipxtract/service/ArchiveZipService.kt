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
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ZIP_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
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

class ArchiveZipService : Service() {

    companion object {
        const val NOTIFICATION_ID = 871
        const val EXTRA_ARCHIVE_NAME = "archiveName"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_COMPRESSION_METHOD = "compressionMethod"
        const val EXTRA_COMPRESSION_LEVEL = "compressionLevel"
        const val EXTRA_IS_ENCRYPTED = "isEncrypted"
        const val EXTRA_ENCRYPTION_METHOD = "encryptionMethod"
        const val EXTRA_AES_STRENGTH = "aesStrength"
        const val EXTRA_FILES_TO_ARCHIVE = "filesToArchive"
    }

    private var archiveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createCancelIntent(): PendingIntent {
        val cancelIntent = Intent(this, ArchiveZipService::class.java).apply {
            action = ACTION_ARCHIVE_ZIP_CANCEL
        }
        return PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archiveName = intent?.getStringExtra(EXTRA_ARCHIVE_NAME) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val compressionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_METHOD, CompressionMethod::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_METHOD) as? CompressionMethod
        } ?: CompressionMethod.STORE

        val compressionLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_LEVEL, CompressionLevel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_LEVEL) as? CompressionLevel
        } ?: CompressionLevel.NO_COMPRESSION

        val isEncrypted = intent.getBooleanExtra(ArchiveSplitZipService.EXTRA_IS_ENCRYPTED, false)

        val encryptionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_ENCRYPTION_METHOD, EncryptionMethod::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_ENCRYPTION_METHOD) as? EncryptionMethod
        }

        val aesStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_AES_STRENGTH, AesKeyStrength::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ArchiveSplitZipService.EXTRA_AES_STRENGTH) as? AesKeyStrength
        }

        val filesToArchive = intent.getStringArrayListExtra(ArchiveSplitZipService.EXTRA_FILES_TO_ARCHIVE) ?: return START_NOT_STICKY

        if (intent.action == ACTION_ARCHIVE_ZIP_CANCEL) {
            archiveJob?.cancel()
            stopForegroundService()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(0))

        archiveJob = CoroutineScope(Dispatchers.IO).launch {
            createZipFile(archiveName, password, compressionMethod, compressionLevel, isEncrypted, encryptionMethod, aesStrength, filesToArchive)
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
                BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID,
                getString(R.string.compress_archive_notification_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        val builder = NotificationCompat.Builder(this,
            BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(getString(R.string.archive_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .addAction(R.drawable.ic_round_cancel, getString(R.string.cancel), createCancelIntent())
            .setOngoing(true)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createZipFile(
        archiveName: String,
        password: String?,
        compressionMethod: CompressionMethod,
        compressionLevel: CompressionLevel,
        isEncrypted: Boolean,
        encryptionMethod: EncryptionMethod?,
        aesStrength: AesKeyStrength?,
        selectedFiles: List<String>
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
            var outputFile = File(baseDirectory, "$archiveName.zip")
            var counter = 1

            while (outputFile.exists()) {
                outputFile = File(baseDirectory, "$archiveName ($counter).zip")
                counter++
            }

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
                }

                zipFile.addFolder(renamedTempDir, zipParameters)

                while (!progressMonitor.state.equals(ProgressMonitor.State.READY)) {
                    val progress = progressMonitor.percentDone
                    updateProgress(progress)
                }

                if (progressMonitor.result == ProgressMonitor.Result.SUCCESS) {
                    showCompletionNotification()
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(BroadcastConstants.EXTRA_DIR_PATH, outputFile.absolutePath))
                } else {
                    showErrorNotification(progressMonitor.result.toString())
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, progressMonitor.result.toString()))
                }

                if (archiveJob?.isCancelled == true) throw ZipException(getString(R.string.operation_cancelled))

                renamedTempDir.deleteRecursively()

            } catch (e: ZipException) {
                e.printStackTrace()
                showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
                return
            }
        } catch (e: ZipException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(BroadcastConstants.ACTION_ARCHIVE_PROGRESS).putExtra(
            BroadcastConstants.EXTRA_PROGRESS, progress))
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID)
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

        val notification = NotificationCompat.Builder(this, BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.zip_creation_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
