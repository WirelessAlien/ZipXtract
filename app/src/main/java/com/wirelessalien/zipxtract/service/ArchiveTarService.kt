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
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItemTar
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Date

class ArchiveTarService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 15
    }

    private var archiveJob: Job? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_OPERATION) {
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
        LocalBroadcastManager.getInstance(this).registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_OPERATION))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archiveName = intent?.getStringExtra(ServiceConstants.EXTRA_ARCHIVE_NAME) ?: return START_NOT_STICKY
        val jobId = intent.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        if (jobId == null) {
            sendErrorBroadcast(getString(R.string.general_error_msg))
            return START_NOT_STICKY
        }
        val filesToArchive = fileOperationsDao.getFilesForJob(jobId)
        val compressionFormat = intent.getStringExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT) ?: "TAR_ONLY"

        startForeground(NOTIFICATION_ID, createNotification(0))

        archiveJob = CoroutineScope(Dispatchers.IO).launch {
            createTarFile(archiveName, filesToArchive, compressionFormat)
            fileOperationsDao.deleteFilesForJob(jobId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        archiveJob?.cancel()
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

    private fun createTarFile(archiveName: String, filesToArchive: List<String>, compressionFormat: String) {

        if (filesToArchive.isEmpty()) {
            val errorMessage = getString(R.string.no_files_to_archive)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
            stopForegroundService()
            return
        }

        // Get ZSTD compression level from SharedPreferences
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val compressionLevelString = sharedPreferences.getString("zstd_compression_level", "3")
        val compressionLevel = compressionLevelString?.toIntOrNull() ?: 3
        val safeLevel = compressionLevel.coerceIn(0, 22)

        try {
            val firstFile = File(filesToArchive.first())
            val baseDirectory = firstFile.parentFile?.absolutePath ?: ""
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
                val isInternalDir = firstFile.absolutePath.startsWith(filesDir.absolutePath)
                if (isInternalDir) {
                    parentDir = Environment.getExternalStorageDirectory()
                } else {
                    parentDir = firstFile.parentFile ?: Environment.getExternalStorageDirectory()
                }
            }

            val extension = getExtension(compressionFormat)
            var tarFile = File(parentDir, "$archiveName$extension")
            var counter = 1

            while (tarFile.exists()) {
                tarFile = File(parentDir, "$archiveName ($counter)$extension")
                counter++
            }

            val finalTarFile = tarFile
            var tempTarFile: File? = null

            val compressionActive = compressionFormat != "TAR_ONLY"
            try {
                if (!compressionActive) {
                    // Direct TAR creation (no intermediate compression)
                    RandomAccessFile(finalTarFile, "rw").use { raf ->
                        val outArchive = SevenZip.openOutArchiveTar()
                        outArchive.createArchive(
                            RandomAccessFileOutStream(raf), filesToArchive.size,
                            createArchiveCallback(filesToArchive, baseDirectory, compressionActive)
                        )
                        outArchive.close()
                    }
                } else {
                    // Two-step: 1. Create temporary TAR, 2. Compress temporary TAR
                    tempTarFile = File.createTempFile("archive", ".tar", cacheDir)
                    RandomAccessFile(tempTarFile, "rw").use { tempRaf ->
                        val outArchive = SevenZip.openOutArchiveTar()
                        outArchive.createArchive(
                            RandomAccessFileOutStream(tempRaf), filesToArchive.size,
                            createArchiveCallback(filesToArchive, baseDirectory, compressionActive)
                        )
                        outArchive.close()
                    }

                    tempTarFile.inputStream().use { fis ->
                        BufferedOutputStream(finalTarFile.outputStream()).use { bos ->
                            val compressorOutputStream = when (compressionFormat) {
                                CompressorStreamFactory.LZMA -> LZMACompressorOutputStream(bos)
                                CompressorStreamFactory.BZIP2 -> BZip2CompressorOutputStream(bos)
                                CompressorStreamFactory.XZ -> XZCompressorOutputStream(bos)
                                CompressorStreamFactory.ZSTANDARD -> ZstdOutputStream(bos).apply { setLevel(safeLevel) }
                                CompressorStreamFactory.GZIP -> GzipCompressorOutputStream(bos)
                                else -> bos
                            }
                            compressorOutputStream.use { cos ->
                                val tempTarSize = tempTarFile.length()
                                if (tempTarSize > 0) {
                                    var bytesCopied = 0L
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE) // 8KB buffer
                                    var bytes = fis.read(buffer)
                                    while (bytes >= 0) {
                                        cos.write(buffer, 0, bytes)
                                        bytesCopied += bytes
                                        val compressionProgress = ((bytesCopied.toDouble() / tempTarSize) * 50).toInt()
                                        updateProgress(50 + compressionProgress)
                                        bytes = fis.read(buffer)
                                    }
                                }
                            }
                        }
                    }
                }
                stopForegroundService()
                showCompletionNotification(finalTarFile.parent ?: "")
                scanForNewFile(finalTarFile)
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_COMPLETE).putExtra(EXTRA_DIR_PATH, finalTarFile.parent))

            } catch (e: SevenZipException) {
                if (e.message == "Cancelled") {
                    // Cancelled by user, do nothing
                } else {
                    e.printStackTrace()
                    showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                    sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
                }
            } catch (e: IOException) {
                e.printStackTrace()
                showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
                showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
            } finally {
                tempTarFile?.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message ?: getString(R.string.general_error_msg))
            sendLocalBroadcast(Intent(ACTION_ARCHIVE_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message))
            stopForegroundService()
        }
    }

    private fun createArchiveCallback(filesToArchive: List<String>, baseDirectory: String, compressionActive: Boolean): IOutCreateCallback<IOutItemTar> {
        return object : IOutCreateCallback<IOutItemTar> {
            override fun setOperationResult(operationResultOk: Boolean) {}

            override fun setTotal(total: Long) {}

            override fun setCompleted(complete: Long) {
                if (archiveJob?.isActive == false) {
                    throw SevenZipException("Cancelled")
                }
                val totalSize = filesToArchive.sumOf { File(it).length() }
                var progress = if (totalSize > 0) ((complete.toDouble() / totalSize) * 100).toInt() else 0
                if (compressionActive) {
                    progress /= 2 // Scale to 0-50
                }
                startForeground(NOTIFICATION_ID, createNotification(progress))
                updateProgress(progress)
            }

            override fun getItemInformation(index: Int, outItemFactory: OutItemFactory<IOutItemTar>): IOutItemTar {
                val item = outItemFactory.createOutItem()
                val file = File(filesToArchive[index])
                val relativePath = file.absolutePath.removePrefix(baseDirectory).removePrefix("/")

                item.dataSize = file.length()
                item.propertyPath = relativePath
                item.propertyIsDir = file.isDirectory
                item.propertyLastModificationTime = Date(file.lastModified())

                return item
            }

            override fun getStream(i: Int): ISequentialInStream? {
                return if (File(filesToArchive[i]).isDirectory) null else RandomAccessFileInStream(RandomAccessFile(filesToArchive[i], "r"))
            }
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        sendLocalBroadcast(Intent(ACTION_ARCHIVE_PROGRESS).putExtra(EXTRA_PROGRESS, progress))
    }

    private fun getExtension(compressionFormat: String): String {
        return when (compressionFormat) {
            CompressorStreamFactory.LZMA -> ".tar.lzma"
            CompressorStreamFactory.BZIP2 -> ".tar.bz2"
            CompressorStreamFactory.XZ -> ".tar.xz"
            CompressorStreamFactory.ZSTANDARD -> ".tar.zst"
            CompressorStreamFactory.GZIP -> ".tar.gz"
            else -> ".tar"
        }
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
            .setContentTitle(getString(R.string.tar_creation_success))
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
            .setContentTitle(getString(R.string.tar_creation_failed))
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

    private fun scanForNewFile(file: File) {
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
    }
}