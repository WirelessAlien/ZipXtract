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
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ArchiveSplitZipService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 14
    }

    private var archiveJob: Job? = null
    private var progressMonitor: ProgressMonitor? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_OPERATION) {
                progressMonitor?.isCancelAllTasks = true
                archiveJob?.cancel()
                stopForegroundService()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fileOperationsDao = FileOperationsDao(this)
        createNotificationChannel()
        ContextCompat.registerReceiver(this, cancelReceiver, IntentFilter(ACTION_CANCEL_OPERATION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archiveName = intent?.getStringExtra(ServiceConstants.EXTRA_ARCHIVE_NAME) ?: return START_NOT_STICKY
        val password = intent.getStringExtra(ServiceConstants.EXTRA_PASSWORD)
        val compressionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD, CompressionMethod::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD) as? CompressionMethod
        } ?: CompressionMethod.STORE

        val compressionLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, CompressionLevel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL) as? CompressionLevel
        } ?: CompressionLevel.NO_COMPRESSION

        val isEncrypted = intent.getBooleanExtra(ServiceConstants.EXTRA_IS_ENCRYPTED, false)

        val encryptionMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD, EncryptionMethod::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD) as? EncryptionMethod
        }

        val aesStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServiceConstants.EXTRA_AES_STRENGTH, AesKeyStrength::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(ServiceConstants.EXTRA_AES_STRENGTH) as? AesKeyStrength
        }
        val jobId = intent.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        val destinationPath = intent.getStringExtra(ServiceConstants.EXTRA_DESTINATION_PATH)

        if (jobId == null) {
            sendErrorBroadcast(getString(R.string.general_error_msg))
            return START_NOT_STICKY
        }

        val splitSize = intent.getLongExtra(ServiceConstants.EXTRA_SPLIT_SIZE, 64)

        startForeground(NOTIFICATION_ID, createNotification(0))

        archiveJob = CoroutineScope(Dispatchers.IO).launch {
            val filesToArchive = fileOperationsDao.getFilesForJob(jobId)
            createSplitZipFile(archiveName, password, compressionMethod, compressionLevel, isEncrypted, encryptionMethod, aesStrength, filesToArchive, splitSize, destinationPath)
            fileOperationsDao.deleteFilesForJob(jobId)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        archiveJob?.cancel()
        unregisterReceiver(cancelReceiver)
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
        val cancelIntent = Intent(ACTION_CANCEL_OPERATION)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.archive_ongoing))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)

        return builder.build()
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(errorMessage: String) {
        sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
    }

    private fun createSplitZipFile(
        archiveName: String,
        password: String?,
        compressionMethod: CompressionMethod,
        compressionLevel: CompressionLevel,
        isEncrypted: Boolean,
        encryptionMethod: EncryptionMethod?,
        aesStrength: AesKeyStrength?,
        selectedFiles: List<String>,
        splitSize: Long,
        destinationPath: String?
    ) {

        if (selectedFiles.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        try {
            val zipParameters = ZipParameters().apply {
                this.compressionMethod = compressionMethod
                this.compressionLevel = compressionLevel
                this.encryptionMethod = if (isEncrypted) encryptionMethod else null
                this.isEncryptFiles = isEncrypted
                this.aesKeyStrength = if (isEncrypted) aesStrength else null
            }

            val firstFile = File(selectedFiles.first())
            val baseDirectory = firstFile.parentFile
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val archivePath = sharedPreferences.getString(PREFERENCE_ARCHIVE_DIR_PATH, null)
            val parentDir: File

            if (!destinationPath.isNullOrBlank()) {
                parentDir = File(destinationPath)
            } else if (!archivePath.isNullOrEmpty()) {
                parentDir = if (File(archivePath).isAbsolute) {
                    File(archivePath)
                } else {
                    File(Environment.getExternalStorageDirectory(), archivePath)
                }
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
            } else {
                val isInternalDir = firstFile.absolutePath.startsWith(filesDir.absolutePath)
                if (isInternalDir) {
                    parentDir = Environment.getExternalStorageDirectory()
                } else {
                    parentDir = firstFile.parentFile ?: Environment.getExternalStorageDirectory()
                }
            }
            val outputFile = File(parentDir, "$archiveName.zip")

            val zipFile = ZipFile(outputFile)
            if (isEncrypted) {
                zipFile.setPassword(password?.toCharArray())
            }

            zipFile.isRunInThread = true
            progressMonitor = zipFile.progressMonitor

            try {
                val tempDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.createTempDirectory("tempArchive").toFile()
                } else {
                    val tempDir = File(cacheDir, "tempArchive")
                    tempDir.mkdirs()
                    tempDir
                }
                val renamedTempDir = File(tempDir.parent, archiveName)
                tempDir.renameTo(renamedTempDir)

                selectedFiles.forEach { filePath ->
                    val file = File(filePath)
                    val destFile = File(renamedTempDir, file.relativeTo(baseDirectory!!).path)
                    if (file.isDirectory) {
                        destFile.mkdirs()
                        val dirsToSetTime = mutableListOf<Pair<File, Long>>()
                        file.walk().forEach { source ->
                            val target = File(destFile, source.relativeTo(file).path)
                            if (source.isDirectory) {
                                target.mkdirs()
                                val lastModified = source.lastModified()
                                dirsToSetTime.add(target to (if (lastModified > 0) lastModified else System.currentTimeMillis()))
                            } else {
                                target.parentFile?.mkdirs()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                } else {
                                    source.inputStream().use { input ->
                                        target.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                                val lastModified = source.lastModified()
                                target.setLastModified(if (lastModified > 0) lastModified else System.currentTimeMillis())
                            }
                        }
                        dirsToSetTime.reverse()
                        dirsToSetTime.forEach { (dir, time) ->
                            dir.setLastModified(time)
                        }
                    } else {
                        destFile.parentFile?.mkdirs()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } else {
                            file.inputStream().use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        val lastModified = file.lastModified()
                        destFile.setLastModified(if (lastModified > 0) lastModified else System.currentTimeMillis())
                    }
                }

                zipFile.createSplitZipFileFromFolder(renamedTempDir, zipParameters, true, splitSize)

                var lastProgress = -1
                while (!progressMonitor!!.state.equals(ProgressMonitor.State.READY)) {
                    val progress = progressMonitor!!.percentDone
                    if (progress > lastProgress) {
                        lastProgress = progress
                        updateProgress(progress)
                    }
                    Thread.sleep(100)
                }

                if (progressMonitor!!.result == ProgressMonitor.Result.SUCCESS) {
                    showCompletionNotification(outputFile)
                    scanForNewFile(outputFile)
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(EXTRA_DIR_PATH, outputFile.parent))
                } else if (progressMonitor!!.result == ProgressMonitor.Result.CANCELLED) {
                    // Do nothing
                } else {
                    showErrorNotification(getString(R.string.zip_creation_failed))
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, progressMonitor!!.result))
                }

                renamedTempDir.deleteRecursively()

            } catch (e: ZipException) {
                e.printStackTrace()
                showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_ARCHIVE_PROGRESS).putExtra(
            EXTRA_PROGRESS, progress))
    }

    private fun showErrorNotification(error: String) {
        stopForegroundService()
        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.error))
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showCompletionNotification(file: File) {
        stopForegroundService()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIRECTORY
            putExtra(MainActivity.EXTRA_DIRECTORY_PATH, file.parent)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.archive_created))
            .setContentText(file.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${file.name} - ${file.parent}"))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
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