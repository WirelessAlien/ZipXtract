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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants.DELETE_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DeleteFilesService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 23
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fileOperationsDao = FileOperationsDao(this)
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        if (jobId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val filesToDelete = fileOperationsDao.getFilesForJob(jobId).map { File(it) }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(0, filesToDelete.size))

        CoroutineScope(Dispatchers.IO).launch {
            deleteFiles(filesToDelete)
            fileOperationsDao.deleteFilesForJob(jobId)
        }

        return START_STICKY
    }

    private fun deleteFiles(files: List<File>) {
        val totalFilesCount = countTotalFiles(files)
        var deletedFilesCount = 0
        val parentDirs = mutableSetOf<String>()

        fun deleteFile(file: File) {
            file.parentFile?.absolutePath?.let { parentDirs.add(it) }
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteFile(it) }
            }
            file.deleteRecursively()
            deletedFilesCount++
            updateNotification(deletedFilesCount, totalFilesCount)
        }

        for (file in files) {
            deleteFile(file)
        }

        if (parentDirs.isNotEmpty()) {
            MediaScannerConnection.scanFile(this, parentDirs.toTypedArray(), null, null)
        }

        stopForegroundService()
        stopSelf()
    }

    private fun countTotalFiles(files: List<File>): Int {
        var count = 0
        for (file in files) {
            if (file.isDirectory) {
                count += countTotalFiles(file.listFiles()?.toList() ?: emptyList())
            } else {
                count++
            }
        }
        return count
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DELETE_NOTIFICATION_CHANNEL_ID,
                getString(R.string.delete_files_notification_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, DELETE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.delete_ongoing))
            .setContentText(getString(R.string.deleting_files, progress, total))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(total, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int, total: Int) {
        val notification = createNotification(progress, total)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(Archive7zService.NOTIFICATION_ID)
    }
}