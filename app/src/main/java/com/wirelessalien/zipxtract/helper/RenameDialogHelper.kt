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

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.DialogRenameBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object RenameDialogHelper {

    fun showSingleRenameDialog(
        context: Context,
        scope: CoroutineScope,
        file: File,
        onRenamed: (File) -> Unit
    ) {
        val binding = DialogRenameBinding.inflate(LayoutInflater.from(context))
        binding.renameEditText.setText(file.name)

        // Pre-select base name without extension for files
        val (baseName, ext) = RenameHelper.splitNameAndExtension(file)
        if (!file.isDirectory && ext.isNotEmpty() && baseName.isNotEmpty()) {
            binding.renameEditText.setSelection(0, baseName.length)
        } else {
            binding.renameEditText.selectAll()
        }

        binding.renameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.renameInputLayout.error = null
            }
        })

        val dialog = MaterialAlertDialogBuilder(context, R.style.MaterialDialog)
            .setTitle(R.string.rename_t)
            .setView(binding.root)
            .setPositiveButton(R.string.rename_t, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        dialog.setOnShowListener {
            binding.renameEditText.requestFocus()
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val inputName = binding.renameEditText.text?.toString()?.trim().orEmpty()
                when (RenameHelper.validateFileName(inputName)) {
                    RenameHelper.NameValidationResult.EMPTY -> {
                        binding.renameInputLayout.error = context.getString(R.string.rename_error_empty)
                        return@setOnClickListener
                    }
                    RenameHelper.NameValidationResult.INVALID_CHARS -> {
                        binding.renameInputLayout.error = context.getString(R.string.rename_error_invalid_chars)
                        return@setOnClickListener
                    }
                    RenameHelper.NameValidationResult.VALID -> Unit
                }

                positiveButton.isEnabled = false
                scope.launch(Dispatchers.IO) {
                    val result = RenameHelper.renameSingle(file, inputName)
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is RenameHelper.RenameResult.Success -> {
                                FileUtils.scanFiles(
                                    context.applicationContext,
                                    listOf(result.oldFile.absolutePath, result.newFile.absolutePath)
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.rename_success, result.newFile.name),
                                    Toast.LENGTH_SHORT
                                ).show()
                                dialog.dismiss()
                                onRenamed(result.newFile)
                            }
                            is RenameHelper.RenameResult.NoChange -> {
                                dialog.dismiss()
                            }
                            is RenameHelper.RenameResult.Conflict -> {
                                positiveButton.isEnabled = true
                                binding.renameInputLayout.error = context.getString(R.string.rename_error_exists)
                            }
                            is RenameHelper.RenameResult.InvalidName -> {
                                positiveButton.isEnabled = true
                                binding.renameInputLayout.error = if (result.reason == RenameHelper.NameValidationResult.EMPTY) {
                                    context.getString(R.string.rename_error_empty)
                                } else {
                                    context.getString(R.string.rename_error_invalid_chars)
                                }
                            }
                            is RenameHelper.RenameResult.Failure -> {
                                positiveButton.isEnabled = true
                                Toast.makeText(context, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        dialog.show()
    }
}
