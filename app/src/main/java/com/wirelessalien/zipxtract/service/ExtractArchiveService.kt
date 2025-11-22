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
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRACTION_NOTIFICATION_CHANNEL_ID
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.model.DirectoryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Date


class ExtractArchiveService : Service() {

    private lateinit var fileOperationsDao: FileOperationsDao

    companion object {
        const val NOTIFICATION_ID = 18
    }

    private var archiveFormat: ArchiveFormat? = null
    private var extractionJob: Job? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fileOperationsDao = FileOperationsDao(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
        val password = intent?.getStringExtra(ServiceConstants.EXTRA_PASSWORD)
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
            Log.w("ExtractArchiveService", "This service only supports single file extraction. Only the first file will be extracted.")
        }
        val filePath = filesToExtract[0]


        startForeground(NOTIFICATION_ID, createNotification(0))

        extractionJob = CoroutineScope(Dispatchers.IO).launch {
            extractArchive(filePath, password, useAppNameDir)
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

            try {
                val inArchive = SevenZip.openInArchive(archiveFormat, inStream, OpenCallback(password))
                destinationDir.mkdir()

                try {
                    val extractCallback = ExtractCallback(inArchive, destinationDir, password)
                    inArchive.extract(null, false, extractCallback)

                    if (extractCallback.hasUnsupportedMethod) {
                        tryLibArchiveAndroid(file, destinationDir)
                    } else {
                        FileUtils.setLastModifiedTime(extractCallback.directories)
                        scanForNewFiles(destinationDir)
                        showCompletionNotification(destinationDir.absolutePath)
                        sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))
                    }
                } catch (e: SevenZipException) {
                    e.printStackTrace()
                    showErrorNotification(e.message ?: getString(R.string.general_error_msg))
                    sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, e.message ?: getString(R.string.general_error_msg)))
                } finally {
                    inArchive.close()
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

    private fun tryLibArchiveAndroid(file: File, destinationDir: File) {
        try {
            val totalBytes = file.length()
            var bytesProcessed = 0L

            FileInputStream(file).use { fileInput ->
                BufferedInputStream(fileInput).use {
                    val fileDescriptor = fileInput.fd
                    var archive: Long = 0
                    try {
                        archive = Archive.readNew()
                        Archive.setCharset(
                            archive,
                            StandardCharsets.UTF_8.name().toByteArray(StandardCharsets.UTF_8)
                        )
                        Archive.readSupportFilterAll(archive)
                        Archive.readSupportFormatAll(archive)

                        val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)

                        Archive.readSetCallbackData(archive, fileDescriptor)
                        Archive.readSetReadCallback(
                            archive,
                            object : Archive.ReadCallback<FileDescriptor> {
                                override fun onRead(
                                    archive: Long,
                                    clientData: FileDescriptor
                                ): ByteBuffer? {
                                    buffer.clear()
                                    try {
                                        val bytesRead = Os.read(clientData, buffer)
                                        bytesProcessed += bytesRead
                                        val progress = (bytesProcessed * 100 / totalBytes).toInt()
                                        updateProgress(progress)
                                        buffer.flip()
                                        return buffer
                                    } catch (e: Exception) {
                                        Log.e("ExtractArchiveService", "Read error", e)
                                    }
                                    return null
                                }

                            })

                        Archive.readSetSkipCallback(
                            archive,
                            object : Archive.SkipCallback<FileDescriptor> {
                                override fun onSkip(
                                    archive: Long,
                                    clientData: FileDescriptor,
                                    request: Long
                                ): Long {
                                    try {
                                        return Os.lseek(clientData, request, OsConstants.SEEK_CUR)
                                    } catch (e: Exception) {
                                        Log.e("ExtractArchiveService", "Skip error", e)
                                    }
                                    return 0
                                }
                            })

                        Archive.readSetSeekCallback(
                            archive,
                            object : Archive.SeekCallback<FileDescriptor> {
                                override fun onSeek(
                                    archive: Long,
                                    clientData: FileDescriptor,
                                    offset: Long,
                                    whence: Int
                                ): Long {
                                    try {
                                        return Os.lseek(clientData, offset, whence)
                                    } catch (e: Exception) {
                                        Log.e("ExtractArchiveService", "Seek error", e)
                                    }
                                    return 0
                                }
                            })

                        Archive.readOpen1(archive)

                        var entry = Archive.readNextHeader(archive)
                        while (entry != 0L) {
                            val entryPath = getEntryPath(entry)
                            val outputFile = File(destinationDir, entryPath)

                            outputFile.parentFile?.mkdirs()

                            if (entryPath.endsWith("/")) {
                                outputFile.mkdirs()
                            } else {
                                BufferedOutputStream(outputFile.outputStream()).use { outputStream ->
                                    val readBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)

                                    while (true) {
                                        readBuffer.clear()
                                        Archive.readData(archive, readBuffer)
                                        val bytesRead = readBuffer.position()

                                        if (bytesRead <= 0) break

                                        readBuffer.flip()
                                        val bytes = ByteArray(bytesRead)
                                        readBuffer.get(bytes)
                                        outputStream.write(bytes)
                                    }
                                }
                                val lastModifiedTime = ArchiveEntry.mtime(entry)
                                outputFile.setLastModified(lastModifiedTime * 1000)
                            }
                            entry = Archive.readNextHeader(archive)
                        }

                        scanForNewFiles(destinationDir)
                        showCompletionNotification(destinationDir.absolutePath)
                        sendLocalBroadcast(
                            Intent(ACTION_EXTRACTION_COMPLETE)
                                .putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath)
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val errorMessage = e.message ?: getString(R.string.general_error_msg)
                        showErrorNotification(errorMessage)
                        sendLocalBroadcast(
                            Intent(ACTION_EXTRACTION_ERROR).putExtra(
                                EXTRA_ERROR_MESSAGE,
                                errorMessage
                            )
                        )
                    } finally {
                        if (archive != 0L) {
                            Archive.free(archive)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = e.message ?: getString(R.string.general_error_msg)
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
        }
    }

    private fun getEntryPath(entry: Long): String {
        val utf8Path = ArchiveEntry.pathnameUtf8(entry)
        val defaultPath = ArchiveEntry.pathname(entry)

        return when {
            utf8Path != null -> utf8Path
            defaultPath != null -> String(defaultPath, StandardCharsets.UTF_8)
            else -> ""
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

            TarArchiveInputStream(FileInputStream(file)).use { tarInput ->
                var entry: TarArchiveEntry? = tarInput.nextEntry
                while (entry != null) {
                    val outputFile = File(destinationDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                        directories.add(DirectoryInfo(outputFile.path, entry.modTime.time))
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
                        outputFile.setLastModified(entry.modTime.time)

                    }
                    entry = tarInput.nextEntry
                }
            }
            FileUtils.setLastModifiedTime(directories)
            scanForNewFiles(destinationDir)
            showCompletionNotification(destinationDir.absolutePath)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))
        } catch (e: IOException) {
            e.printStackTrace()
            tryLibArchiveAndroid(file, destinationDir)
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

            zipFile.isRunInThread = true
            val directories = mutableListOf<DirectoryInfo>()
            for (fileHeader in zipFile.fileHeaders) {
                if (fileHeader.isDirectory) {
                    val directoryPath = File(destinationDir, fileHeader.fileName).path
                    directories.add(DirectoryInfo(directoryPath, fileHeader.lastModifiedTimeEpoch))
                }
            }
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
            FileUtils.setLastModifiedTime(directories)
            scanForNewFiles(destinationDir)
            showCompletionNotification(destinationDir.absolutePath)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_COMPLETE).putExtra(EXTRA_DIR_PATH, destinationDir.absolutePath))

            if (useAppNameDir) {
                filesDir.deleteRecursively()
            }

        } catch (e: ZipException) {
            e.printStackTrace()
            val errorMessage = when (e.type) {
                ZipException.Type.WRONG_PASSWORD -> getString(R.string.wrong_password)
                ZipException.Type.UNKNOWN_COMPRESSION_METHOD -> {
                    tryLibArchiveAndroid(file, File(Environment.getExternalStorageDirectory().absolutePath))
                    return
                }
                ZipException.Type.UNSUPPORTED_ENCRYPTION -> getString(R.string.general_error_msg)
                else -> e.message ?: getString(R.string.general_error_msg)
            }
            showErrorNotification(errorMessage)
            sendLocalBroadcast(Intent(ACTION_EXTRACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
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
        private var currentFileIndex: Int = -1
        private var currentUnpackedFile: File? = null
        var hasUnsupportedMethod = false
        val directories = mutableListOf<DirectoryInfo>()

        init {
            totalSize = inArchive.numberOfItems.toLong()
        }

        private var errorBroadcasted = false

        override fun setOperationResult(p0: ExtractOperationResult?) {
            when (p0) {
                ExtractOperationResult.UNSUPPORTEDMETHOD -> {
                    hasUnsupportedMethod = true
                }
                ExtractOperationResult.WRONG_PASSWORD -> {
                    if (!errorBroadcasted) {
                        showErrorNotification(getString(R.string.wrong_password))
                        sendLocalBroadcast(
                            Intent(ACTION_EXTRACTION_ERROR).putExtra(
                                EXTRA_ERROR_MESSAGE,
                                getString(R.string.wrong_password)
                            )
                        )
                        errorBroadcasted = true
                    }
                }
                ExtractOperationResult.DATAERROR, ExtractOperationResult.CRCERROR, ExtractOperationResult.UNAVAILABLE, ExtractOperationResult.HEADERS_ERROR, ExtractOperationResult.UNEXPECTED_END, ExtractOperationResult.UNKNOWN_OPERATION_RESULT -> {
                    if (!errorBroadcasted) {
                        showErrorNotification(getString(R.string.general_error_msg))
                        sendLocalBroadcast(
                            Intent(ACTION_EXTRACTION_ERROR).putExtra(
                                EXTRA_ERROR_MESSAGE,
                                getString(R.string.general_error_msg)
                            )
                        )
                        errorBroadcasted = true
                    }
                }
                ExtractOperationResult.OK -> {
                    try {
                        uos?.close()
                        if (this.currentUnpackedFile != null) {
                            val modTime =
                                inArchive.getProperty(
                                    this.currentFileIndex,
                                    PropID.LAST_MODIFICATION_TIME
                                ) as? Date
                            if (modTime != null) {
                                this.currentUnpackedFile!!.setLastModified(modTime.time)
                            }
                        }
                        // Reset currentUnpackedFile and currentFileIndex for the next entry
                        this.currentUnpackedFile = null
                        this.currentFileIndex = -1
                        extractedSize++
                    } catch (e: SevenZipException) {
                        e.printStackTrace()
                    }
                }
                else -> {
                    if (!errorBroadcasted) {
                        showErrorNotification(getString(R.string.general_error_msg))
                        sendLocalBroadcast(
                            Intent(ACTION_EXTRACTION_ERROR).putExtra(
                                EXTRA_ERROR_MESSAGE,
                                getString(R.string.general_error_msg)
                            )
                        )
                        errorBroadcasted = true
                    }
                }
            }
        }

        override fun getStream(p0: Int, p1: ExtractAskMode?): ISequentialOutStream {
            this.currentFileIndex = p0 // Store current file index

            val path: String = inArchive.getStringProperty(p0, PropID.PATH)
            val isDir: Boolean = inArchive.getProperty(p0, PropID.IS_FOLDER) as Boolean
            this.currentUnpackedFile = File(dstDir.path, path) // Store current unpacked file

            if (isDir) {
                this.currentUnpackedFile!!.mkdir()
                val lastModified =
                    (inArchive.getProperty(p0, PropID.LAST_MODIFICATION_TIME) as? Date)?.time
                        ?: System.currentTimeMillis()
                directories.add(DirectoryInfo(this.currentUnpackedFile!!.path, lastModified))
            } else {
                try {
                    val dir = this.currentUnpackedFile!!.parent?.let { File(it) }
                    if (dir != null && !dir.isDirectory) {
                        dir.mkdirs()
                    }
                    this.currentUnpackedFile!!.createNewFile()
                    uos = FileOutputStream(this.currentUnpackedFile!!)
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

    private fun showCompletionNotification(destinationPath: String) {
        stopForegroundService()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DIRECTORY
            putExtra(MainActivity.EXTRA_DIRECTORY_PATH, destinationPath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)


        val notification = NotificationCompat.Builder(this, EXTRACTION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.extraction_success))
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