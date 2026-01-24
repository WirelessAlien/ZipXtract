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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_CANCEL_OPERATION
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.AppEvent
import com.wirelessalien.zipxtract.helper.EventBus
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.model.DirectoryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class ExtractCsArchiveService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 19
    }

    private var extractionJob: Job? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_OPERATION) {
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
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        val useAppNameDir = intent?.getBooleanExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, false) ?: false
        val destinationPath = intent?.getStringExtra(ServiceConstants.EXTRA_DESTINATION_PATH)

        if (jobId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            val filesToExtract = fileOperationsDao.getFilesForJob(jobId)
            if (filesToExtract.isEmpty()) {
                fileOperationsDao.deleteFilesForJob(jobId)
                stopSelf()
                return@launch
            }
            if (filesToExtract.size > 1) {
                Log.w("ExtractCsArchiveService", "This service only supports single file extraction. Only the first file will be extracted.")
            }
            val filePath = filesToExtract[0]

            extractArchive(filePath, useAppNameDir, destinationPath)
            fileOperationsDao.deleteFilesForJob(jobId)
            stopSelf()
        }

        return START_NOT_STICKY
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

    private fun extractArchive(filePath: String, useAppNameDir: Boolean, destinationPath: String?) {

        if (filePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            CoroutineScope(Dispatchers.IO).launch { EventBus.emit(AppEvent.ExtractionError(errorMessage)) }
            stopForegroundService()
            return
        }

        val file = File(filePath)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val extractPath = sharedPreferences.getString(BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH, null)

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
        } else if (useAppNameDir) {
            val rootDir = File(Environment.getExternalStorageDirectory().absolutePath)
            parentDir = File(rootDir, getString(R.string.app_name))
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

        val baseFileName = file.name.substring(0, file.name.lastIndexOf('.'))
        var newFileName = baseFileName
        var destinationDir = File(parentDir, newFileName)
        var counter = 1

        while (destinationDir.exists()) {
            newFileName = "$baseFileName ($counter)"
            destinationDir = File(parentDir, newFileName)
            counter++
        }

        destinationDir.mkdirs()

        try {
            val totalBytes = file.length()
            var bytesRead = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val directories = mutableListOf<DirectoryInfo>()

            val fi: InputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.newInputStream(file.toPath())
            } else {
                FileInputStream(file)
            }
            val bi = BufferedInputStream(fi)
            val compressorInputStream = when (file.extension.lowercase()) {
                "lzma" -> LZMACompressorInputStream(bi)
                else -> {
                    try {
                        CompressorStreamFactory().createCompressorInputStream(bi)
                    } catch (e: CompressorException) {
                        showErrorNotification(getString(R.string.unsupported_compression_format))
                        CoroutineScope(Dispatchers.IO).launch { EventBus.emit(AppEvent.ExtractionError(getString(R.string.unsupported_compression_format))) }
                        return
                    }
                }
            }

            TarArchiveInputStream(compressorInputStream).use { tarInput ->
                var entry: TarArchiveEntry? = tarInput.nextEntry
                var lastProgress = -1
                while (entry != null) {
                    if (extractionJob?.isActive == false) {
                        return
                    }
                    val outputFile = File(destinationDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                        val lastModified = if (entry.lastModifiedDate.time > 0) entry.lastModifiedDate.time else System.currentTimeMillis()
                        directories.add(DirectoryInfo(outputFile.path, lastModified))
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output ->
                            var n: Int
                            while (tarInput.read(buffer).also { n = it } != -1) {
                                if (extractionJob?.isActive == false) {
                                    return
                                }
                                output.write(buffer, 0, n)
                                bytesRead += n
                                val progress = (bytesRead * 100 / totalBytes).toInt()
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    updateProgress(progress)
                                }
                            }
                        }
                        if (entry.lastModifiedDate.time > 0) {
                            outputFile.setLastModified(entry.lastModifiedDate.time)
                        }
                    }
                    entry = tarInput.nextEntry
                }
            }
            FileUtils.setLastModifiedTime(directories)
            scanForNewFiles(destinationDir)
            showCompletionNotification(destinationDir)
            CoroutineScope(Dispatchers.IO).launch { EventBus.emit(AppEvent.ExtractionComplete(destinationDir.absolutePath)) }
        } catch (e: CompressorException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            CoroutineScope(Dispatchers.IO).launch { EventBus.emit(AppEvent.ExtractionError(e.message ?: getString(R.string.general_error_msg))) }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            CoroutineScope(Dispatchers.IO).launch { EventBus.emit(AppEvent.ExtractionError(e.message ?: getString(R.string.general_error_msg))) }
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            CoroutineScope(Dispatchers.IO).launch { EventBus.emit(AppEvent.ExtractionError(e.message ?: getString(R.string.general_error_msg))) }
        } finally {
            stopForegroundService()
            if (useAppNameDir) {
                filesDir.deleteRecursively()
            }
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        CoroutineScope(Dispatchers.IO).launch {
            EventBus.emit(AppEvent.ExtractionProgress(progress))
        }
    }

    private fun showCompletionNotification(destination: File) {
        stopForegroundService()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIRECTORY
            putExtra(MainActivity.EXTRA_DIRECTORY_PATH, destination.absolutePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)


        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_completed))
            .setContentText(destination.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${destination.name} - ${destination.path}"))
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
            .setContentTitle(getString(R.string.error))
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
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