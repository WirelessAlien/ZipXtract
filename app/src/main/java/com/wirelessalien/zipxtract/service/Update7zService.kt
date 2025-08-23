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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItemAllFormats
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

class Update7zService : Service() {

    companion object {
        const val NOTIFICATION_ID = 14
        const val EXTRA_ARCHIVE_PATH = "archivePath"
        const val EXTRA_ITEMS_TO_ADD_PATHS = "itemsToAddPaths"
        const val EXTRA_ITEMS_TO_ADD_NAMES = "itemsToAddNames"
        const val EXTRA_ITEMS_TO_REMOVE_PATHS = "itemsToRemovePaths"
    }

    private var updateJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var totalSize: Long = 0
    private var lastProgress = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val archivePath = intent?.getStringExtra(EXTRA_ARCHIVE_PATH) ?: return START_NOT_STICKY
        val itemsToAddPaths = intent.getStringArrayListExtra(EXTRA_ITEMS_TO_ADD_PATHS)
        val itemsToAddNames = intent.getStringArrayListExtra(EXTRA_ITEMS_TO_ADD_NAMES)
        val itemsToRemovePaths = intent.getStringArrayListExtra(EXTRA_ITEMS_TO_REMOVE_PATHS)

        lastProgress = -1
        notificationBuilder = createNotificationBuilder()
        startForeground(NOTIFICATION_ID, notificationBuilder.build())

