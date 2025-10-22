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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.FileOperationsDao
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

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fileOperationsDao = FileOperationsDao(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        val useAppNameDir = intent?.getBooleanExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, false) ?: false

        if (jobId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val filesToExtract = fileOperationsDao.getFilesForJob(jobId)
        if (filesToExtract.isEmpty()) {
            fileOperationsDao.deleteFilesForJob(jobId)
            stopSelf()
            return START_NOT_STICKY
        }
        if (filesToExtract.size > 1) {
            Log.w("ExtractCsArchiveService", "This service only supports single file extraction. Only the first file will be extracted.")
        }
        val filePath = filesToExtract[0]

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(filePath, useAppNameDir)
            fileOperationsDao.deleteFilesForJob(jobId)
        }

        return START_NOT_STICKY
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

    private fun extractArchive(filePath: String, useAppNameDir: Boolean) {

        if (filePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        val file = File(filePath)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val extractPath = sharedPreferences.getString(BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH, null)

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
        } else if (useAppNameDir) {
            val rootDir = File(Environment.getExternalStorageDirectory().absolutePath)
            parentDir = File(rootDir, getString(R.string.app_name))
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
        } else {
            parentDir = file.parentFile ?: File(Environment.getExternalStorageDirectory().absolutePath)
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
                        sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, getString(R.string.unsupported_compression_format)))
                        return
                    }
                }
            }

            TarArchiveInputStream(compressorInputStream).use { tarInput ->
                var entry: TarArchiveEntry? = tarInput.nextEntry
                while (entry != null) {
                    val outputFile = File(destinationDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output ->
                            var n: Int
                            while (tarInput.read(buffer).also { n = it } != -1) {
                                output.write(buffer, 0, n)
                                bytesRead += n
                                val progress = (bytesRead * 100 / totalBytes).toInt()
                                updateProgress(progress)
                            }
                        }
                        outputFile.setLastModified(entry.lastModifiedDate.time)
                    }
                    entry = tarInput.nextEntry
                }
            }
            showCompletionNotification()
            scanForNewFiles(destinationDir)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))
        } catch (e: CompressorException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
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