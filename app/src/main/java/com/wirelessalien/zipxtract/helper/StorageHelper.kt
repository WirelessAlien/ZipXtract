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

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager

object StorageHelper {

    fun getSdCardPath(context: Context): String? {
        // Using StorageManager for Android R (API 30) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumes = storageManager.storageVolumes
            for (storageVolume in storageVolumes) {
                if (storageVolume.isRemovable) {
                    val path = storageVolume.directory?.absolutePath
                    if (path != null) return path
                }
            }
        }

        // Fallback: Check external files dirs for older versions or if StorageManager failed
        val externalFilesDirs = context.getExternalFilesDirs(null)
        val primaryStorage = Environment.getExternalStorageDirectory()

        for (dir in externalFilesDirs) {
            if (dir == null) continue
            val path = dir.absolutePath
            // If path is on primary storage, skip
            if (path.startsWith(primaryStorage.absolutePath)) continue

            val match = Regex("^/storage/[^/]+").find(path)
            if (match != null) {
                return match.value
            }
        }
        return null
    }
}
