package com.wirelessalien.zipxtract.helper

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.DialogFileConflictBinding
import java.io.File

object ConflictResolutionHelper {

    enum class ConflictAction {
        SKIP,
        REPLACE,
        KEEP_BOTH
    }

    /**
     * Resolves file conflicts for a list of source files to be copied or moved into destinationDir.
     * Returns a list of Pair<sourceFilePath, customDestinationName?> for files that should be processed.
     * For REPLACE, customDestinationName is null (original name).
     * For KEEP_BOTH, customDestinationName is a unique renamed file name.
     * For SKIP, the item is omitted.
     */
    fun resolveConflicts(
        context: Context,
        files: List<File>,
        destinationDir: File,
        onFinished: (resolvedPairs: List<Pair<String, String?>>) -> Unit
    ) {
        val nonConflicting = mutableListOf<Pair<String, String?>>()
        val conflicting = mutableListOf<File>()

        for (file in files) {
            val target = File(destinationDir, file.name)
            if (target.exists()) {
                conflicting.add(file)
            } else {
                nonConflicting.add(file.absolutePath to null)
            }
        }

        if (conflicting.isEmpty()) {
            onFinished(nonConflicting)
            return
        }

        val resolved = nonConflicting.toMutableList()
        var doForAllAction: ConflictAction? = null
        var currentIndex = 0

        fun applyAction(file: File, action: ConflictAction) {
            when (action) {
                ConflictAction.SKIP -> {
                    // Omit file from resolved
                }
                ConflictAction.REPLACE -> {
                    resolved.add(file.absolutePath to null)
                }
                ConflictAction.KEEP_BOTH -> {
                    val reservedNames = resolved.mapNotNull { it.second ?: File(it.first).name }.toSet()
                    val uniqueFile = FileUtils.getUniqueDestinationFile(destinationDir, file.name, reservedNames)
                    resolved.add(file.absolutePath to uniqueFile.name)
                }
            }
        }

        fun processNext() {
            if (currentIndex >= conflicting.size) {
                onFinished(resolved)
                return
            }

            val currentFile = conflicting[currentIndex]

            val actionToUse = doForAllAction
            if (actionToUse != null) {
                applyAction(currentFile, actionToUse)
                currentIndex++
                processNext()
                return
            }

            val binding = DialogFileConflictBinding.inflate(LayoutInflater.from(context))
            binding.textConflictMessage.text = context.getString(R.string.conflict_dialog_message, currentFile.name)

            var dialog: AlertDialog? = null

            val handleChoice = { action: ConflictAction ->
                if (binding.checkboxDoForAll.isChecked) {
                    doForAllAction = action
                }
                applyAction(currentFile, action)
                dialog?.dismiss()
                currentIndex++
                processNext()
            }

            binding.btnSkip.setOnClickListener {
                handleChoice(ConflictAction.SKIP)
            }

            binding.btnReplace.setOnClickListener {
                handleChoice(ConflictAction.REPLACE)
            }

            binding.btnKeepBoth.setOnClickListener {
                handleChoice(ConflictAction.KEEP_BOTH)
            }

            dialog = MaterialAlertDialogBuilder(context, R.style.MaterialDialog)
                .setView(binding.root)
                .setCancelable(true)
                .setOnCancelListener {
                    onFinished(emptyList())
                }
                .create()

            dialog.show()
        }

        processNext()
    }
}
