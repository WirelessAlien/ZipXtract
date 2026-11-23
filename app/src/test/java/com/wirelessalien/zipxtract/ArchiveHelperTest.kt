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

import com.wirelessalien.zipxtract.helper.EncryptionCheckHelper
import com.wirelessalien.zipxtract.helper.MultipartArchiveHelper
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests covering archive helper utilities:
 * - MultipartArchiveHelper recognition of multipart Zip, 7z, and RAR archives vs single-volume archives
 * - EncryptionCheckHelper detection of encrypted vs unencrypted archives across Zip variations
 */
class ArchiveHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMultipartZipDetection() {
        val workDir = tempFolder.newFolder("multipart_zip_detection")

        // 1. Standard single-volume ZIP (not split)
        val normalZip = File(workDir, "standard.zip")
        val sampleFile = File(workDir, "test.txt").apply { writeText("Hello") }
        ZipFile(normalZip).use { it.addFile(sampleFile) }
        assertFalse("Standard zip is not multipart", MultipartArchiveHelper.isMultipartZip(normalZip))

        // 2. Real split ZIP archive created with Zip4j
        val largeFile = File(workDir, "payload.dat").apply {
            writeBytes(ByteArray(150 * 1024) { 42 })
        }
        val splitZip = File(workDir, "split.zip")
        ZipFile(splitZip).use { zip ->
            val params = ZipParameters().apply { compressionMethod = CompressionMethod.STORE }
            zip.createSplitZipFile(listOf(largeFile), params, true, 65536L)
        }
        assertTrue("Real split .zip must be recognized as multipart zip", MultipartArchiveHelper.isMultipartZip(splitZip))

        // 3. Pattern-based filenames for split zip
        val z01File = File(workDir, "archive.z01")
        val z99File = File(workDir, "backup.z99")
        val zip001File = File(workDir, "data.zip.001")
        val zip003File = File(workDir, "data.zip.003")

        assertTrue(MultipartArchiveHelper.isMultipartZip(z01File))
        assertTrue(MultipartArchiveHelper.isMultipartZip(z99File))
        assertTrue(MultipartArchiveHelper.isMultipartZip(zip001File))
        assertTrue(MultipartArchiveHelper.isMultipartZip(zip003File))

        // Non-matching filenames
        assertFalse(MultipartArchiveHelper.isMultipartZip(File(workDir, "archive.zip.bak")))
        assertFalse(MultipartArchiveHelper.isMultipartZip(File(workDir, "archive.z1")))
    }

    @Test
    fun testMultipart7zDetection() {
        val workDir = tempFolder.newFolder("multipart_7z_detection")

        val part001 = File(workDir, "archive.7z.001")
        val part005 = File(workDir, "archive.7z.005")
        val regular7z = File(workDir, "archive.7z")
        val other001 = File(workDir, "archive.tar.001")

        assertTrue(MultipartArchiveHelper.isMultipart7z(part001))
        assertTrue(MultipartArchiveHelper.isMultipart7z(part005))
        assertFalse(MultipartArchiveHelper.isMultipart7z(regular7z))
        assertFalse(MultipartArchiveHelper.isMultipart7z(other001))
    }

    @Test
    fun testMultipartRarDetection() {
        val workDir = tempFolder.newFolder("multipart_rar_detection")

        val part1Rar = File(workDir, "movie.part1.rar")
        val part02Rar = File(workDir, "movie.part02.rar")
        val r00File = File(workDir, "game.r00")
        val r01File = File(workDir, "game.r01")
        val num001File = File(workDir, "archive.001")
        val standardRar = File(workDir, "archive.rar")

        assertTrue(MultipartArchiveHelper.isMultipartRar(part1Rar))
        assertTrue(MultipartArchiveHelper.isMultipartRar(part02Rar))
        assertTrue(MultipartArchiveHelper.isMultipartRar(r00File))
        assertTrue(MultipartArchiveHelper.isMultipartRar(r01File))
        assertTrue(MultipartArchiveHelper.isMultipartRar(num001File))
        assertFalse(MultipartArchiveHelper.isMultipartRar(standardRar))

        // Mutual exclusion: zip.001 and 7z.001 should NOT be classified as RAR
        val zip001 = File(workDir, "archive.zip.001")
        val sevenZ001 = File(workDir, "archive.7z.001")
        assertFalse("zip.001 is not a RAR", MultipartArchiveHelper.isMultipartRar(zip001))
        assertFalse("7z.001 is not a RAR", MultipartArchiveHelper.isMultipartRar(sevenZ001))
    }

    @Test
    fun testEncryptionCheckHelperForZip() {
        val workDir = tempFolder.newFolder("encryption_check")
        val sampleFile = File(workDir, "plain.txt").apply { writeText("Plain content") }

        // 1. Unencrypted Zip
        val plainZip = File(workDir, "unencrypted.zip")
        ZipFile(plainZip).use { it.addFile(sampleFile) }
        assertFalse("Unencrypted zip should return false", EncryptionCheckHelper.isEncrypted(plainZip))

        // 2. Standard ZipCrypto encrypted Zip
        val stdEncryptedZip = File(workDir, "std_encrypted.zip")
        val stdParams = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.ZIP_STANDARD
        }
        ZipFile(stdEncryptedZip, "password123".toCharArray()).use { it.addFile(sampleFile, stdParams) }
        assertTrue("Standard encrypted zip should return true", EncryptionCheckHelper.isEncrypted(stdEncryptedZip))

        // 3. AES-256 encrypted Zip
        val aesEncryptedZip = File(workDir, "aes_encrypted.zip")
        val aesParams = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        ZipFile(aesEncryptedZip, "password123".toCharArray()).use { it.addFile(sampleFile, aesParams) }
        assertTrue("AES encrypted zip should return true", EncryptionCheckHelper.isEncrypted(aesEncryptedZip))
    }
}
