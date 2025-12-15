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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.github.luben.zstd.ZstdOutputStream
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_CANCEL_OPERATION
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
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

class CompressCsArchiveService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 17
        const val ZSTD_FORMAT = "zstd"
    }

    private var compressionJob: Job? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_OPERATION) {
                compressionJob?.cancel()
                stopForegroundService()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fileOperationsDao = FileOperationsDao(this)
        createNotificationChannel()
        LocalBroadcastManager.getInstance(this).registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_OPERATION))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        val compressionFormat = intent?.getStringExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT)


        if (jobId == null || compressionFormat == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val filesToCompress = fileOperationsDao.getFilesForJob(jobId)
        if (filesToCompress.isEmpty()) {
            fileOperationsDao.deleteFilesForJob(jobId)
            stopSelf()
            return START_NOT_STICKY
        }
        if (filesToCompress.size > 1) {
            Log.w("CompressCsArchiveService", "This service only supports single file compression. Only the first file will be compressed.")
        }
        val filePath = filesToCompress[0]


        startForeground(NOTIFICATION_ID, createNotification(0))

        compressionJob = CoroutineScope(Dispatchers.IO).launch {
            if (compressionFormat == ZSTD_FORMAT) {
                compressWithZstd(filePath, compressionFormat)
            } else {
                compressArchive(filePath, compressionFormat)
            }
            fileOperationsDao.deleteFilesForJob(jobId)
        }


        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        compressionJob?.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cancelReceiver)
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
            .setContentTitle(getString(R.string.compression_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun compressArchive(filePath: String, format: String) {

        if (filePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        val file = File(filePath)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
            val isInternalDir = file.absolutePath.startsWith(filesDir.absolutePath)
            if (isInternalDir) {
                parentDir = Environment.getExternalStorageDirectory()
            } else {
                parentDir = file.parentFile ?: Environment.getExternalStorageDirectory()
            }
        }

        var outputFile = if (format == CompressorStreamFactory.BZIP2) {
            File(parentDir, "${File(filePath).name}.bz2")
        } else {
            File(parentDir, "${File(filePath).name}.$format")
        }
        var counter = 1

        while (outputFile.exists()) {
            outputFile = if (format == CompressorStreamFactory.BZIP2) {
                File(parentDir, "($counter)${File(filePath).name}.bz2")
            } else {
                File(parentDir, "($counter)${File(filePath).name}.$format")
            }
            counter++
        }

        val fin: InputStream
        val outStream: OutputStream

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fin = Files.newInputStream(file.toPath())
                outStream = Files.newOutputStream(outputFile.toPath())
            } else {
                fin = FileInputStream(file)
                outStream = FileOutputStream(outputFile)
            }

            val inStream = BufferedInputStream(fin)
            val bufferedOutStream = BufferedOutputStream(outStream)
            val compressorOutputStream = CompressorStreamFactory().createCompressorOutputStream(format, bufferedOutStream)

            val totalBytes = file.length()
            var bytesRead = 0L

            var n: Int
            while (inStream.read(buffer).also { n = it } != -1) {
                if (compressionJob?.isActive == false) {
                    compressorOutputStream.close()
                    inStream.close()
                    return
                }
                compressorOutputStream.write(buffer, 0, n)
                bytesRead += n
                val progress = (bytesRead * 100 / totalBytes).toInt()
                updateProgress(progress)
            }

            compressorOutputStream.close()
            inStream.close()

            showCompletionNotification(outputFile.parent ?: "")
            scanForNewFile(outputFile)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(EXTRA_DIR_PATH, outputFile.parent))

        } catch (e: CompressorException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        } finally {
            stopForegroundService()
        }
    }

    private fun compressWithZstd(inputFilePath: String, format: String) {

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val compressionLevel = sharedPreferences.getString("zstd_compression_level", "3")?.toIntOrNull() ?: 3
        val safeLevel = compressionLevel.coerceIn(0, 22)

        if (inputFilePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        val file = File(inputFilePath)
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
            val isInternalDir = file.absolutePath.startsWith(filesDir.absolutePath)
            if (isInternalDir) {
                parentDir = Environment.getExternalStorageDirectory()
            } else {
                parentDir = file.parentFile ?: Environment.getExternalStorageDirectory()
            }
        }

        var outputFile = File(parentDir, "${file.name}.$format")
        var counter = 1

        while (outputFile.exists()) {
            outputFile = File(parentDir, "($counter)${file.name}.$format")
            counter++
        }

        try {
            val compressor = ZstdOutputStream(FileOutputStream(outputFile)).apply {
                setLevel(safeLevel)
            }

            FileInputStream(file).use { inputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                val totalBytes = file.length()
                var bytesProcessed = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (compressionJob?.isActive == false) {
                        compressor.close()
                        return
                    }
                    compressor.write(buffer, 0, bytesRead)
                    bytesProcessed += bytesRead

                    // Update progress (0-100%)
                    val progress = (bytesProcessed * 100 / totalBytes).toInt()
                    updateProgress(progress)
                }
            }

            compressor.close()

            showCompletionNotification(outputFile.parent ?: "")
            scanForNewFile(outputFile)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(EXTRA_DIR_PATH, outputFile.parent))

        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        } finally {
            stopForegroundService()
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_ARCHIVE_PROGRESS).putExtra(EXTRA_PROGRESS, progress))
    }

    private fun showCompletionNotification(destinationPath: String) {
        stopForegroundService()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIRECTORY
            putExtra(MainActivity.EXTRA_DIRECTORY_PATH, destinationPath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.compression_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.compression_failed))
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

    private fun scanForNewFile(file: File) {
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
    }
}