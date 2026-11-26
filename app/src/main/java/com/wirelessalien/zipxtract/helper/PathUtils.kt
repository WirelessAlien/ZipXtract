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
import android.view.ViewGroup
import android.widget.ImageView
import com.wirelessalien.zipxtract.R

object PathUtils {

    /**
     * Creates a subtle chevron separator view for breadcrumb path segments.
     */
    fun createBreadcrumbSeparator(context: Context): ImageView {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            scaleType = ImageView.ScaleType.CENTER
            alpha = 0.6f
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            val density = context.resources.displayMetrics.density
            val width = (18 * density).toInt()
            val height = (48 * density).toInt()
            layoutParams = ViewGroup.LayoutParams(width, height)
        }
    }

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
