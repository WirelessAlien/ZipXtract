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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_CANCEL_OPERATION
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.model.DirectoryInfo
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

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 21
    }

    private var extractionJob: Job? = null
    private var progressMonitor: ProgressMonitor? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_OPERATION) {
                progressMonitor?.isCancelAllTasks = true
                extractionJob?.cancel()
                stopForegroundService()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fileOperationsDao = FileOperationsDao(this)
        createNotificationChannel()
        ContextCompat.registerReceiver(this, cancelReceiver, IntentFilter(ACTION_CANCEL_OPERATION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(ServiceConstants.EXTRA_PASSWORD)
        val destinationPath = intent.getStringExtra(ServiceConstants.EXTRA_DESTINATION_PATH)

        if (jobId.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val filesToExtract = fileOperationsDao.getFileForJob(jobId)
        if (filesToExtract?.isEmpty() == true) {
            fileOperationsDao.deleteFilesForJob(jobId)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(filesToExtract ?:"", password, destinationPath)
            fileOperationsDao.deleteFilesForJob(jobId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        extractionJob?.cancel()
        unregisterReceiver(cancelReceiver)
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
        val cancelIntent = Intent(ACTION_CANCEL_OPERATION)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private suspend fun extractArchive(filePath: String, password: String?, destinationPath: String?) {

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
            if (!destinationPath.isNullOrBlank()) {
                parentDir = File(destinationPath)
            } else if (!extractPath.isNullOrEmpty()) {
                parentDir = if (File(extractPath).isAbsolute) {
                    File(extractPath)
                } else {
                    File(Environment.getExternalStorageDirectory(), extractPath)
                }
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
            } else {
                val isInternalDir = file.absolutePath.startsWith(filesDir.absolutePath)
                if (isInternalDir) {
                    parentDir = Environment.getExternalStorageDirectory()
                } else {
                    parentDir = file.parentFile ?: File(Environment.getExternalStorageDirectory().absolutePath)
                }
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
            progressMonitor = zipFile.progressMonitor
            val directories = mutableListOf<DirectoryInfo>()

            fileHeaders.forEach { header ->
                if (header.isDirectory) {
                    val directoryPath = File(extractDir, header.fileName).path
                    directories.add(DirectoryInfo(directoryPath, header.lastModifiedTimeEpoch))
                }
            }

            zipFile.extractAll(extractDir.absolutePath)

            while (progressMonitor!!.state != ProgressMonitor.State.READY) {
                if (progressMonitor!!.state == ProgressMonitor.State.BUSY) {
                    val percentDone = progressMonitor!!.percentDone
                    updateProgress(percentDone)
                }
                delay(100)
            }

            if (progressMonitor!!.result == ProgressMonitor.Result.CANCELLED) {
                // Do nothing
            } else if (progressMonitor!!.result == ProgressMonitor.Result.SUCCESS) {
                FileUtils.setLastModifiedTime(directories)
                scanForNewFiles(extractDir)
                showCompletionNotification(extractDir.absolutePath)
                sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, extractDir.absolutePath))
            } else {
                val exception = progressMonitor!!.exception
                val errorMessage = if (exception is ZipException && exception.type == ZipException.Type.WRONG_PASSWORD) {
                    getString(R.string.wrong_password)
                } else {
                    exception?.message ?: getString(R.string.general_error_msg)
                }
                showErrorNotification(errorMessage)
                sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            }
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

    private fun showCompletionNotification(destinationPath: String) {
        stopForegroundService()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIRECTORY
            putExtra(MainActivity.EXTRA_DIRECTORY_PATH, destinationPath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)


        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
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