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

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.regex.PatternSyntaxException

object RenameHelper {

    enum class NameValidationResult {
        VALID,
        EMPTY,
        INVALID_CHARS
    }

    sealed class RenameResult {
        data class Success(val oldFile: File, val newFile: File) : RenameResult()
        data class NoChange(val file: File) : RenameResult()
        data class Conflict(val targetFile: File) : RenameResult()
        data class InvalidName(val reason: NameValidationResult) : RenameResult()
        data class Failure(val file: File, val message: String? = null) : RenameResult()
    }

    enum class MultiRenameMode {
        PATTERN,
        FIND_REPLACE,
        ADD_CASE
    }

    enum class CaseTransform {
        UNCHANGED,
        LOWERCASE,
        UPPERCASE,
        TITLE_CASE
    }

    data class MultiRenameConfig(
        val mode: MultiRenameMode = MultiRenameMode.PATTERN,
        val keepExtension: Boolean = true,
        val pattern: String = "{name}_{n:02}",
        val startNumber: Int = 1,
        val step: Int = 1,
        val findText: String = "",
        val replaceText: String = "",
        val caseSensitive: Boolean = false,
        val useRegex: Boolean = false,
        val prefix: String = "",
        val suffix: String = "",
        val caseTransform: CaseTransform = CaseTransform.UNCHANGED
    )

    enum class PreviewStatus {
        VALID,
        UNCHANGED,
        INVALID_NAME,
        INVALID_REGEX,
        CONFLICT_DUPLICATE,
        CONFLICT_EXISTS
    }

    data class RenamePreviewItem(
        val sourceFile: File,
        val newName: String,
        val status: PreviewStatus
    ) {
        val isError: Boolean
            get() = status == PreviewStatus.INVALID_NAME ||
                status == PreviewStatus.INVALID_REGEX ||
                status == PreviewStatus.CONFLICT_DUPLICATE ||
                status == PreviewStatus.CONFLICT_EXISTS
    }

    data class BatchRenameSummary(
        val renamedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val affectedPaths: List<String>
    )

