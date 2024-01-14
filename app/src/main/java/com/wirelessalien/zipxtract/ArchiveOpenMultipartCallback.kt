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

import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

class ArchiveOpenMultipartCallback : IArchiveOpenVolumeCallback, IArchiveOpenCallback, ICryptoGetTextPassword  {
    private val openedRandomAccessFileList = HashMap<String, RandomAccessFile>()
    private var name: String? = null
    private var password: CharArray? = null

    @Throws(SevenZipException::class)
    override fun getProperty(propID: PropID): Any? {
        return if (propID == PropID.NAME) name else null
    }

    @Throws(SevenZipException::class)
    override fun getStream(filename: String): IInStream? {
        return try {
            var randomAccessFile = openedRandomAccessFileList[filename]
            if (randomAccessFile != null) { // Cache hit.
                randomAccessFile.seek(0)
                name = filename
                RandomAccessFileInStream(randomAccessFile)
            } else {
                randomAccessFile = RandomAccessFile(filename, "r")
                openedRandomAccessFileList[filename] = randomAccessFile
                name = filename
                RandomAccessFileInStream(randomAccessFile)
            }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun cryptoGetTextPassword(): String {
        return String(password ?: CharArray(0))
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

