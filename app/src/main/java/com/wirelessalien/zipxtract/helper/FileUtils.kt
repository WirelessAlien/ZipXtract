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

import android.util.Log
import com.wirelessalien.zipxtract.model.DirectoryInfo
import java.io.File

object FileUtils {
    fun setLastModifiedTime(directories: List<DirectoryInfo>) {
        val sortedDirectories = directories.sortedByDescending { it.path.length }

        for (directory in sortedDirectories) {
            try {
                val file = File(directory.path)
                if (file.exists() && file.isDirectory) {
                    file.setLastModified(directory.lastModified)
                }
            } catch (e: Exception) {
                Log.e("FileUtils", "Failed to set last modified time for ${directory.path}", e)
            }
        }
    }
}
