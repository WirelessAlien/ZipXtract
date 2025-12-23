package com.wirelessalien.zipxtract.model

import java.io.File

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
    }
}
