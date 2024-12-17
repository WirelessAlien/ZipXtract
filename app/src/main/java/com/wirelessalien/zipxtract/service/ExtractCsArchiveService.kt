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
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACT_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

class ExtractCsArchiveService : Service() {

    companion object {
        const val NOTIFICATION_ID = 596
        const val EXTRA_FILE_PATH = "file_path"
    }

    private var extractionJob: Job? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH)

        if (filePath == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_EXTRACT_CANCEL) {
            extractionJob?.cancel()
            stopForegroundService()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(File(filePath))
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        extractionJob?.cancel()
    }

    private fun createCancelIntent(): PendingIntent {
        val cancelIntent = Intent(this, ExtractCsArchiveService::class.java).apply {
            action = ACTION_EXTRACT_CANCEL
        }
        return PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EXTRACTION_NOTIFICATION_CHANNEL_ID,
                "Extraction Service",
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
            .addAction(R.drawable.ic_round_cancel, getString(R.string.cancel), createCancelIntent())

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun extractArchive(file: File) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val outputFile = File(file.parent, file.nameWithoutExtension)
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

            val compressorInputStream = when (file.extension.lowercase()) {
                "lzma" -> LZMACompressorInputStream(inStream)
                else -> {
                    try {
                        CompressorStreamFactory().createCompressorInputStream(inStream)
                    } catch (e: CompressorException) {
                        showErrorNotification(getString(R.string.unsupported_compression_format))
                        sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, getString(R.string.unsupported_compression_format)))
                        return
                    }
                }
            }

            val totalBytes = file.length()
            var bytesRead = 0L

            var n: Int
            while (compressorInputStream.read(buffer).also { n = it } != -1) {
                if (extractionJob?.isCancelled == true) throw Exception("Extraction cancelled")
                outStream.write(buffer, 0, n)
                bytesRead += n
                val progress = (bytesRead * 100 / totalBytes).toInt()
                updateProgress(progress)
            }

            outStream.close()
            compressorInputStream.close()

            showCompletionNotification()
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE))

        } catch (e: CompressorException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.extraction_failed))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.extraction_failed)))
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.extraction_failed))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.extraction_failed)))
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.extraction_failed))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.extraction_failed)))
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(ExtractArchiveService.NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(BroadcastConstants.ACTION_EXTRACTION_PROGRESS).putExtra(
            BroadcastConstants.EXTRA_PROGRESS, progress))
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