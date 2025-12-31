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

package com.wirelessalien.zipxtract.helper

import net.lingala.zip4j.ZipFile
import java.io.File

object MultipartArchiveHelper {

    fun isMultipartZip(file: File): Boolean {
        // Explicit .zip check for master split file using Zip4j
        if (file.extension.equals("zip", ignoreCase = true)) {
            return try {
                ZipFile(file).use { zipFile ->
                    zipFile.isSplitArchive
                }
            } catch (_: Exception) {
                false
            }
        }

        val fileName = file.name
        return fileName.matches(Regex(".*\\.zip\\.\\d{3}")) ||
                fileName.matches(Regex(".*\\.z\\d{2}"))
    }

    fun isMultipart7z(file: File): Boolean {
        val fileName = file.name
        return fileName.matches(Regex(".*\\.7z\\.\\d{3}"))
    }

    fun isMultipartRar(file: File): Boolean {
        val fileName = file.name
        val rarPartRegex = Regex("^(.*)\\.part\\d+\\.rar$")
        val rPartRegex = Regex("^(.*)\\.r\\d{2}$")
        val numPartRegex = Regex("^(.*)\\.\\d{3}$")

        if (fileName.matches(rarPartRegex) || fileName.matches(rPartRegex)) {
            return true
        }


        if (fileName.matches(numPartRegex)) {
            // Check if it is NOT zip or 7z pattern
            if (isMultipartZip(file)) return false
            if (isMultipart7z(file)) return false

            return true
        }

        return false
    }

    //not used
    fun isMultipartArchive(file: File): Boolean {
        return isMultipartZip(file) || isMultipart7z(file) || isMultipartRar(file)
    }
}
