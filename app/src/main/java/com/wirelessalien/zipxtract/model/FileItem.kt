package com.wirelessalien.zipxtract.model

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

data class FileItem(
    val file: File,
    val lastModified: Long,
    val size: Long,
    val isDirectory: Boolean
) {
    // Helper to create from File
    companion object {
        fun fromFile(file: File): FileItem {
            return FileItem(
                file = file,
                lastModified = file.lastModified(),
                size = file.length(),
                isDirectory = file.isDirectory
            )
        }

        @RequiresApi(Build.VERSION_CODES.O)
        fun fromPath(path: Path): FileItem {
            val file = path.toFile()
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            return FileItem(
                file = file,
                lastModified = attrs.lastModifiedTime().toMillis(),
                size = attrs.size(),
                isDirectory = attrs.isDirectory
            )
        }
    }
}
