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
import android.widget.Toast
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.adapter.FilePathAdapter
import com.wirelessalien.zipxtract.databinding.SevenZOptionDialogBinding
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SevenZOptionDialogFragment : DialogFragment() {

    private lateinit var binding: SevenZOptionDialogBinding
    private var adapter: FileAdapter? = null
    private lateinit var selectedFilePaths: MutableList<String>
    private lateinit var filePathAdapter: FilePathAdapter
    private var launchedWithFilePaths = false
    private var jobId: String? = null
    private lateinit var fileOperationsDao: FileOperationsDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileOperationsDao = FileOperationsDao(requireContext())
        arguments?.let {
            jobId = it.getString(ARG_JOB_ID)
            if (jobId != null) {
                selectedFilePaths = fileOperationsDao.getFilesForJob(jobId!!).toMutableList()
                launchedWithFilePaths = true
            }
        }
    }

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
            if (!::selectedFilePaths.isInitialized) {
                if (adapter != null) {
                    selectedFilePaths = withContext(Dispatchers.IO) {
                        adapter!!.getSelectedFilesPaths().toMutableList()
                    }
                } else {
                    binding.progressIndicator.visibility = View.GONE
                    Toast.makeText(context, getString(R.string.error_no_file_information_provided), Toast.LENGTH_LONG).show()
                    dismiss()
                    return@launch
                }
            }
            binding.progressIndicator.visibility = View.GONE
            initializeUI()
        }
    }

    private fun initializeUI() {
        if (!::selectedFilePaths.isInitialized || selectedFilePaths.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        filePathAdapter = FilePathAdapter(selectedFilePaths) { filePath ->
            if (!launchedWithFilePaths) {
                val position = selectedFilePaths.indexOf(filePath)
                if (position != -1) {
                    selectedFilePaths.removeAt(position)
                    filePathAdapter.removeFilePath(filePath)
                    filePathAdapter.notifyItemRemoved(position)
                    filePathAdapter.notifyItemRangeChanged(position, selectedFilePaths.size)

                    if (selectedFilePaths.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
                        dismiss()
                    }
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.file_list_is_fixed_for_this_operation), Toast.LENGTH_SHORT).show()
            }
        }

        binding.filePathsRv.layoutManager = LinearLayoutManager(context)
        binding.filePathsRv.adapter = filePathAdapter

        if (launchedWithFilePaths) {
            binding.filePathsRv.visibility = View.GONE
            binding.toggleFileViewBtn.visibility = View.GONE
        }

        binding.toggleFileViewBtn.setOnClickListener {
            if (binding.filePathsRv.isGone) {
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
            val confirmPassword = binding.confirmPasswordEditText.text.toString()

            if (password != confirmPassword) {
                binding.confirmPasswordEditText.error = getString(R.string.passwords_do_not_match)
                return@setOnClickListener
            }

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
        private const val ARG_JOB_ID = "arg_job_id"

        fun newInstance(adapter: FileAdapter): SevenZOptionDialogFragment {
            val fragment = SevenZOptionDialogFragment()
            fragment.adapter = adapter
            fragment.launchedWithFilePaths = false
            return fragment
        }

        fun newInstance(jobId: String): SevenZOptionDialogFragment {
            val fragment = SevenZOptionDialogFragment()
            val args = Bundle()
            args.putString(ARG_JOB_ID, jobId)
            fragment.arguments = args
            return fragment
        }
    }
}