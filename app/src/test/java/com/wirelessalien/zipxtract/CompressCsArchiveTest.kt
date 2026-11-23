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

import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.CompressorOutputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Unit tests covering single file compression and extraction (CS Archive):
 * - Gzip compression and decompression
 * - Bzip2 compression and decompression
 * - XZ compression and decompression
 * - Empty file compression and decompression
 * - Large binary payload compression integrity
 * - Corrupted compressed stream error handling
 */
class CompressCsArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val factory = CompressorStreamFactory()

    private fun compressFile(inputFile: File, outputFile: File, format: String) {
        BufferedInputStream(FileInputStream(inputFile)).use { input ->
            BufferedOutputStream(FileOutputStream(outputFile)).use { fileOut ->
                val cos: CompressorOutputStream<*> = factory.createCompressorOutputStream(format, fileOut)
                cos.use { compressorOut ->
                    input.copyTo(compressorOut)
                }
            }
        }
    }

    private fun decompressFile(compressedFile: File, outputFile: File) {
        BufferedInputStream(FileInputStream(compressedFile)).use { fileIn ->
            val cis: CompressorInputStream = factory.createCompressorInputStream(fileIn)
            cis.use { compressorIn ->
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    compressorIn.copyTo(output)
                }
            }
        }
    }

    @Test
    fun testGzipCompressAndDecompress() {
        val workDir = tempFolder.newFolder("gzip_test")
        val sourceFile = File(workDir, "sample.txt").apply {
            writeText("GZIP compression and decompression testing for ZipXtract single file archive.")
        }
        val compressedFile = File(workDir, "sample.txt.gz")
        val decompressedFile = File(workDir, "sample_restored.txt")

        compressFile(sourceFile, compressedFile, CompressorStreamFactory.GZIP)
        assertTrue("GZIP file should exist", compressedFile.exists())
        assertTrue("GZIP file should be non-empty", compressedFile.length() > 0)

        decompressFile(compressedFile, decompressedFile)
        assertTrue("Decompressed file should exist", decompressedFile.exists())
        assertEquals(sourceFile.readText(), decompressedFile.readText())
    }

    @Test
    fun testBzip2CompressAndDecompress() {
        val workDir = tempFolder.newFolder("bzip2_test")
        val sourceFile = File(workDir, "sample.txt").apply {
            writeText("BZip2 algorithm test string with repetitive repetitive patterns patterns patterns.")
        }
        val compressedFile = File(workDir, "sample.txt.bz2")
        val decompressedFile = File(workDir, "sample_restored.txt")

        compressFile(sourceFile, compressedFile, CompressorStreamFactory.BZIP2)
        assertTrue("BZIP2 file should exist", compressedFile.exists())

        decompressFile(compressedFile, decompressedFile)
        assertTrue("Decompressed file should exist", decompressedFile.exists())
        assertEquals(sourceFile.readText(), decompressedFile.readText())
    }

    @Test
    fun testXzCompressAndDecompress() {
        val workDir = tempFolder.newFolder("xz_test")
        val sourceFile = File(workDir, "sample.txt").apply {
            writeText("XZ / LZMA2 compression testing scenario with high compression ratio.")
        }
        val compressedFile = File(workDir, "sample.txt.xz")
        val decompressedFile = File(workDir, "sample_restored.txt")

        compressFile(sourceFile, compressedFile, CompressorStreamFactory.XZ)
        assertTrue("XZ file should exist", compressedFile.exists())

        decompressFile(compressedFile, decompressedFile)
        assertTrue("Decompressed file should exist", decompressedFile.exists())
        assertEquals(sourceFile.readText(), decompressedFile.readText())
    }

    @Test
    fun testCompressAndDecompressEmptyFile() {
        val workDir = tempFolder.newFolder("empty_cs_test")
        val emptySource = File(workDir, "empty.txt").apply { createNewFile() }
        val compressedFile = File(workDir, "empty.txt.gz")
        val decompressedFile = File(workDir, "empty_restored.txt")

        compressFile(emptySource, compressedFile, CompressorStreamFactory.GZIP)
        assertTrue(compressedFile.exists())

        decompressFile(compressedFile, decompressedFile)
        assertTrue(decompressedFile.exists())
        assertEquals(0L, decompressedFile.length())
    }

    @Test
    fun testLargeBinaryPayloadCompressAndDecompressIntegrity() {
        val workDir = tempFolder.newFolder("binary_cs_test")
        val randomBytes = ByteArray(100 * 1024) { (it * 31 % 256).toByte() }
        val sourceFile = File(workDir, "binary.dat").apply { writeBytes(randomBytes) }
        val compressedFile = File(workDir, "binary.dat.gz")
        val decompressedFile = File(workDir, "binary_restored.dat")

        compressFile(sourceFile, compressedFile, CompressorStreamFactory.GZIP)
        decompressFile(compressedFile, decompressedFile)

        assertArrayEquals(randomBytes, decompressedFile.readBytes())
    }

    @Test
    fun testDecompressCorruptedStreamThrowsException() {
        val workDir = tempFolder.newFolder("corrupted_cs")
        val corruptedGz = File(workDir, "broken.gz").apply {
            writeBytes(byteArrayOf(0x1F, (0x8B).toByte(), 0x00, 0x01, 0x02)) // Invalid gzip stream
        }
        val targetFile = File(workDir, "broken_out.txt")

        try {
            decompressFile(corruptedGz, targetFile)
            fail("Expected exception when decompressing invalid gzip stream")
        } catch (e: Exception) {
            assertTrue(
                "Exception should be CompressorException or IOException",
                e is CompressorException || e is IOException
            )
        }
    }
}
