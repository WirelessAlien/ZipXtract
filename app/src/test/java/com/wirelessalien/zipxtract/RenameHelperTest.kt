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

import com.wirelessalien.zipxtract.helper.RenameHelper
import com.wirelessalien.zipxtract.helper.RenameHelper.CaseTransform
import com.wirelessalien.zipxtract.helper.RenameHelper.MultiRenameConfig
import com.wirelessalien.zipxtract.helper.RenameHelper.MultiRenameMode
import com.wirelessalien.zipxtract.helper.RenameHelper.NameValidationResult
import com.wirelessalien.zipxtract.helper.RenameHelper.PreviewStatus
import com.wirelessalien.zipxtract.helper.RenameHelper.RenameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RenameHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidateFileName() {
        assertEquals(NameValidationResult.EMPTY, RenameHelper.validateFileName(""))
        assertEquals(NameValidationResult.EMPTY, RenameHelper.validateFileName("   "))
        assertEquals(NameValidationResult.INVALID_CHARS, RenameHelper.validateFileName("."))
        assertEquals(NameValidationResult.INVALID_CHARS, RenameHelper.validateFileName(".."))
        assertEquals(NameValidationResult.INVALID_CHARS, RenameHelper.validateFileName("a/b"))
        assertEquals(NameValidationResult.INVALID_CHARS, RenameHelper.validateFileName("a\\b"))
        assertEquals(NameValidationResult.VALID, RenameHelper.validateFileName("document_01.pdf"))
        assertEquals(NameValidationResult.VALID, RenameHelper.validateFileName(".hidden"))
    }

    @Test
    fun testSingleRenameFileAndFolder() {
        val dir = tempFolder.newFolder("single_rename")
        val file = File(dir, "old.txt").apply { writeText("hello") }
        val folder = File(dir, "OldFolder").apply { mkdir() }
        File(folder, "inside.txt").writeText("nested")

        val fileRes = RenameHelper.renameSingle(file, "new.txt")
        assertTrue(fileRes is RenameResult.Success)
        assertFalse(file.exists())
        assertTrue(File(dir, "new.txt").exists())
        assertEquals("hello", File(dir, "new.txt").readText())

        val folderRes = RenameHelper.renameSingle(folder, "NewFolder")
        assertTrue(folderRes is RenameResult.Success)
        assertFalse(folder.exists())
        assertTrue(File(dir, "NewFolder/inside.txt").exists())
    }

    @Test
    fun testSingleRenameConflictAndNoChange() {
        val dir = tempFolder.newFolder("conflict")
        val f1 = File(dir, "a.txt").apply { writeText("1") }
        File(dir, "b.txt").apply { writeText("2") }

        assertTrue(RenameHelper.renameSingle(f1, "a.txt") is RenameResult.NoChange)
        assertTrue(RenameHelper.renameSingle(f1, "b.txt") is RenameResult.Conflict)
        assertTrue(f1.exists())
    }

    @Test
    fun testBatchPatternRename() {
        val dir = tempFolder.newFolder("pattern")
        val f1 = File(dir, "alpha.jpg").apply { writeText("1") }
        val f2 = File(dir, "beta.png").apply { writeText("2") }
        val folder = File(dir, "docs").apply { mkdir() }

        val config = MultiRenameConfig(
            mode = MultiRenameMode.PATTERN,
            keepExtension = true,
            pattern = "item_{n:02}_{name}",
            startNumber = 1,
            step = 2
        )

        val previews = RenameHelper.previewBatchRename(listOf(f1, f2, folder), config)
        assertEquals(3, previews.size)
        assertEquals("item_01_alpha.jpg", previews[0].newName)
        assertEquals("item_03_beta.png", previews[1].newName)
        assertEquals("item_05_docs", previews[2].newName)
        assertTrue(previews.all { it.status == PreviewStatus.VALID })

        val summary = RenameHelper.executeBatchRename(previews)
        assertEquals(3, summary.renamedCount)
        assertEquals(0, summary.failedCount)
        assertTrue(File(dir, "item_01_alpha.jpg").exists())
        assertTrue(File(dir, "item_03_beta.png").exists())
        assertTrue(File(dir, "item_05_docs").isDirectory)
    }

    @Test
    fun testBatchFindReplacePlainAndRegex() {
        val dir = tempFolder.newFolder("replace")
        val f1 = File(dir, "IMG_001_draft.jpg").apply { writeText("1") }
        val f2 = File(dir, "IMG_002_DRAFT.jpg").apply { writeText("2") }

        val plainConfig = MultiRenameConfig(
            mode = MultiRenameMode.FIND_REPLACE,
            keepExtension = true,
            findText = "draft",
            replaceText = "final",
            caseSensitive = false,
            useRegex = false
        )
        val plainPreviews = RenameHelper.previewBatchRename(listOf(f1, f2), plainConfig)
        assertEquals("IMG_001_final.jpg", plainPreviews[0].newName)
        assertEquals("IMG_002_final.jpg", plainPreviews[1].newName)

        val regexConfig = MultiRenameConfig(
            mode = MultiRenameMode.FIND_REPLACE,
            keepExtension = true,
            findText = "\\d+",
            replaceText = "X",
            useRegex = true
        )
        val regexPreviews = RenameHelper.previewBatchRename(listOf(f1, f2), regexConfig)
        assertEquals("IMG_X_draft.jpg", regexPreviews[0].newName)
        assertEquals("IMG_X_DRAFT.jpg", regexPreviews[1].newName)
    }

    @Test
    fun testBatchAddPrefixSuffixAndCase() {
        val dir = tempFolder.newFolder("add_case")
        val f1 = File(dir, "my_report.pdf").apply { writeText("1") }
        val folder = File(dir, "archive_folder").apply { mkdir() }

        val config = MultiRenameConfig(
            mode = MultiRenameMode.ADD_CASE,
            keepExtension = true,
            prefix = "2026_",
            suffix = "_v1",
            caseTransform = CaseTransform.TITLE_CASE
        )

        val previews = RenameHelper.previewBatchRename(listOf(f1, folder), config)
        assertEquals("2026_My_Report_v1.pdf", previews[0].newName)
        assertEquals("2026_Archive_Folder_v1", previews[1].newName)
    }

    @Test
    fun testBatchOverlappingCyclicRenameAndConflictDetection() {
        val dir = tempFolder.newFolder("cyclic")
        val f1 = File(dir, "file1.txt").apply { writeText("one") }
        val f2 = File(dir, "file2.txt").apply { writeText("two") }
        val existing = File(dir, "file3.txt").apply { writeText("three") }

        // Shift f1 -> file2.txt, f2 -> file3.txt (file3.txt is NOT in batch -> conflict!)
        val conflictConfig = MultiRenameConfig(
            mode = MultiRenameMode.PATTERN,
            keepExtension = true,
            pattern = "file{n}",
            startNumber = 2,
            step = 1
        )
        val conflictPreviews = RenameHelper.previewBatchRename(listOf(f1, f2), conflictConfig)
        assertEquals(PreviewStatus.VALID, conflictPreviews[0].status)
        assertEquals(PreviewStatus.CONFLICT_EXISTS, conflictPreviews[1].status)

        // Now include all 3 in a cyclic shift: file1 -> file2, file2 -> file3, file3 -> file1
        // Use regex/find-replace or manual preview items to test two-phase staging
        val cyclicPreviews = listOf(
            RenameHelper.RenamePreviewItem(f1, "file2.txt", PreviewStatus.VALID),
            RenameHelper.RenamePreviewItem(f2, "file3.txt", PreviewStatus.VALID),
            RenameHelper.RenamePreviewItem(existing, "file1.txt", PreviewStatus.VALID)
        )
        val summary = RenameHelper.executeBatchRename(cyclicPreviews)
        assertEquals(3, summary.renamedCount)
        assertEquals(0, summary.failedCount)
        assertEquals("one", File(dir, "file2.txt").readText())
        assertEquals("two", File(dir, "file3.txt").readText())
        assertEquals("three", File(dir, "file1.txt").readText())
    }

    @Test
    fun testInvalidRegexReplacementAndCaseInsensitiveDuplicate() {
        val dir = tempFolder.newFolder("edge_cases")
        val f1 = File(dir, "a.txt").apply { writeText("1") }
        val f2 = File(dir, "b.txt").apply { writeText("2") }

        val badReplaceConfig = MultiRenameConfig(
            mode = MultiRenameMode.FIND_REPLACE,
            findText = "a",
            replaceText = "$9",
            useRegex = true
        )
        val badPreviews = RenameHelper.previewBatchRename(listOf(f1, f2), badReplaceConfig)
        assertTrue(badPreviews.all { it.status == PreviewStatus.INVALID_REGEX })

        val caseDupPreviews = listOf(f1, f2).let { files ->
            RenameHelper.previewBatchRename(
                files,
                MultiRenameConfig(
                    mode = MultiRenameMode.ADD_CASE,
                    prefix = "",
                    suffix = "",
                    caseTransform = CaseTransform.UNCHANGED
                )
            )
            // Rename both to "Same.txt" / "same.txt" via pattern vs case
            RenameHelper.previewBatchRename(
                files,
                MultiRenameConfig(
                    mode = MultiRenameMode.PATTERN,
                    keepExtension = true,
                    pattern = "same"
                )
            )
        }
        assertTrue(caseDupPreviews.all { it.status == PreviewStatus.CONFLICT_DUPLICATE })
    }
}
