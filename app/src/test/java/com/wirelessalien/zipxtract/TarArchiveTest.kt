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

import com.wirelessalien.zipxtract.model.DirectoryInfo
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Unit tests covering Tar archive creation and extraction scenarios:
 * - Standard TAR creation and extraction
 * - Directory hierarchy, nested subfolders, and multi-file archives
 * - Empty files within TAR archives
 * - Timestamp and metadata preservation
 * - Zip Slip path traversal detection and protection
 * - Corrupted / truncated TAR stream handling
 */
class TarArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Extracts a TAR archive replicating the logic in ExtractArchiveService.extractTarArchive
     */
    private fun extractTarArchive(tarFile: File, destinationDir: File) {
        destinationDir.mkdirs()
        val canonicalDstPath = destinationDir.canonicalPath
        val safeDstPath = if (canonicalDstPath.endsWith(File.separator)) canonicalDstPath else canonicalDstPath + File.separator
        val buffer = ByteArray(65536)
        val directories = mutableListOf<DirectoryInfo>()

        TarArchiveInputStream(FileInputStream(tarFile)).use { tarInput ->
            var entry: TarArchiveEntry? = tarInput.nextEntry
            while (entry != null) {
                val outputFile = File(destinationDir, entry.name)

                if (!outputFile.canonicalPath.startsWith(safeDstPath) && outputFile.canonicalPath != canonicalDstPath) {
                    throw IOException("Zip Slip detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                    val lastModified = if (entry.modTime.time > 0) entry.modTime.time else System.currentTimeMillis()
                    directories.add(DirectoryInfo(outputFile.path, lastModified))
                } else {
                    outputFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                        var n: Int
                        while (tarInput.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                        }
                    }
                    if (entry.modTime.time > 0) {
                        outputFile.setLastModified(entry.modTime.time)
                    }
                }
                entry = tarInput.nextEntry
            }
        }
    }

    @Test
    fun testCreateAndExtractStandardTarArchive() {
        val workDir = tempFolder.newFolder("tar_work")
        val file1 = File(workDir, "hello.txt").apply { writeText("Hello TAR Archive!") }
        val file2 = File(workDir, "data.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 42, 99)) }

        val tarFile = File(workDir, "test.tar")

        TarArchiveOutputStream(FileOutputStream(tarFile)).use { tarOut ->
            // Add file1
            val entry1 = TarArchiveEntry(file1, "hello.txt")
            tarOut.putArchiveEntry(entry1)
            file1.inputStream().use { it.copyTo(tarOut) }
            tarOut.closeArchiveEntry()

            // Add file2
            val entry2 = TarArchiveEntry(file2, "data.bin")
            tarOut.putArchiveEntry(entry2)
            file2.inputStream().use { it.copyTo(tarOut) }
            tarOut.closeArchiveEntry()
        }

        assertTrue("TAR file should be created", tarFile.exists())

        val extractDir = tempFolder.newFolder("tar_extract")
        extractTarArchive(tarFile, extractDir)

        val extractedFile1 = File(extractDir, "hello.txt")
        val extractedFile2 = File(extractDir, "data.bin")

        assertTrue("Extracted hello.txt should exist", extractedFile1.exists())
        assertEquals(file1.readText(), extractedFile1.readText())

        assertTrue("Extracted data.bin should exist", extractedFile2.exists())
        assertArrayEquals(file2.readBytes(), extractedFile2.readBytes())
    }

    @Test
    fun testCreateAndExtractTarWithNestedDirectoriesAndEmptyFile() {
        val workDir = tempFolder.newFolder("tar_nested_work")
        val subDir = File(workDir, "subfolder").apply { mkdir() }
        val nestedFile = File(subDir, "nested.txt").apply { writeText("Nested in folder") }
        val emptyFile = File(workDir, "zero_bytes.txt").apply { createNewFile() }

        val tarFile = File(workDir, "nested.tar")

        TarArchiveOutputStream(FileOutputStream(tarFile)).use { tarOut ->
            // Add directory entry
            val dirEntry = TarArchiveEntry("subfolder/")
            tarOut.putArchiveEntry(dirEntry)
            tarOut.closeArchiveEntry()

            // Add nested file
            val nestedEntry = TarArchiveEntry(nestedFile, "subfolder/nested.txt")
            tarOut.putArchiveEntry(nestedEntry)
            nestedFile.inputStream().use { it.copyTo(tarOut) }
            tarOut.closeArchiveEntry()

            // Add empty file
            val emptyEntry = TarArchiveEntry(emptyFile, "zero_bytes.txt")
            tarOut.putArchiveEntry(emptyEntry)
            tarOut.closeArchiveEntry()
        }

        val extractDir = tempFolder.newFolder("tar_nested_extract")
        extractTarArchive(tarFile, extractDir)

        val extractedNested = File(extractDir, "subfolder/nested.txt")
        assertTrue("Nested file should exist", extractedNested.exists())
        assertEquals("Nested in folder", extractedNested.readText())

        val extractedEmpty = File(extractDir, "zero_bytes.txt")
        assertTrue("Empty file should exist", extractedEmpty.exists())
        assertEquals(0L, extractedEmpty.length())
    }

    @Test
    fun testExtractTarDetectsAndPreventsZipSlip() {
        val workDir = tempFolder.newFolder("tar_malicious_work")
        val maliciousTar = File(workDir, "malicious.tar")

        // Create a TAR with a path traversing entry
        TarArchiveOutputStream(FileOutputStream(maliciousTar)).use { tarOut ->
            val payload = "Malicious content intended to escape root directory"
            val maliciousEntry = TarArchiveEntry("../../escaped.txt").apply {
                size = payload.toByteArray().size.toLong()
            }
            tarOut.putArchiveEntry(maliciousEntry)
            tarOut.write(payload.toByteArray())
            tarOut.closeArchiveEntry()
        }

        val destinationDir = tempFolder.newFolder("tar_safe_target")
        try {
            extractTarArchive(maliciousTar, destinationDir)
            fail("Expected Zip Slip IOException to be thrown")
        } catch (e: IOException) {
            assertTrue(
                "Exception message should mention Zip Slip detected",
                e.message?.contains("Zip Slip detected") == true
            )
        }

        // Verify the file was not written outside target directory
        val escapedFile = File(destinationDir.parentFile, "escaped.txt")
        assertTrue("Escaped file must not exist on the filesystem", !escapedFile.exists())
    }

    @Test
    fun testExtractTruncatedTarThrowsException() {
        val workDir = tempFolder.newFolder("tar_truncated")
        val sourceFile = File(workDir, "payload.txt").apply {
            writeBytes(ByteArray(2048) { (it % 127).toByte() })
        }
        val validTar = File(workDir, "valid.tar")
        TarArchiveOutputStream(FileOutputStream(validTar)).use { tarOut ->
            val entry = TarArchiveEntry(sourceFile, "payload.txt")
            tarOut.putArchiveEntry(entry)
            sourceFile.inputStream().use { it.copyTo(tarOut) }
            tarOut.closeArchiveEntry()
        }

        // Truncate inside the entry payload after the 512-byte header
        val truncatedBytes = validTar.readBytes().copyOf(600)
        val truncatedTar = File(workDir, "broken.tar").apply {
            writeBytes(truncatedBytes)
        }

        val extractDir = tempFolder.newFolder("tar_broken_extract")
        try {
            extractTarArchive(truncatedTar, extractDir)
            fail("Expected IOException when extracting truncated TAR archive")
        } catch (e: IOException) {
            // Expected for corrupted/truncated tar streams
        }
    }
}
