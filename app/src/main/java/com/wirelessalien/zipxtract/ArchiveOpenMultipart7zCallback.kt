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

package com.wirelessalien.zipxtract

import android.util.Log
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

class ArchiveOpenMultipart7zCallback(private val parentDir: File) : IArchiveOpenVolumeCallback {
    private val openedRandomAccessFileList = mutableMapOf<String, RandomAccessFile>()

    override fun getProperty(propID: PropID?): Any? {
        return null
    }

    override fun getStream(filename: String?): IInStream? {
        if (filename == null) return null
        return try {
            // First check if we already have this file open
            var randomAccessFile = openedRandomAccessFileList[filename]
            if (randomAccessFile != null) {
                randomAccessFile.seek(0)
                return RandomAccessFileInStream(randomAccessFile)
            }

            val volumeFile = File(parentDir, File(filename).name)

            if (!volumeFile.exists()) {
                return null
            }

            randomAccessFile = RandomAccessFile(volumeFile, "r")
            openedRandomAccessFileList[filename] = randomAccessFile
            RandomAccessFileInStream(randomAccessFile)
        } catch (e: FileNotFoundException) {
            Log.e("7zExtract", "Failed to open volume: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e("7zExtract", "Error accessing volume: ${e.message}", e)
            throw RuntimeException(e)
        }
    }

    fun close() {
        for (file in openedRandomAccessFileList.values) {
            try {
                file.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        openedRandomAccessFileList.clear()
    }
}