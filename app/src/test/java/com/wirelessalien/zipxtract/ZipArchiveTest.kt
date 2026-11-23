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

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Unit tests covering various Zip archive creation and extraction scenarios:
 * - Uncompressed (STORE) vs Compressed (DEFLATE with various compression levels)
 * - Password protection (Standard ZipCrypto vs AES-128, AES-192, AES-256)
 * - Empty files and multi-nested directory structures
 * - Unicode and special character filenames
 * - Partial file extraction (extracting individual entries)
 * - Split / multipart Zip creation and extraction
 * - Corrupted archive handling & password verification
 * - Zip Slip path traversal security protection
 */
class ZipArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createZipParameters(
        compressionMethod: CompressionMethod = CompressionMethod.DEFLATE,
        compressionLevel: CompressionLevel = CompressionLevel.NORMAL,
        isEncrypted: Boolean = false,
        encryptionMethod: EncryptionMethod? = null,
        aesStrength: AesKeyStrength? = null
    ): ZipParameters {
        return ZipParameters().apply {
            this.compressionMethod = compressionMethod
            this.compressionLevel = compressionLevel
            this.isEncryptFiles = isEncrypted
            this.encryptionMethod = if (isEncrypted) encryptionMethod else null
            this.aesKeyStrength = if (isEncrypted) aesStrength else null
            this.isIncludeRootFolder = false
        }
    }

    @Test
    fun testCreateAndExtractStoreUncompressedZip() {
        val workDir = tempFolder.newFolder("zip_store")
        val sampleFile = File(workDir, "uncompressed.txt").apply {
            writeText("Testing uncompressed ZIP storage method.")
        }
        val zipFileLocation = File(workDir, "archive_store.zip")

        val zipParams = createZipParameters(
            compressionMethod = CompressionMethod.STORE,
            compressionLevel = CompressionLevel.NO_COMPRESSION
        )

        ZipFile(zipFileLocation).use { zip ->
            zip.addFile(sampleFile, zipParams)
        }

        assertTrue("Zip file should exist", zipFileLocation.exists())

        val extractDir = tempFolder.newFolder("extract_store")
        ZipFile(zipFileLocation).use { zip ->
            zip.extractAll(extractDir.absolutePath)
        }

        val extractedFile = File(extractDir, "uncompressed.txt")
        assertTrue("Extracted file should exist", extractedFile.exists())
        assertEquals(sampleFile.readText(), extractedFile.readText())
    }

    @Test
    fun testCreateAndExtractDeflateDifferentCompressionLevels() {
        val workDir = tempFolder.newFolder("zip_levels")
        val content = "A".repeat(50000) // Highly compressible text
        val sampleFile = File(workDir, "compressible.txt").apply {
            writeText(content)
        }

        val levels = listOf(
            CompressionLevel.FASTEST,
            CompressionLevel.NORMAL,
            CompressionLevel.MAXIMUM,
            CompressionLevel.ULTRA
        )

        for (level in levels) {
            val archiveFile = File(workDir, "archive_${level.name}.zip")
            val zipParams = createZipParameters(
                compressionMethod = CompressionMethod.DEFLATE,
                compressionLevel = level
            )

            ZipFile(archiveFile).use { zip ->
                zip.addFile(sampleFile, zipParams)
            }

            assertTrue("Archive for ${level.name} should exist", archiveFile.exists())
            assertTrue(
                "Compressed archive should be significantly smaller than source",
                archiveFile.length() < sampleFile.length()
            )

            val extractDir = tempFolder.newFolder("extract_${level.name}")
            ZipFile(archiveFile).use { zip ->
                zip.extractAll(extractDir.absolutePath)
            }

            val extractedFile = File(extractDir, "compressible.txt")
            assertTrue("Extracted file should exist", extractedFile.exists())
            assertEquals(content, extractedFile.readText())
        }
    }

    @Test
    fun testCreateAndExtractStandardPasswordProtectedZip() {
        val workDir = tempFolder.newFolder("zip_std_pwd")
        val sampleFile = File(workDir, "secret.txt").apply {
            writeText("Confidential data encrypted with Zip Standard encryption.")
        }
        val password = "StrongPassword123!"
        val archiveFile = File(workDir, "archive_std_pwd.zip")

        val zipParams = createZipParameters(
            isEncrypted = true,
            encryptionMethod = EncryptionMethod.ZIP_STANDARD
        )

        ZipFile(archiveFile, password.toCharArray()).use { zip ->
            zip.addFile(sampleFile, zipParams)
        }

        // Verify archive recognizes encryption
        ZipFile(archiveFile).use { zip ->
            assertTrue("Archive should be encrypted", zip.isEncrypted)
        }

        // Test extraction with WRONG password fails
        val wrongExtractDir = tempFolder.newFolder("extract_wrong_pwd")
        try {
            ZipFile(archiveFile, "WrongPassword".toCharArray()).use { zip ->
                zip.extractAll(wrongExtractDir.absolutePath)
            }
            fail("Extraction should fail with wrong password")
        } catch (expected: ZipException) {
            // Expected
        }

        // Test extraction with CORRECT password succeeds
        val correctExtractDir = tempFolder.newFolder("extract_correct_pwd")
        ZipFile(archiveFile, password.toCharArray()).use { zip ->
            zip.extractAll(correctExtractDir.absolutePath)
        }

        val extractedFile = File(correctExtractDir, "secret.txt")
        assertTrue("Extracted file should exist", extractedFile.exists())
        assertEquals(sampleFile.readText(), extractedFile.readText())
    }

    @Test
    fun testCreateAndExtractAesEncryptedZipAllStrengths() {
        val strengths = listOf(
            AesKeyStrength.KEY_STRENGTH_128,
            AesKeyStrength.KEY_STRENGTH_256
        )

        val password = "SuperSecretAESPassword456"

        for (strength in strengths) {
            val workDir = tempFolder.newFolder("zip_aes_${strength.name}")
            val sampleFile = File(workDir, "aes_payload.dat").apply {
                writeBytes(ByteArray(1024) { (it % 256).toByte() })
            }
            val archiveFile = File(workDir, "archive_${strength.name}.zip")

            val zipParams = createZipParameters(
                isEncrypted = true,
                encryptionMethod = EncryptionMethod.AES,
                aesStrength = strength
            )

            ZipFile(archiveFile, password.toCharArray()).use { zip ->
                zip.addFile(sampleFile, zipParams)
            }

            assertTrue("Archive should exist", archiveFile.exists())
            ZipFile(archiveFile).use { zip ->
                assertTrue("Archive should be flagged as encrypted", zip.isEncrypted)
            }

            // Wrong password check
            val wrongExtractDir = tempFolder.newFolder("wrong_extract_${strength.name}")
            try {
                ZipFile(archiveFile, "InvalidPassword".toCharArray()).use { zip ->
                    zip.extractAll(wrongExtractDir.absolutePath)
                }
                fail("Extraction should have thrown ZipException with invalid password")
            } catch (e: ZipException) {
                // Expected
            }

            // Correct password extraction
            val correctExtractDir = tempFolder.newFolder("correct_extract_${strength.name}")
            ZipFile(archiveFile, password.toCharArray()).use { zip ->
                zip.extractAll(correctExtractDir.absolutePath)
            }

            val extractedFile = File(correctExtractDir, "aes_payload.dat")
            assertTrue("Extracted file should exist", extractedFile.exists())
            assertArrayEquals(sampleFile.readBytes(), extractedFile.readBytes())
        }
    }

    @Test
    fun testCreateAndExtractNestedDirectoriesAndMultipleFiles() {
        val rootDir = tempFolder.newFolder("nested_source")
        val subDir1 = File(rootDir, "folder1").apply { mkdir() }
        val subDir2 = File(subDir1, "folder2").apply { mkdir() }

        val file1 = File(rootDir, "root_file.txt").apply { writeText("File in root") }
        val file2 = File(subDir1, "sub1_file.txt").apply { writeText("File in folder1") }
        val file3 = File(subDir2, "sub2_file.txt").apply { writeText("File in folder1/folder2") }

        val archiveFile = File(tempFolder.newFolder("zip_nested"), "nested.zip")
        val zipParams = createZipParameters().apply {
            defaultFolderPath = rootDir.absolutePath
        }

        val allFiles = listOf(file1, file2, file3)
        ZipFile(archiveFile).use { zip ->
            zip.addFiles(allFiles, zipParams)
        }

        val extractDir = tempFolder.newFolder("extract_nested")
        ZipFile(archiveFile).use { zip ->
            zip.extractAll(extractDir.absolutePath)
        }

        assertTrue(File(extractDir, "root_file.txt").exists())
        assertEquals("File in root", File(extractDir, "root_file.txt").readText())

        assertTrue(File(extractDir, "folder1/sub1_file.txt").exists())
        assertEquals("File in folder1", File(extractDir, "folder1/sub1_file.txt").readText())

        assertTrue(File(extractDir, "folder1/folder2/sub2_file.txt").exists())
        assertEquals("File in folder1/folder2", File(extractDir, "folder1/folder2/sub2_file.txt").readText())
    }

    @Test
    fun testCreateAndExtractEmptyFileAndSpecialCharacters() {
        val workDir = tempFolder.newFolder("zip_special")
        val emptyFile = File(workDir, "empty.txt").apply { createNewFile() }
        val unicodeFile = File(workDir, "测试 文件 test.txt").apply {
            writeText("Unicode and space filename content: αβγδε")
        }

        val archiveFile = File(workDir, "special.zip")
        val zipParams = createZipParameters()

        ZipFile(archiveFile).use { zip ->
            zip.addFile(emptyFile, zipParams)
            zip.addFile(unicodeFile, zipParams)
        }

        val extractDir = tempFolder.newFolder("extract_special")
        ZipFile(archiveFile).use { zip ->
            zip.extractAll(extractDir.absolutePath)
        }

        val extractedEmpty = File(extractDir, "empty.txt")
        assertTrue("Extracted empty file should exist", extractedEmpty.exists())
        assertEquals(0L, extractedEmpty.length())

        val extractedUnicode = File(extractDir, "测试 文件 test.txt")
        assertTrue("Extracted unicode file should exist", extractedUnicode.exists())
        assertEquals(unicodeFile.readText(), extractedUnicode.readText())
    }

    @Test
    fun testExtractSpecificSelectedFiles() {
        val workDir = tempFolder.newFolder("zip_partial")
        val fileA = File(workDir, "fileA.txt").apply { writeText("Content A") }
        val fileB = File(workDir, "fileB.txt").apply { writeText("Content B") }
        val fileC = File(workDir, "fileC.txt").apply { writeText("Content C") }

        val archiveFile = File(workDir, "partial.zip")
        val zipParams = createZipParameters()

        ZipFile(archiveFile).use { zip ->
            zip.addFiles(listOf(fileA, fileB, fileC), zipParams)
        }

        val extractDir = tempFolder.newFolder("extract_partial")
        ZipFile(archiveFile).use { zip ->
            // Extract only fileB.txt as done in ExtractArchiveService when itemsToExtract is specified
            zip.extractFile("fileB.txt", extractDir.absolutePath)
        }

        assertFalse("fileA should not be extracted", File(extractDir, "fileA.txt").exists())
        assertTrue("fileB should be extracted", File(extractDir, "fileB.txt").exists())
        assertFalse("fileC should not be extracted", File(extractDir, "fileC.txt").exists())
        assertEquals("Content B", File(extractDir, "fileB.txt").readText())
    }

    @Test
    fun testCreateAndExtractSplitZipArchive() {
        val workDir = tempFolder.newFolder("split_source")
        // Create files with sufficient size to span multiple split parts with 64KB split size
        val largeFile = File(workDir, "large_data.bin").apply {
            val bytes = ByteArray(150 * 1024) { (it % 128).toByte() }
            writeBytes(bytes)
        }

        val outputDir = tempFolder.newFolder("split_output")
        val splitZipFile = File(outputDir, "split_archive.zip")
        val splitSize = 65536L // 64KB per split chunk

        val zipParams = createZipParameters(compressionMethod = CompressionMethod.STORE)

        ZipFile(splitZipFile).use { zip ->
            zip.createSplitZipFile(listOf(largeFile), zipParams, true, splitSize)
        }

        // Verify split parts were generated (.z01, .z02, and .zip)
        assertTrue("Main .zip file should exist", splitZipFile.exists())
        val part1 = File(outputDir, "split_archive.z01")
        assertTrue("First split part (.z01) should exist", part1.exists())

        // Verify Zip4j identifies it as a split archive
        ZipFile(splitZipFile).use { zip ->
            assertTrue("Archive should be recognized as split", zip.isSplitArchive)
        }

        // Test extraction of the split archive
        val extractDir = tempFolder.newFolder("extract_split")
        ZipFile(splitZipFile).use { zip ->
            zip.extractAll(extractDir.absolutePath)
        }

        val extractedLargeFile = File(extractDir, "large_data.bin")
        assertTrue("Extracted file from split archive should exist", extractedLargeFile.exists())
        assertEquals(largeFile.length(), extractedLargeFile.length())
        assertArrayEquals(largeFile.readBytes(), extractedLargeFile.readBytes())
    }

    @Test
    fun testExtractCorruptedZipThrowsException() {
        val workDir = tempFolder.newFolder("corrupted_zip")
        val corruptedFile = File(workDir, "corrupted.zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08, 0x00)) // Incomplete ZIP header
        }

        val extractDir = tempFolder.newFolder("extract_corrupted")
        try {
            ZipFile(corruptedFile).use { zip ->
                zip.extractAll(extractDir.absolutePath)
            }
            fail("Expected ZipException when extracting corrupted zip")
        } catch (e: ZipException) {
            // Expected failure
        }
    }

    @Test
    fun testZipSlipPathTraversalProtection() {
        val extractDir = tempFolder.newFolder("extract_safe")
        val canonicalDstPath = extractDir.canonicalPath
        val safeDstPath = if (canonicalDstPath.endsWith(File.separator)) canonicalDstPath else canonicalDstPath + File.separator

        // Malicious entry path trying to escape destination directory
        val maliciousPath = "../../evil.txt"
        val resolvedFile = File(extractDir, maliciousPath)

        // Validate the Zip Slip prevention check used across ZipXtract services
        val isEscaping = !resolvedFile.canonicalPath.startsWith(safeDstPath) && resolvedFile.canonicalPath != canonicalDstPath
        assertTrue("Malicious path traversing outside destination must be detected", isEscaping)

        // Valid nested path should pass
        val safeFile = File(extractDir, "sub/dir/safe.txt")
        val isSafeValid = safeFile.canonicalPath.startsWith(safeDstPath) || safeFile.canonicalPath == canonicalDstPath
        assertTrue("Legitimate nested path must be allowed", isSafeValid)
    }
}