    fun validateFileName(name: String): NameValidationResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return NameValidationResult.EMPTY
        if (trimmed == "." || trimmed == "..") return NameValidationResult.INVALID_CHARS
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            return NameValidationResult.INVALID_CHARS
        }
        return NameValidationResult.VALID
    }

    fun splitNameAndExtension(file: File): Pair<String, String> {
        return splitNameAndExtension(file.name, file.isDirectory)
    }

    fun splitNameAndExtension(name: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory) return name to ""
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < name.length - 1) {
            name.substring(0, dotIndex) to name.substring(dotIndex + 1)
        } else {
            name to ""
        }
    }

    fun renameSingle(file: File, rawNewName: String): RenameResult {
        val newName = rawNewName.trim()
        val validation = validateFileName(newName)
        if (validation != NameValidationResult.VALID) {
            return RenameResult.InvalidName(validation)
        }
        if (!file.exists()) {
            return RenameResult.Failure(file, "Source does not exist")
        }
        if (file.name == newName) {
            return RenameResult.NoChange(file)
        }

        val parent = file.parentFile ?: return RenameResult.Failure(file, "Missing parent directory")
        val target = File(parent, newName)

        if (target.exists()) {
            val isSameUnderlyingFile = try {
                file.name.equals(newName, ignoreCase = true) &&
                    file.canonicalFile == target.canonicalFile
            } catch (_: Exception) {
                false
            }
            if (!isSameUnderlyingFile) {
                return RenameResult.Conflict(target)
            }
            // Case-only rename on a case-insensitive filesystem requires a temp hop
            val tempFile = createTempSibling(parent, file.name)
            if (!file.renameTo(tempFile)) {
                return RenameResult.Failure(file, "Failed to stage case-only rename")
            }
            return if (tempFile.renameTo(target)) {
                RenameResult.Success(file, target)
            } else {
                tempFile.renameTo(file)
                RenameResult.Failure(file, "Failed to complete case-only rename")
            }
        }

        return if (file.renameTo(target)) {
            RenameResult.Success(file, target)
        } else {
            RenameResult.Failure(file, "renameTo returned false")
        }
    }

    fun previewBatchRename(files: List<File>, config: MultiRenameConfig): List<RenamePreviewItem> {
        if (files.isEmpty()) return emptyList()

        val compiledRegex = if (config.mode == MultiRenameMode.FIND_REPLACE && config.useRegex && config.findText.isNotEmpty()) {
            try {
                val options = if (config.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(config.findText, options)
            } catch (_: PatternSyntaxException) {
                return files.map { file ->
                    RenamePreviewItem(file, file.name, PreviewStatus.INVALID_REGEX)
                }
            }
        } else {
            null
        }

        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val rawTargets = try {
            files.mapIndexed { index, file ->
                val newName = computeTargetName(file, index, config, compiledRegex, dateFormat).trim()
                file to newName
            }
        } catch (_: IllegalArgumentException) {
            return files.map { file ->
                RenamePreviewItem(file, file.name, PreviewStatus.INVALID_REGEX)
            }
        } catch (_: IndexOutOfBoundsException) {
            return files.map { file ->
                RenamePreviewItem(file, file.name, PreviewStatus.INVALID_REGEX)
            }
        }

        val sourcePaths = files.map { it.absolutePath }.toSet()
        val seenTargets = mutableMapOf<Pair<String, String>, Int>()
        rawTargets.forEach { (file, newName) ->
            val key = (file.parent ?: "") to newName.lowercase(Locale.ROOT)
            seenTargets[key] = (seenTargets[key] ?: 0) + 1
        }

        return rawTargets.map { (file, newName) ->
            val validation = validateFileName(newName)
            if (validation != NameValidationResult.VALID) {
                return@map RenamePreviewItem(file, newName, PreviewStatus.INVALID_NAME)
            }
            if (newName == file.name) {
                return@map RenamePreviewItem(file, newName, PreviewStatus.UNCHANGED)
            }

            val key = (file.parent ?: "") to newName.lowercase(Locale.ROOT)
            if ((seenTargets[key] ?: 0) > 1) {
                return@map RenamePreviewItem(file, newName, PreviewStatus.CONFLICT_DUPLICATE)
            }

            val parent = file.parentFile
            if (parent != null) {
                val target = File(parent, newName)
                if (target.exists() && !sourcePaths.contains(target.absolutePath)) {
                    val isSameCaseInsensitive = try {
                        file.name.equals(newName, ignoreCase = true) &&
                            file.canonicalFile == target.canonicalFile
                    } catch (_: Exception) {
                        false
                    }
                    if (!isSameCaseInsensitive) {
                        return@map RenamePreviewItem(file, newName, PreviewStatus.CONFLICT_EXISTS)
                    }
                }
            }

            RenamePreviewItem(file, newName, PreviewStatus.VALID)
        }
    }

    fun executeBatchRename(previews: List<RenamePreviewItem>): BatchRenameSummary {
        val actionable = previews.filter { it.status == PreviewStatus.VALID }
        val skippedCount = previews.count { it.status == PreviewStatus.UNCHANGED }
        var renamedCount = 0
        var failedCount = previews.count { it.isError }
        val affectedPaths = mutableListOf<String>()

        if (actionable.isEmpty()) {
            return BatchRenameSummary(0, skippedCount, failedCount, emptyList())
        }

        val sourcePaths = actionable.map { it.sourceFile.absolutePath }.toSet()
        val targetPaths = actionable.mapNotNull { item ->
            item.sourceFile.parentFile?.let { File(it, item.newName).absolutePath }
        }.toSet()
        val staged = mutableListOf<Triple<File, File, File>>() // (original, currentOnDisk, finalTarget)

        // Phase 1: Stage any item whose target currently exists or whose current path is needed by another item
        for (item in actionable) {
            val source = item.sourceFile
            val parent = source.parentFile
            if (parent == null || !source.exists()) {
                failedCount++
                continue
            }
            val target = File(parent, item.newName)
            val needsStaging = targetPaths.contains(source.absolutePath) ||
                (target.exists() && (
                    sourcePaths.contains(target.absolutePath) ||
                        source.name.equals(item.newName, ignoreCase = true)
                    ))

            if (needsStaging) {
                val temp = createTempSibling(parent, source.name)
                if (source.renameTo(temp)) {
                    staged.add(Triple(source, temp, target))
                } else {
                    failedCount++
                }
            } else {
                staged.add(Triple(source, source, target))
            }
        }

        // Phase 2: Move all staged/direct files to their final targets
        for ((original, current, target) in staged) {
            if (current.renameTo(target)) {
                renamedCount++
                affectedPaths.add(original.absolutePath)
                affectedPaths.add(target.absolutePath)
            } else {
                // Attempt rollback if it was staged to a temp file
                if (current != original) {
                    current.renameTo(original)
                }
                failedCount++
            }
        }

        return BatchRenameSummary(
            renamedCount = renamedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            affectedPaths = affectedPaths.distinct()
        )
    }

    private fun computeTargetName(
        file: File,
        index: Int,
        config: MultiRenameConfig,
        compiledRegex: Regex?,
        dateFormat: SimpleDateFormat
    ): String {
        val (baseName, ext) = splitNameAndExtension(file)
        val shouldKeepExt = config.keepExtension && !file.isDirectory && ext.isNotEmpty()
        val workingName = if (shouldKeepExt) baseName else file.name

        val transformed = when (config.mode) {
            MultiRenameMode.PATTERN -> {
                val counter = config.startNumber + (index * config.step)
                val timestamp = file.lastModified().let { if (it > 0L) it else System.currentTimeMillis() }
                val dateStr = dateFormat.format(Date(timestamp))
                applyPattern(config.pattern, baseName, ext, counter, dateStr)
            }

            MultiRenameMode.FIND_REPLACE -> {
                if (config.findText.isEmpty()) {
                    workingName
                } else if (compiledRegex != null) {
                    compiledRegex.replace(workingName, config.replaceText)
                } else {
                    workingName.replace(
                        config.findText,
                        config.replaceText,
                        ignoreCase = !config.caseSensitive
                    )
                }
            }

            MultiRenameMode.ADD_CASE -> {
                val cased = applyCaseTransform(workingName, config.caseTransform)
                "${config.prefix}$cased${config.suffix}"
            }
        }

        return if (shouldKeepExt) "$transformed.$ext" else transformed
    }

    private fun applyPattern(
        pattern: String,
        baseName: String,
        ext: String,
        counter: Int,
        dateStr: String
    ): String {
        val counterRegex = Regex("\\{n(?::(\\d+))?\\}")
        var result = pattern
            .replace("{name}", baseName)
            .replace("{ext}", ext)
            .replace("{date}", dateStr)

        result = counterRegex.replace(result) { match ->
            val widthStr = match.groupValues[1]
            if (widthStr.isNotEmpty()) {
                val width = widthStr.toIntOrNull()?.coerceIn(1, 10) ?: 1
                counter.toString().padStart(width, '0')
            } else {
                counter.toString()
            }
        }
        return result
    }

    private fun applyCaseTransform(text: String, transform: CaseTransform): String {
        val locale = Locale.getDefault()
        return when (transform) {
            CaseTransform.UNCHANGED -> text
            CaseTransform.LOWERCASE -> text.lowercase(locale)
            CaseTransform.UPPERCASE -> text.uppercase(locale)
            CaseTransform.TITLE_CASE -> {
                val sb = StringBuilder(text.length)
                var capitalizeNext = true
                for (ch in text) {
                    if (ch.isWhitespace() || ch == '_' || ch == '-' || ch == '.') {
                        sb.append(ch)
                        capitalizeNext = true
                    } else if (capitalizeNext) {
                        sb.append(ch.titlecaseChar())
                        capitalizeNext = false
                    } else {
                        sb.append(ch.lowercaseChar())
                    }
                }
                sb.toString()
            }
        }
    }

    private fun createTempSibling(parent: File, baseName: String): File {
        var candidate: File
        do {
            val token = UUID.randomUUID().toString().take(8)
            candidate = File(parent, ".zipxtract_rename_${token}_$baseName")
        } while (candidate.exists())
        return candidate
    }
}
