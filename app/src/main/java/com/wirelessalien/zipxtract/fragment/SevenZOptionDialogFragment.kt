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


package com.wirelessalien.zipxtract.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.databinding.SevenZOptionDialogBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SevenZOptionDialogFragment : DialogFragment() {

    private lateinit var binding: SevenZOptionDialogBinding
    private lateinit var adapter: FileAdapter
    private lateinit var selectedFilePaths: List<String>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SevenZOptionDialogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show the progress bar
        binding.progressIndicator.visibility = View.VISIBLE

        // Launch a coroutine to fetch the selected file paths
        CoroutineScope(Dispatchers.Main).launch {
            selectedFilePaths = withContext(Dispatchers.IO) {
                adapter.getSelectedFilesPaths()
            }

            // Hide the progress bar
            binding.progressIndicator.visibility = View.GONE

            // Initialize the rest of the UI with the fetched data
            initializeUI()
        }
    }

    private fun initializeUI() {
        binding.okButton.setOnClickListener {
            val defaultName = if (selectedFilePaths.isNotEmpty()) {
                File(selectedFilePaths.first()).name
            } else {
                "outputSvnZ"
            }
            val archiveName = binding.archiveNameEditText.text.toString().ifBlank { defaultName }
            val password = binding.passwordEditText.text.toString()
            val compressionLevel = when (binding.compressionSpinner.selectedItemPosition) {
                0 -> 0
                1 -> 1
                2 -> 3
                3 -> 5
                4 -> 7
                5 -> 9
                else -> -1
            }
            val solid = binding.solidCheckBox.isChecked
            val threadCount = binding.threadCountEditText.text.toString().toIntOrNull() ?: -1

            (activity as MainActivity).startSevenZService(password.ifBlank { null }, archiveName, compressionLevel, solid, threadCount, selectedFilePaths)
            dismiss()
        }

        binding.noPasswordButton.setOnClickListener {
            val defaultName = if (selectedFilePaths.isNotEmpty()) {
                File(selectedFilePaths.first()).name
            } else {
                "outputSvnZ"
            }
            val archiveName = binding.archiveNameEditText.text.toString().ifBlank { defaultName }
            val compressionLevel = when (binding.compressionSpinner.selectedItemPosition) {
                0 -> 0
                1 -> 1
                2 -> 3
                3 -> 5
                4 -> 7
                5 -> 9
                else -> -1
            }
            val solid = binding.solidCheckBox.isChecked
            val threadCount = binding.threadCountEditText.text.toString().toIntOrNull() ?: -1

            (activity as MainActivity).startSevenZService(null, archiveName, compressionLevel, solid, threadCount, selectedFilePaths)
            dismiss()
        }
    }

    companion object {
        fun newInstance(adapter: FileAdapter): SevenZOptionDialogFragment {
            val fragment = SevenZOptionDialogFragment()
            fragment.adapter = adapter
            return fragment
        }
    }
}