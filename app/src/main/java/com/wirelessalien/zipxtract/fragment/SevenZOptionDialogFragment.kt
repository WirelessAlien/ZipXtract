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
import androidx.recyclerview.widget.LinearLayoutManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.adapter.FilePathAdapter
import com.wirelessalien.zipxtract.databinding.SevenZOptionDialogBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SevenZOptionDialogFragment : DialogFragment() {

    private lateinit var binding: SevenZOptionDialogBinding
    private lateinit var adapter: FileAdapter
    private lateinit var selectedFilePaths: MutableList<String>
    private lateinit var filePathAdapter: FilePathAdapter

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

        binding.progressIndicator.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            selectedFilePaths = withContext(Dispatchers.IO) {
                adapter.getSelectedFilesPaths().toMutableList()
            }

            binding.progressIndicator.visibility = View.GONE

            initializeUI()
        }
    }

    private fun initializeUI() {
        filePathAdapter = FilePathAdapter(selectedFilePaths) { filePath ->
            val position = selectedFilePaths.indexOf(filePath)
            if (position != -1) {
                selectedFilePaths.removeAt(position)
                filePathAdapter.removeFilePath(filePath)
                filePathAdapter.notifyItemRemoved(position)
                filePathAdapter.notifyItemRangeChanged(position, selectedFilePaths.size)
            }
        }

        binding.filePathsRv.layoutManager = LinearLayoutManager(context)
        binding.filePathsRv.adapter = filePathAdapter

        binding.toggleFileViewBtn.setOnClickListener {
            if (binding.filePathsRv.visibility == View.GONE) {
                binding.filePathsRv.visibility = View.VISIBLE
            } else {
                binding.filePathsRv.visibility = View.GONE
            }
        }

        val defaultName = if (selectedFilePaths.isNotEmpty()) {
            File(selectedFilePaths.first()).name
        } else {
            "outputSvnZ"
        }
        binding.archiveNameEditText.setText(defaultName)
        binding.archiveNameEditText.setOnClickListener {
            binding.archiveNameEditText.selectAll()
        }

        val mainFragment = parentFragmentManager.findFragmentById(R.id.container) as? MainFragment

        binding.okButton.setOnClickListener {
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

            mainFragment?.startSevenZService(password.ifBlank { null }, archiveName, compressionLevel, solid, threadCount, selectedFilePaths)
            dismiss()
        }

        binding.noPasswordButton.setOnClickListener {
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

            mainFragment?.startSevenZService(null, archiveName, compressionLevel, solid, threadCount, selectedFilePaths)
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