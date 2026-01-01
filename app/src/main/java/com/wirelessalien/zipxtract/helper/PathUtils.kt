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
import android.os.Environment
import com.wirelessalien.zipxtract.R

object PathUtils {

    fun formatPath(path: String, context: Context): String {
        val internalStorage = Environment.getExternalStorageDirectory().absolutePath
        return if (path.startsWith(internalStorage)) {
            path.replaceFirst(internalStorage, context.getString(R.string.internal_storage))
        } else {
            if (path.startsWith("/storage/")) {
                val parts = path.split("/")
                if (parts.size > 2 && parts[1] == "storage" && parts[2] != "emulated") {
                    val sdCardId = parts[2]
                    path.replaceFirst("/storage/$sdCardId", context.getString(R.string.sd_card))
                } else {
                    path
                }
            } else {
                path
            }
        }
    }
}
