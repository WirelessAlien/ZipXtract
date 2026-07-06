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
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants.COPY_MOVE_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File

class CopyMoveService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 2
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fileOperationsDao = FileOperationsDao(this)
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        val destinationPath = intent?.getStringExtra(ServiceConstants.EXTRA_DESTINATION_PATH)
        val isCopyAction = intent?.getBooleanExtra(ServiceConstants.EXTRA_IS_COPY_ACTION, true)

        if (jobId == null || destinationPath == null || isCopyAction == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(0, 0, isCopyAction))

        serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZipXtract:CopyMoveWakeLock")
            wakeLock.acquire(60 * 60 * 1000L /*1 hour*/)
            try {
                val filesToCopyMove = fileOperationsDao.getFilesForJob(jobId).map { File(it) }
                copyMoveFiles(filesToCopyMove, destinationPath, isCopyAction)
                fileOperationsDao.deleteFilesForJob(jobId)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun copyMoveFiles(files: List<File>, destinationPath: String, isCopyAction: Boolean) {
        var totalFilesCount = 0
        for (file in files) {
            totalFilesCount += FileUtils.countTotalFiles(file)
        }

        var processedFilesCount = 0
        val pathsToScan = mutableSetOf<String>()

        val progressCallback: (Int) -> Unit = { count ->
            processedFilesCount += count
            updateNotification(processedFilesCount, totalFilesCount, isCopyAction)
        }

        for (file in files) {
            val destinationFile = File(destinationPath, file.name)
            if (file.absolutePath == destinationFile.absolutePath) {
                continue // Skip if the source and destination paths are the same
            }

            if (isCopyAction) {
                FileUtils.copyRecursively(
                    source = file,
                    destination = destinationFile,
                    overwrite = true,
                    progressCallback = progressCallback,
                    scanCallback = { dest ->
                        pathsToScan.add(dest.absolutePath)
                    }
                )
            } else {
                FileUtils.smartMove(
                    source = file,
                    destination = destinationFile,
                    overwrite = true,
                    progressCallback = progressCallback,
                    scanCallback = { src, dest ->
                        pathsToScan.add(src.absolutePath)
                        pathsToScan.add(dest.absolutePath)
                    }
                )
            }
        }

        FileUtils.scanFiles(this, pathsToScan)

        stopForegroundService()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COPY_MOVE_NOTIFICATION_CHANNEL_ID,
                getString(R.string.copy_move_files_notification_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int, total: Int, isCopyAction: Boolean): Notification {
        val title = if (isCopyAction) {
            getString(R.string.copying_files)
        } else {
            getString(R.string.moving_files)
        }
        val contentText = if (isCopyAction) {
            getString(R.string.copying_files_progress, progress, total)
        } else {
            getString(R.string.moving_files_progress, progress, total)
        }

        return NotificationCompat.Builder(this, COPY_MOVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(total, progress, false)
            .setOngoing(true)
            .build()
    }

    private var lastNotifyTime = 0L

    private fun updateNotification(progress: Int, total: Int, isCopyAction: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotifyTime >= 500 || progress == total || progress == 0) {
            lastNotifyTime = currentTime
            val notification = createNotification(progress, total, isCopyAction)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}