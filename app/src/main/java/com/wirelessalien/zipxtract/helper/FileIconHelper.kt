/*
 *  Copyright (C) 2026  WirelessAlien <https://github.com/WirelessAlien>
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
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView
import com.wirelessalien.zipxtract.R
import java.io.File
import java.util.Locale

object FileIconHelper {

    enum class FileCategory {
        DIRECTORY,
        ARCHIVE,
        IMAGE,
        AUDIO,
        VIDEO,
        DOCUMENT,
        CODE_INSTALLER,
        GENERIC
    }

    private val archiveExtensions = setOf(
        "rar", "r00", "001", "7z", "7z.001", "zip", "tar", "gz", "bz2", "xz",
        "lz4", "lzma", "sz", "tgz", "tbz", "tbz2", "txz", "zst", "zstd",
        "iso", "cab", "arj", "lzh", "ace", "cpio", "jar", "war"
    )

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "svgz", "ico",
        "heic", "heif", "avif", "tif", "tiff", "raw", "cr2", "nef"
    )

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma",
        "mid", "midi", "amr", "awb", "aif", "aiff"
    )

    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "3gp", "ts", "wmv",
        "mpeg", "mpg", "m4v", "ogv"
    )

    private val codeAndInstallerExtensions = setOf(
        "apk", "xapk", "apks", "aab", "exe", "msi", "bat", "cmd",
        "kt", "kts", "java", "py", "pyw", "js", "jsx", "ts", "tsx",
        "html", "htm", "css", "scss", "sass", "json", "xml", "yaml", "yml",
        "sh", "bash", "zsh", "c", "cpp", "cxx", "cc", "h", "hpp", "hxx",
        "cs", "php", "rb", "go", "rs", "swift", "sql", "toml", "properties",
        "gradle", "lua", "r", "dart", "asm"
    )

    private val documentExtensions = setOf(
        "pdf", "txt", "text", "doc", "docx", "dot", "dotx", "rtf",
        "xls", "xlsx", "xlt", "xltx", "csv", "tsv",
        "ppt", "pptx", "pot", "potx", "pps", "ppsx",
        "odt", "ods", "odp", "odf", "epub", "mobi", "md", "markdown",
        "sqlite", "sqlite3", "db", "log", "chm"
    )

    fun getFileCategory(name: String, isDirectory: Boolean): FileCategory {
        if (isDirectory) return FileCategory.DIRECTORY

        val lower = name.lowercase(Locale.getDefault())

        // Compound archive extensions check first
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.xz") ||
            lower.endsWith(".tar.lz4") || lower.endsWith(".tar.sz") || lower.endsWith(".tar.zst") ||
            lower.endsWith(".tar.zstd") || lower.endsWith(".tar.lzma") || lower.endsWith(".7z.001") ||
            lower.endsWith(".tar.br")
        ) {
            return FileCategory.ARCHIVE
        }

        val extension = lower.substringAfterLast('.', "")
        if (extension.isEmpty()) return FileCategory.GENERIC

        return when {
            archiveExtensions.contains(extension) -> FileCategory.ARCHIVE
            imageExtensions.contains(extension) -> FileCategory.IMAGE
            audioExtensions.contains(extension) -> FileCategory.AUDIO
            videoExtensions.contains(extension) -> FileCategory.VIDEO
            codeAndInstallerExtensions.contains(extension) -> FileCategory.CODE_INSTALLER
            documentExtensions.contains(extension) -> FileCategory.DOCUMENT
            else -> FileCategory.GENERIC
        }
    }

    fun getDisplayExtension(name: String): String {
        val lower = name.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".tar.gz") -> "TAR.GZ"
            lower.endsWith(".tar.bz2") -> "TAR.BZ2"
            lower.endsWith(".tar.xz") -> "TAR.XZ"
            lower.endsWith(".tar.zst") -> "TAR.ZST"
            lower.endsWith(".7z.001") -> "7Z.001"
            else -> name.substringAfterLast('.', "").uppercase(Locale.getDefault())
        }
    }

    fun applyExtensionText(textView: TextView, extension: String, @ColorInt textColor: Int? = null) {
        textView.text = extension
        if (textColor != null) {
            textView.setTextColor(textColor)
        }
        textView.maxLines = 1
        textView.ellipsize = android.text.TextUtils.TruncateAt.END
        when {
            extension.length <= 3 -> textView.textSize = 12f
            extension.length == 4 -> textView.textSize = 11f
            extension.length == 5 -> textView.textSize = 9.5f
            extension.length == 6 -> textView.textSize = 8.5f
            else -> textView.textSize = 7.5f
        }
    }

    fun bindFileIcon(
        cardView: MaterialCardView,
        fileIcon: ImageView,
        fileExtension: TextView,
        name: String,
        isDirectory: Boolean,
        file: File? = null
    ) {
        val context = cardView.context
        val category = getFileCategory(name, isDirectory)
        val density = context.resources.displayMetrics.density
        val defaultPaddingPx = (8 * density).toInt()

        if (category != FileCategory.IMAGE || file == null || !file.exists()) {
            try {
                Glide.with(context).clear(fileIcon)
            } catch (_: Exception) {}
            fileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        when (category) {
            FileCategory.DIRECTORY -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_folder_container)
                val onContainerColor = ContextCompat.getColor(context, R.color.file_on_folder_container)
                cardView.setCardBackgroundColor(containerColor)
                fileIcon.setImageResource(R.drawable.ic_folder)
                fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                fileIcon.visibility = View.VISIBLE
                fileExtension.visibility = View.GONE
            }

            FileCategory.ARCHIVE -> {
                val containerColor = resolveThemeColor(context, com.google.android.material.R.attr.colorPrimaryContainer)
                val onContainerColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer)
                cardView.setCardBackgroundColor(containerColor)
                fileIcon.setImageResource(R.drawable.ic_zip)
                fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                fileIcon.visibility = View.VISIBLE
                fileExtension.visibility = View.GONE
            }

            FileCategory.IMAGE -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_generic_container)
                cardView.setCardBackgroundColor(containerColor)

                if (file != null && file.exists()) {
                    fileIcon.imageTintList = null
                    fileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    fileIcon.setPadding(0, 0, 0, 0)

                    val imageLoadingOptions = RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override((48 * density).toInt(), (48 * density).toInt())
                        .format(DecodeFormat.PREFER_RGB_565)
                        .centerCrop()

                    val requestBuilder = Glide.with(context)
                        .asDrawable()
                        .apply(imageLoadingOptions)
                        .sizeMultiplier(0.25f)

                    Glide.with(context)
                        .load(file)
                        .apply(imageLoadingOptions)
                        .thumbnail(requestBuilder)
                        .into(fileIcon)

                    fileIcon.visibility = View.VISIBLE
                    fileExtension.visibility = View.GONE
                } else {
                    val onContainerColor = ContextCompat.getColor(context, R.color.file_on_generic_container)
                    fileIcon.setImageResource(R.drawable.ic_image)
                    fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                    fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                    fileIcon.visibility = View.VISIBLE
                    fileExtension.visibility = View.GONE
                }
            }

            FileCategory.AUDIO -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_audio_container)
                val onContainerColor = ContextCompat.getColor(context, R.color.file_on_audio_container)
                cardView.setCardBackgroundColor(containerColor)
                fileIcon.setImageResource(R.drawable.ic_audio)
                fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                fileIcon.visibility = View.VISIBLE
                fileExtension.visibility = View.GONE
            }

            FileCategory.VIDEO -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_video_container)
                val onContainerColor = ContextCompat.getColor(context, R.color.file_on_video_container)
                cardView.setCardBackgroundColor(containerColor)
                fileIcon.setImageResource(R.drawable.ic_video)
                fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                fileIcon.visibility = View.VISIBLE
                fileExtension.visibility = View.GONE
            }

            FileCategory.DOCUMENT -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_doc_container)
                val onContainerColor = ContextCompat.getColor(context, R.color.file_on_doc_container)
                cardView.setCardBackgroundColor(containerColor)
                val ext = getDisplayExtension(name)
                if (ext.isNotEmpty()) {
                    fileIcon.visibility = View.GONE
                    fileExtension.visibility = View.VISIBLE
                    applyExtensionText(fileExtension, ext, onContainerColor)
                } else {
                    fileIcon.setImageResource(R.drawable.ic_document)
                    fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                    fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                    fileIcon.visibility = View.VISIBLE
                    fileExtension.visibility = View.GONE
                }
            }

            FileCategory.CODE_INSTALLER -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_code_container)
                val onContainerColor = ContextCompat.getColor(context, R.color.file_on_code_container)
                cardView.setCardBackgroundColor(containerColor)
                val ext = getDisplayExtension(name)
                if (ext == "APK" || ext == "XAPK" || ext == "APKS") {
                    fileIcon.setImageResource(R.drawable.ic_apk)
                    fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                    fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                    fileIcon.visibility = View.VISIBLE
                    fileExtension.visibility = View.GONE
                } else if (ext.isNotEmpty()) {
                    fileIcon.visibility = View.GONE
                    fileExtension.visibility = View.VISIBLE
                    applyExtensionText(fileExtension, ext, onContainerColor)
                } else {
                    fileIcon.setImageResource(R.drawable.ic_code)
                    fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                    fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                    fileIcon.visibility = View.VISIBLE
                    fileExtension.visibility = View.GONE
                }
            }

            FileCategory.GENERIC -> {
                val containerColor = ContextCompat.getColor(context, R.color.file_generic_container)
                val onContainerColor = ContextCompat.getColor(context, R.color.file_on_generic_container)
                cardView.setCardBackgroundColor(containerColor)
                val ext = getDisplayExtension(name)
                if (ext.isNotEmpty()) {
                    fileIcon.visibility = View.GONE
                    fileExtension.visibility = View.VISIBLE
                    applyExtensionText(fileExtension, ext, onContainerColor)
                } else {
                    fileIcon.setImageResource(R.drawable.ic_file_present)
                    fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
                    fileIcon.setPadding(defaultPaddingPx, defaultPaddingPx, defaultPaddingPx, defaultPaddingPx)
                    fileIcon.visibility = View.VISIBLE
                    fileExtension.visibility = View.GONE
                }
            }
        }
    }

    @ColorInt
    private fun resolveThemeColor(context: Context, @AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            ContextCompat.getColor(context, typedValue.resourceId)
        }
    }
}
