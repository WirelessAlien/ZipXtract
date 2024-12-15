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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

class ArchiveZipService : Service() {

    companion object {
        const val NOTIFICATION_ID = 871
        const val CHANNEL_ID = "zip_service_channel"
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
            action = BroadcastConstants.ACTION_ARCHIVE_ZIP_CANCEL
        }
        return PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archiveName = intent?.getStringExtra(EXTRA_ARCHIVE_NAME) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val compressionMethod = intent.getSerializableExtra(EXTRA_COMPRESSION_METHOD) as CompressionMethod
        val compressionLevel = intent.getSerializableExtra(EXTRA_COMPRESSION_LEVEL) as CompressionLevel
        val isEncrypted = intent.getBooleanExtra(EXTRA_IS_ENCRYPTED, false)
        val encryptionMethod = intent.getSerializableExtra(EXTRA_ENCRYPTION_METHOD) as EncryptionMethod?
        val aesStrength = intent.getSerializableExtra(EXTRA_AES_STRENGTH) as AesKeyStrength?
        val filesToArchive = intent.getSerializableExtra(EXTRA_FILES_TO_ARCHIVE) as List<String>

        if (intent.action == BroadcastConstants.ACTION_ARCHIVE_ZIP_CANCEL) {
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
                CHANNEL_ID,
                "Zip Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Creating Zip Archive")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .addAction(R.drawable.ic_round_cancel, "Cancel", createCancelIntent())
            .setOngoing(true)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private suspend fun createZipFile(
        archiveName: String,
        password: String?,
        compressionMethod: CompressionMethod,
        compressionLevel: CompressionLevel,
        isEncrypted: Boolean,
        encryptionMethod: EncryptionMethod?,
        aesStrength: AesKeyStrength?,
        selectedFiles: List<String>
    ) {
        try {
            val zipParameters = ZipParameters().apply {
                this.compressionMethod = compressionMethod
                this.compressionLevel = compressionLevel
                this.encryptionMethod = if (isEncrypted) encryptionMethod else null
                this.isEncryptFiles = isEncrypted
                this.aesKeyStrength = if (isEncrypted) aesStrength else null
            }

            val baseDirectory = File(selectedFiles.first()).parentFile
            val outputFile = File(baseDirectory, "$archiveName.zip")
            val zipFile = ZipFile(outputFile)
            if (isEncrypted) {
                zipFile.setPassword(password?.toCharArray())
            }

            zipFile.isRunInThread = true
            val progressMonitor = zipFile.progressMonitor

            try {
                selectedFiles.forEach { filePath ->
                    while (progressMonitor.state != ProgressMonitor.State.READY) {
                        delay(100)
                    }
                    val file = File(filePath)
                    val relativePath = file.relativeTo(baseDirectory).path
                    when {
                        file.isDirectory -> zipFile.addFolder(
                            file,
                            zipParameters.apply { rootFolderNameInZip = relativePath })

                        else -> zipFile.addFile(
                            file,
                            zipParameters.apply { fileNameInZip = relativePath })
                    }

                    if (archiveJob?.isCancelled == true) throw ZipException("Extraction cancelled")
                }
            } catch (e: ZipException) {
                e.printStackTrace()
                showErrorNotification("Archive creation failed: ${e.message}")
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra("error_message", "Archive creation failed: ${e.message}"))
                return
            }

            while (!progressMonitor.state.equals(ProgressMonitor.State.READY)) {
                val progress = progressMonitor.percentDone
                updateProgress(progress)
            }

            if (progressMonitor.result == ProgressMonitor.Result.SUCCESS) {
                showCompletionNotification("$archiveName.zip created successfully")
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE))
            } else {
                showErrorNotification("Archive creation failed")
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra("error_message", "Archive creation failed: ${progressMonitor.result}"))
            }

        } catch (e: ZipException) {
            e.printStackTrace()
            showErrorNotification("Archive creation failed: ${e.message}")
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra("error_message", "Archive creation failed: ${e.message}"))
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Archive Creation Failed")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showCompletionNotification(error: String) {
        stopForegroundService()

        val notification = NotificationCompat.Builder(this, ExtractArchiveService.CHANNEL_ID)
            .setContentTitle("Extraction Complete")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(ExtractArchiveService.NOTIFICATION_ID + 1, notification)
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