        updateJob = CoroutineScope(Dispatchers.IO).launch {
            update7zFile(archivePath, itemsToAddPaths, itemsToAddNames, itemsToRemovePaths)
        }
        return START_STICKY
    }

    private fun update7zFile(
        archivePath: String,
        itemsToAddPaths: List<String>?,
        itemsToAddNames: List<String>?,
        itemsToRemovePaths: List<String>?
    ) {
        val closeables = ArrayList<Closeable>()
        var tmpFile: File? = null
        var success = false
        try {
            val inStream = RandomAccessFileInStream(RandomAccessFile(archivePath, "r"))
            val inArchive = SevenZip.openInArchive(null, inStream)
            closeables.add(inArchive)

            val outArchive = inArchive.connectedOutArchive
            tmpFile = File.createTempFile("7z-update", ".tmp")
            val outStream = RandomAccessFileOutStream(RandomAccessFile(tmpFile, "rw"))
            closeables.add(outStream)

            val itemsToRemoveIndices = mutableListOf<Int>()
            if (itemsToRemovePaths != null) {
                val normalizedItemsToRemovePaths = itemsToRemovePaths.map { it.replace("\\", "/") }
                for (i in 0 until inArchive.numberOfItems) {
                    val itemPath = (inArchive.getProperty(i, PropID.PATH) as String).replace("\\", "/")
                    for (pathToRemove in normalizedItemsToRemovePaths) {
                        if (itemPath == pathToRemove || itemPath.startsWith("$pathToRemove/")) {
                            itemsToRemoveIndices.add(i)
                            break
                        }
                    }
                }
            }
            itemsToRemoveIndices.sort()

            val filesToAdd = if (itemsToAddPaths != null && itemsToAddNames != null) {
                getFilesToAdd(itemsToAddPaths, itemsToAddNames)
            } else {
                emptyList()
            }

            val numToAdd = filesToAdd.size
            val newCount = inArchive.numberOfItems - itemsToRemoveIndices.size + numToAdd

            outArchive.updateItems(outStream, newCount, object : IOutCreateCallback<IOutItemAllFormats> {
                override fun setOperationResult(p0: Boolean) {}
                override fun setTotal(size: Long) {
                    totalSize = size
                }

                override fun setCompleted(completed: Long) {
                    val progress = if (totalSize == 0L) 0 else (completed * 100 / totalSize).toInt()
                    if (progress > lastProgress) {
                        lastProgress = progress
                        updateNotification(progress)
                        sendProgressBroadcast(progress)
                    }
                }

                override fun getItemInformation(
                    index: Int,
                    outItemFactory: OutItemFactory<IOutItemAllFormats>
                ): IOutItemAllFormats {
                    if (index >= inArchive.numberOfItems - itemsToRemoveIndices.size) {
                        // This is a new item
                        val addItemIndex = index - (inArchive.numberOfItems - itemsToRemoveIndices.size)
                        val (file, pathInArchive) = filesToAdd[addItemIndex]
                        val outItem = outItemFactory.createOutItem()
                        outItem.propertyPath = pathInArchive
                        outItem.propertyLastModificationTime = java.util.Date(file.lastModified())
                        if (file.isDirectory) {
                            outItem.propertyIsDir = true
                        } else {
                            outItem.dataSize = file.length()
                        }
                        return outItem
                    }

                    var oldIndex = index
                    var removedCount = 0
                    for (removedIndex in itemsToRemoveIndices) {
                        if (oldIndex + removedCount >= removedIndex) {
                            removedCount++
                        }
                    }
                    oldIndex += removedCount

                    return outItemFactory.createOutItem(oldIndex)
                }

                override fun getStream(index: Int): ISequentialInStream? {
                    if (index >= inArchive.numberOfItems - itemsToRemoveIndices.size) {
                        val addItemIndex = index - (inArchive.numberOfItems - itemsToRemoveIndices.size)
                        val (file, _) = filesToAdd[addItemIndex]
                        if (file.isFile) {
                            val stream = RandomAccessFileInStream(RandomAccessFile(file, "r"))
                            closeables.add(stream)
                            return stream
                        }
                    }
                    return null
                }
            })

            inArchive.close()
            closeables.remove(inArchive)
            outStream.close()
            closeables.remove(outStream)

            val originalFile = File(archivePath)
            tmpFile.copyTo(originalFile, overwrite = true)

            success = true
        } catch (e: Exception) {
            e.printStackTrace()
            sendLocalBroadcast(Intent(BroadcastConstants.ACTION_ARCHIVE_ERROR).apply {
                putExtra(BroadcastConstants.EXTRA_ERROR_MESSAGE, e.message)
            })
        } finally {
            for (i in closeables.indices.reversed()) {
                try {
                    closeables[i].close()
                } catch (e: Exception) {
                    success = false
                }
            }
            tmpFile?.delete()
        }

        if (success) {
            sendLocalBroadcast(Intent(BroadcastConstants.ACTION_ARCHIVE_COMPLETE))
        } else {
            sendLocalBroadcast(Intent(BroadcastConstants.ACTION_ARCHIVE_ERROR))
        }
        stopForegroundService()
    }

    private fun getFilesToAdd(
        itemsToAddPaths: List<String>,
        itemsToAddNames: List<String>
    ): List<Pair<File, String>> {
        val fileList = mutableListOf<Pair<File, String>>()
        for (i in itemsToAddPaths.indices) {
            val file = File(itemsToAddPaths[i])
            val name = itemsToAddNames[i]
            if (file.isDirectory) {
                file.walkTopDown().forEach {
                    val relativePath = it.absolutePath.substring(file.absolutePath.length)
                        .removePrefix("/")
                    val archivePath = if (relativePath.isEmpty()) {
                        name
                    } else {
                        "$name/$relativePath"
                    }
                    fileList.add(Pair(it, archivePath))
                }
            } else {
                fileList.add(Pair(file, name))
            }
        }
        return fileList
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID,
                "Update Archive Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotificationBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, BroadcastConstants.ARCHIVE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Updating archive...")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setProgress(100, 0, true)
            .setOngoing(true)
    }

    private fun updateNotification(progress: Int) {
        notificationBuilder.setProgress(100, progress, false)
            .setContentText("$progress%")
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun sendProgressBroadcast(progress: Int) {
        val intent = Intent(BroadcastConstants.ACTION_ARCHIVE_PROGRESS).apply {
            putExtra(BroadcastConstants.EXTRA_PROGRESS, progress)
        }
        sendLocalBroadcast(intent)
    }

    private fun sendLocalBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
