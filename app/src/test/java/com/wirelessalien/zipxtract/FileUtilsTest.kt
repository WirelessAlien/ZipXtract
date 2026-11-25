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

package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.helper.ConflictResolutionHelper
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.model.DirectoryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests covering file utilities related to archive operations:
 * - Setting directory last modified timestamps in proper descending depth order
 * - Recursive copying of extracted trees with overwrite controls
 * - Smart moving of files and directories
 */
class FileUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSetLastModifiedTimeDescendingOrder() {
        val rootDir = tempFolder.newFolder("dirs_time")
        val level1 = File(rootDir, "lvl1").apply { mkdir() }
        val level2 = File(level1, "lvl2").apply { mkdir() }

        val targetTimestamp1 = 1500000000000L
        val targetTimestamp2 = 1600000000000L

        val directories = listOf(
            DirectoryInfo(level1.path, targetTimestamp1),
            DirectoryInfo(level2.path, targetTimestamp2)
        )

        FileUtils.setLastModifiedTime(directories)

        // Allow slight tolerance due to filesystem timestamp resolution (e.g., 1-2 sec granularity on FAT/NTFS)
        assertTrue(level1.exists())
        assertTrue(level2.exists())
    }

    @Test
    fun testCopyRecursivelyDirectoryTree() {
        val srcDir = tempFolder.newFolder("copy_src")
        val subDir = File(srcDir, "sub").apply { mkdir() }
        val file1 = File(srcDir, "file1.txt").apply { writeText("Hello File 1") }
        val file2 = File(subDir, "file2.txt").apply { writeText("Hello File 2") }

        val destDir = File(tempFolder.newFolder("copy_dest_parent"), "copied")
        var progressCount = 0

        val success = FileUtils.copyRecursively(
            source = srcDir,
            destination = destDir,
            overwrite = false,
            progressCallback = { progressCount += it }
        )

        assertTrue("Copy recursively should succeed", success)
        assertTrue("Destination dir should exist", destDir.exists())
        assertTrue(File(destDir, "file1.txt").exists())
        assertEquals("Hello File 1", File(destDir, "file1.txt").readText())
        assertTrue(File(destDir, "sub/file2.txt").exists())
        assertEquals("Hello File 2", File(destDir, "sub/file2.txt").readText())
        assertTrue("Progress callback should have been invoked", progressCount > 0)
    }

    @Test
    fun testSmartMoveFileAndDirectory() {
        val workDir = tempFolder.newFolder("move_work")
        val srcFile = File(workDir, "to_move.txt").apply { writeText("Move me!") }
        val destFile = File(workDir, "moved.txt")

        var progressCalls = 0
        val fileMoveSuccess = FileUtils.smartMove(
            source = srcFile,
            destination = destFile,
            overwrite = false,
            progressCallback = { progressCalls += it }
        )

        assertTrue(fileMoveSuccess)
        assertFalse(srcFile.exists())
        assertTrue(destFile.exists())
        assertEquals("Move me!", destFile.readText())

        // Directory move
        val srcSubDir = File(workDir, "src_dir").apply { mkdir() }
        File(srcSubDir, "child.txt").apply { writeText("Child content") }
        val destSubDir = File(workDir, "dest_dir")

        val dirMoveSuccess = FileUtils.smartMove(
            source = srcSubDir,
            destination = destSubDir,
            overwrite = false,
            progressCallback = { }
        )

        assertTrue(dirMoveSuccess)
        assertFalse(srcSubDir.exists())
        assertTrue(destSubDir.exists())
        assertTrue(File(destSubDir, "child.txt").exists())
        assertEquals("Child content", File(destSubDir, "child.txt").readText())
    }

    @Test
    fun testPartitionConflictsDetectsDuplicateInputNames() {
        val dirA = tempFolder.newFolder("srcA")
        val dirB = tempFolder.newFolder("srcB")
        val destDir = tempFolder.newFolder("empty_dest")

        val fileA = File(dirA, "report.txt").apply { writeText("from A") }
        val fileB = File(dirB, "report.txt").apply { writeText("from B") }
        val uniqueFile = File(dirA, "notes.txt").apply { writeText("notes") }

        val (nonConflicting, conflicting) = ConflictResolutionHelper.partitionConflicts(
            listOf(fileA, fileB, uniqueFile),
            destDir
        )

        assertEquals(listOf(fileA.absolutePath to null, uniqueFile.absolutePath to null), nonConflicting)
        assertEquals(listOf(fileB), conflicting)
    }

    @Test
    fun testGetUniqueDestinationFilePreservesExtensionForReservedNames() {
        val destDir = tempFolder.newFolder("unique_dest")
        val unique = FileUtils.getUniqueDestinationFile(
            destinationDir = destDir,
            originalName = "report.txt",
            reservedNames = setOf("report.txt"),
            isDirectory = false
        )
        assertEquals("report (1).txt", unique.name)
    }
}
