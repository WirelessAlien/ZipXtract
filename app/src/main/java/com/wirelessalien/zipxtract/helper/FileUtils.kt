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
import android.util.Log
import com.wirelessalien.zipxtract.model.DirectoryInfo
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object FileUtils {

    fun isInternalPath(context: Context, path: String): Boolean {
        val filesDir = context.filesDir.absolutePath
        val cacheDir = context.cacheDir.absolutePath
        return path.startsWith(filesDir) || path.startsWith(cacheDir)
    }

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

    /**
     * Moves a file or directory to a destination.
     * Tries to use renameTo first (fast move), and falls back to copy-delete (slow move).
     */
    fun smartMove(
        source: File,
        destination: File,
        overwrite: Boolean = false,
        progressCallback: (Int) -> Unit,
        scanCallback: ((File, File) -> Unit)? = null
    ): Boolean {
        if (overwrite && destination.exists()) {
            destination.deleteRecursively()
        }

        // Try fast move (rename)
        if (source.renameTo(destination)) {
            if (destination.isDirectory) {
                // If it was a directory, we need to traverse it to count files and report paths
                walkDirectoryAfterMove(source, destination, destination, progressCallback, scanCallback)
            } else {
                progressCallback(1)
                scanCallback?.invoke(source, destination)
            }
            return true
        }

        // Fallback to copy + delete
        return moveRecursively(source, destination, progressCallback, scanCallback)
    }

    /**
     * Copies a file or directory recursively using a larger buffer.
     */
    fun copyRecursively(
        source: File,
        destination: File,
        overwrite: Boolean = false,
        progressCallback: (Int) -> Unit,
        scanCallback: ((File) -> Unit)? = null
    ): Boolean {
        if (overwrite && destination.exists()) {
            destination.deleteRecursively()
        }

        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) {
                return false
            }
            var success = true
            source.listFiles()?.forEach { child ->
                val destChild = File(destination, child.name)
                if (!copyRecursively(child, destChild, false, progressCallback, scanCallback)) {
                    success = false
                }
            }
            return success
        } else {
            try {
                fastCopy(source, destination)
                scanCallback?.invoke(destination)
                progressCallback(1)
                return true
            } catch (e: Exception) {
                Log.e("FileUtils", "Copy failed", e)
                return false
            }
        }
    }

    private fun walkDirectoryAfterMove(
        originalSource: File,
        destRoot: File,
        currentFile: File,
        progressCallback: (Int) -> Unit,
        scanCallback: ((File, File) -> Unit)?
    ) {
        if (currentFile.isDirectory) {
            currentFile.listFiles()?.forEach { child ->
                walkDirectoryAfterMove(originalSource, destRoot, child, progressCallback, scanCallback)
            }
        } else {
            progressCallback(1)
            if (scanCallback != null) {
                val relativePath = currentFile.toRelativeString(destRoot)
                val sourceFile = File(originalSource, relativePath)
                scanCallback(sourceFile, currentFile)
            }
        }
    }

    private fun moveRecursively(
        source: File,
        destination: File,
        progressCallback: (Int) -> Unit,
        scanCallback: ((File, File) -> Unit)?
    ): Boolean {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) {
                return false
            }
            var success = true
            source.listFiles()?.forEach { child ->
                val destChild = File(destination, child.name)
                if (!moveRecursively(child, destChild, progressCallback, scanCallback)) {
                    success = false
                }
            }
            if (success) {
                source.delete()
            }
            return success
        } else {
            try {
                // Use optimized copy
                fastCopy(source, destination)
                scanCallback?.invoke(source, destination)
                source.delete()
                progressCallback(1)
                return true
            } catch (e: Exception) {
                Log.e("FileUtils", "Move failed", e)
                return false
            }
        }
    }

    fun fastCopy(source: File, destination: File) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                copyStream(input, output)
            }
        }
    }

    fun copyStream(input: InputStream, output: OutputStream) {
        input.copyTo(output, bufferSize = 64 * 1024)
    }

    fun countTotalFiles(file: File): Int {
        if (file.isDirectory) {
            var count = 0
            file.listFiles()?.forEach { count += countTotalFiles(it) }
            return count
        }
        return 1
    }
}
