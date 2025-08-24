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
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.progress.ProgressMonitor
import java.io.File

class ExtractMultipartZipService : Service() {

    companion object {
        const val NOTIFICATION_ID = 21
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_PASSWORD = "password"
    }

    private var extractionJob: Job? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        if (filePath.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(filePath, password)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        extractionJob?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EXTRACTION_NOTIFICATION_CHANNEL_ID,
                getString(R.string.extract_archive_notification_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private suspend fun extractArchive(filePath: String, password: String?) {

        if (filePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        val file = File(filePath)

        try {

            val firstPartPath = when {
                file.name.matches(Regex(".*\\.zip\\.\\d{3}")) -> {
                    file.absolutePath.replace(Regex("\\.zip\\.\\d{3}$"), ".zip.001")
                }
                file.name.matches(Regex(".*\\.z\\d{2}")) -> {
                    file.absolutePath.replace(Regex("\\.z\\d{2}$"), ".z01")
                }
                else -> file.absolutePath
            }

            val zipFile = ZipFile(File(firstPartPath))
            zipFile.isRunInThread = true

            if (!password.isNullOrEmpty()) {
                zipFile.setPassword(password.toCharArray())
            }

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val extractPath = sharedPreferences.getString(PREFERENCE_EXTRACT_DIR_PATH, null)

            val parentDir: File
            if (!extractPath.isNullOrEmpty()) {
                parentDir = if (File(extractPath).isAbsolute) {
                    File(extractPath)
                } else {
                    File(Environment.getExternalStorageDirectory(), extractPath)
                }
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
            } else {
                parentDir = file.parentFile ?: File(Environment.getExternalStorageDirectory().absolutePath)
            }

            var directoryName = file.nameWithoutExtension
            var extractDir = File(parentDir, directoryName)
            var counter = 1

            while (extractDir.exists()) {
                directoryName = "${file.nameWithoutExtension} ($counter)"
                extractDir = File(parentDir, directoryName)
                counter++
            }

            extractDir.mkdirs()

            val fileHeaders = zipFile.fileHeaders
            val totalSize = fileHeaders.sumOf { it.uncompressedSize }
            var extractedSize = 0L
            val progressMonitor = zipFile.progressMonitor

            fileHeaders.forEach { header ->
                while (progressMonitor.state != ProgressMonitor.State.READY) {
                    delay(100)
                }

                zipFile.extractFile(header, extractDir.absolutePath)

                extractedSize += header.uncompressedSize
                val progress = ((extractedSize.toDouble() / totalSize) * 100).toInt()
                updateProgress(progress)
            }

            showCompletionNotification()
            scanForNewFiles(extractDir)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, extractDir.absolutePath))

        } catch (e: ZipException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_EXTRACTION_PROGRESS).putExtra(EXTRA_PROGRESS, progress))
    }

    private fun showCompletionNotification() {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_failed))
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
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