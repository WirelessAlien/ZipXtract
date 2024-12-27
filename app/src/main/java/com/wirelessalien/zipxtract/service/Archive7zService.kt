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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutFeatureSetEncryptHeader
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class Archive7zService : Service() {

    companion object {
        const val NOTIFICATION_ID = 13
        const val EXTRA_ARCHIVE_NAME = "archiveName"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_COMPRESSION_LEVEL = "compressionLevel"
        const val EXTRA_SOLID = "solid"
        const val EXTRA_THREAD_COUNT = "threadCount"
        const val EXTRA_FILES_TO_ARCHIVE = "filesToArchive"
    }

    private var archiveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archiveName = intent?.getStringExtra(EXTRA_ARCHIVE_NAME) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val compressionLevel = intent.getIntExtra(EXTRA_COMPRESSION_LEVEL, 1)
        val solid = intent.getBooleanExtra(EXTRA_SOLID, false)
        val threadCount = intent.getIntExtra(EXTRA_THREAD_COUNT, 1)
        val filesToArchive = intent.getStringArrayListExtra(EXTRA_FILES_TO_ARCHIVE) ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, createNotification(0))

        archiveJob = CoroutineScope(Dispatchers.IO).launch {
            create7zFile(archiveName, password, compressionLevel, solid, threadCount, filesToArchive)
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

    private fun create7zFile(archiveName: String, password: String?, compressionLevel: Int, solid: Boolean, threadCount: Int, filesToArchive: List<String>) {

        if (filesToArchive.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        try {
            val baseDirectory = File(filesToArchive.first()).parentFile?.absolutePath ?: ""
            var sevenZFile = File(baseDirectory, "$archiveName.7z")
            var counter = 1

            while (sevenZFile.exists()) {
                sevenZFile = File(baseDirectory, "$archiveName ($counter).7z")
                counter++
            }

            RandomAccessFile(sevenZFile, "rw").use { raf ->
                val outArchive = SevenZip.openOutArchive7z()

                outArchive.setLevel(compressionLevel)
                outArchive.setSolid(solid)
                outArchive.setThreadCount(threadCount)
                outArchive.setHeaderEncryption(true)

                outArchive.createArchive(
                    RandomAccessFileOutStream(raf), filesToArchive.size,
                    object : IOutCreateCallback<IOutItem7z>, ICryptoGetTextPassword, IOutFeatureSetEncryptHeader {
                        override fun cryptoGetTextPassword(): String? {
                            return password
                        }

                        override fun setOperationResult(operationResultOk: Boolean) {

                        }

                        override fun setTotal(total: Long) {

                        }

                        override fun setCompleted(complete: Long) {
                            val totalSize = filesToArchive.sumOf { File(it).length() }
                            val progress = ((complete.toDouble() / totalSize) * 100).toInt()
                            startForeground(NOTIFICATION_ID, createNotification(progress))
                            updateProgress(progress)
                        }

                        override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItem7z>): IOutItem7z {
                            val item = outItemFactory.createOutItem()
                            val file = File(filesToArchive[index])
                            val relativePath = file.absolutePath.removePrefix(baseDirectory).removePrefix("/")

                            item.dataSize = file.length()
                            item.propertyPath = relativePath
                            item.propertyIsDir = file.isDirectory

                            return item
                        }

                        override fun getStream(i: Int): ISequentialInStream {

                            return RandomAccessFileInStream(RandomAccessFile(filesToArchive[i], "r"))
                        }

                        override fun setHeaderEncryption(enabled: Boolean) {
                            outArchive.setHeaderEncryption(enabled)
                        }
                    })

                outArchive.close()
                stopForegroundService()
                showCompletionNotification()
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(EXTRA_DIR_PATH, sevenZFile.parent))
            }
        } catch (e: SevenZipException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_ARCHIVE_PROGRESS).putExtra(
            EXTRA_PROGRESS, progress))
    }

    private fun showCompletionNotification() {
        stopForegroundService()

        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.sevenz_creation_success))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.sevenz_creation_failed))
            .setContentText(error)
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
}