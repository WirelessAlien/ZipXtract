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
import kotlinx.coroutines.launch
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.progress.ProgressMonitor
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile


class ExtractArchiveService : Service() {

    companion object {
        const val NOTIFICATION_ID = 18
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USE_APP_NAME_DIR = "useAppNameDir"
    }

    private var archiveFormat: ArchiveFormat? = null
    private var extractionJob: Job? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH)
        val password = intent?.getStringExtra(EXTRA_PASSWORD)
        val useAppNameDir = intent?.getBooleanExtra(EXTRA_USE_APP_NAME_DIR, false) ?: false

        if (filePath == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(filePath, password, useAppNameDir)
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

    private fun extractArchive(filePath: String, password: String?, useAppNameDir: Boolean) {

        if (filePath.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        val file = File(filePath)
        when {
            file.extension.equals("zip", ignoreCase = true) -> {
                extractZipArchive(file, password, useAppNameDir)
                return
            }
            file.extension.equals("tar", ignoreCase = true) -> {
                extractTarArchive(file, useAppNameDir)
                return
            }
        }

        try {
            val inStream = RandomAccessFileInStream(RandomAccessFile(file, "r"))
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

            try {
                val inArchive = SevenZip.openInArchive(archiveFormat, inStream, OpenCallback(password))
                destinationDir.mkdir()

                try {
                    inArchive.extract(null, false, ExtractCallback(inArchive, destinationDir, password))
                    showCompletionNotification()
                    sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))
                } catch (e: SevenZipException) {
                    e.printStackTrace()
                    showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                    sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg))
                    )
                }
            } catch (e: SevenZipException) {
                e.printStackTrace()
                showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
            } finally {
                inStream.close()
                if (useAppNameDir) {
                    filesDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        }
    }

    private fun extractTarArchive(file: File, useAppNameDir: Boolean) {
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

            TarArchiveInputStream(FileInputStream(file)).use { tarInput ->
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
                    }
                    entry = tarInput.nextEntry
                }
            }
            showCompletionNotification()
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))
        } catch (e: IOException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        }
    }

    private fun extractZipArchive(file: File, password: String?, useAppNameDir: Boolean) {
        try {
            val zipFile = ZipFile(file)

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

            zipFile.isRunInThread = true
            zipFile.extractAll(destinationDir.absolutePath)

            val progressMonitor = zipFile.progressMonitor
            while (!progressMonitor.state.equals(ProgressMonitor.State.READY)) {
                if (progressMonitor.state.equals(ProgressMonitor.State.BUSY)) {
                    val percentDone = (progressMonitor.percentDone)
                    startForeground(NOTIFICATION_ID, createNotification(percentDone))
                    sendLocalBroadcast(Intent(ACTION_EXTRACTION_PROGRESS).putExtra(EXTRA_PROGRESS, percentDone))
                }
                Thread.sleep(100)
            }

            showCompletionNotification()
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))

            if (useAppNameDir) {
                filesDir.deleteRecursively()
            }

        } catch (e: ZipException) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR)
                .putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
        }
    }

    private inner class OpenCallback(private val password: String?) : IArchiveOpenCallback, ICryptoGetTextPassword {
        override fun setCompleted(p0: Long?, p1: Long?) {}
        override fun setTotal(p0: Long?, p1: Long?) {}
        override fun cryptoGetTextPassword(): String {
            return password ?: ""
        }
    }

    private inner class ExtractCallback(
        private val inArchive: IInArchive,
        private val dstDir: File,
        private val password: String?
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private var uos: OutputStream? = null
        private var totalSize: Long = 0
        private var extractedSize: Long = 0

        init {
            totalSize = inArchive.numberOfItems.toLong()
        }

        override fun setOperationResult(p0: ExtractOperationResult?) {
            if (p0 == ExtractOperationResult.OK) {
                try {
                    uos?.close()
                    extractedSize++
                } catch (e: SevenZipException) {
                    e.printStackTrace()
                }
            }
        }

        override fun getStream(p0: Int, p1: ExtractAskMode?): ISequentialOutStream {

            val path: String = inArchive.getStringProperty(p0, PropID.PATH)
            val isDir: Boolean = inArchive.getProperty(p0, PropID.IS_FOLDER) as Boolean
            val unpackedFile = File(dstDir.path, path)

            if (isDir) {
                unpackedFile.mkdir()
            } else {
                try {
                    val dir = unpackedFile.parent?.let { File(it) }
                    if (dir != null && !dir.isDirectory) {
                        dir.mkdirs()
                    }
                    unpackedFile.createNewFile()
                    uos = FileOutputStream(unpackedFile)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            return ISequentialOutStream { data: ByteArray ->
                try {
                    if (!isDir) {
                        uos?.write(data)
                    }
                } catch (e: SevenZipException) {
                    e.printStackTrace()
                }
                data.size
            }
        }

        override fun prepareOperation(p0: ExtractAskMode?) {}

        override fun setCompleted(complete: Long) {
            val progress = ((complete.toDouble() / totalSize) * 100).toInt()
            startForeground(NOTIFICATION_ID, createNotification(progress))
            updateProgress(progress)
        }

        override fun setTotal(p0: Long) {
            totalSize = p0
        }

        override fun cryptoGetTextPassword(): String {
            return password ?: ""
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Broadcast progress for activity
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
}
