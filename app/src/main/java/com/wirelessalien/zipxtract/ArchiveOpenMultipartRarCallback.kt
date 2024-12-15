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


import kotlinx.coroutines.Job
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

class ArchiveOpenMultipartRarCallback(private val parentDir: File, private val extractionJob: Job?) : IArchiveOpenVolumeCallback, IArchiveOpenCallback {
    private val openedRandomAccessFileList = HashMap<String, RandomAccessFile>()
    private var name: String? = null

    @Throws(SevenZipException::class)
    override fun getProperty(propID: PropID): Any? {
        return if (propID == PropID.NAME) name else null
    }

    @Throws(SevenZipException::class)
    override fun getStream(filename: String): IInStream? {
        if (extractionJob?.isCancelled == true) throw SevenZipException("Extraction cancelled")
        try {
            var randomAccessFile = openedRandomAccessFileList[filename]
            if (randomAccessFile != null) {
                randomAccessFile.seek(0)
                name = filename
                return RandomAccessFileInStream(randomAccessFile)
            }

            val file = File(parentDir, filename)
            if (file.exists()) {
                randomAccessFile = RandomAccessFile(file, "r")
                openedRandomAccessFileList[filename] = randomAccessFile
                name = filename
                return RandomAccessFileInStream(randomAccessFile)
            }
            return null
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: Exception) {
            throw SevenZipException("Error opening file", e)
        }
    }

    @Throws(IOException::class)
    fun close() {
        for (file in openedRandomAccessFileList.values) {
            file.close()
        }
    }

    @Throws(SevenZipException::class)
    override fun setCompleted(files: Long?, bytes: Long?) {}

    @Throws(SevenZipException::class)
    override fun setTotal(files: Long?, bytes: Long?) {}
}