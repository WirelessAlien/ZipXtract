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
import android.os.Environment
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.adapter.FilePathAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_ARCHIVE_DIR_PATH
import com.wirelessalien.zipxtract.databinding.TarOptionDialogBinding
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TarOptionsDialogFragment : DialogFragment() {

    private lateinit var binding: TarOptionDialogBinding
    private var adapter: FileAdapter? = null
    private lateinit var selectedFilePaths: MutableList<String>
    private lateinit var filePathAdapter: FilePathAdapter
    private var launchedWithFilePaths = false
    private var selectedCompressionFormat: String = "TAR_ONLY"
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
        binding = TarOptionDialogBinding.inflate(inflater, container, false)
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
                    Toast.makeText(
                        context,
                        getString(R.string.error_no_file_information_provided),
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                    return@launch
                }
            }
            binding.progressIndicator.visibility = View.GONE
            initializeUI()
        }
    }

    private fun checkStorageForArchive(
        warningTextView: TextView,
        path: String,
        requiredSize: Long,
        okButton: View? = null,
        defaultColor: android.content.res.ColorStateList? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stat = StatFs(path)
                val availableSize = stat.availableBytes
                val safeRequiredSize = (requiredSize * 1.1).toLong()

                if (availableSize < safeRequiredSize) {
                    val availableSizeStr = android.text.format.Formatter.formatFileSize(
                        requireContext(),
                        availableSize
                    )
                    val requiredSizeStr =
                        android.text.format.Formatter.formatFileSize(requireContext(), requiredSize)
                    val warningText = getString(
                        R.string.low_storage_warning_dynamic,
                        availableSizeStr,
                        requiredSizeStr
                    )
                    withContext(Dispatchers.Main) {
                        warningTextView.text = warningText
                        warningTextView.visibility = View.VISIBLE
                        val errorColor = MaterialColors.getColor(warningTextView, com.google.android.material.R.attr.colorOnError)
                        okButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        warningTextView.visibility = View.GONE
                        if (defaultColor != null) {
                            okButton?.backgroundTintList = defaultColor
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initializeUI() {
        if (!::selectedFilePaths.isInitialized || selectedFilePaths.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT)
                .show()
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
                        Toast.makeText(
                            requireContext(),
                            R.string.no_files_to_archive,
                            Toast.LENGTH_SHORT
                        ).show()
                        dismiss()
                    }
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.file_list_is_fixed_for_this_operation),
                    Toast.LENGTH_SHORT
                ).show()
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
            "outputTar"
        }

        // Check storage
        var parentPath = if (selectedFilePaths.isNotEmpty()) {
            File(selectedFilePaths.first()).parent
                ?: Environment.getExternalStorageDirectory().absolutePath
        } else {
            Environment.getExternalStorageDirectory().absolutePath
        }

        if (FileUtils.isInternalPath(requireContext(), parentPath)) {
            parentPath = Environment.getExternalStorageDirectory().absolutePath
        }

        val defaultColor = binding.okButton.backgroundTintList
        var totalSize = 0L

        lifecycleScope.launch {
            totalSize = withContext(Dispatchers.IO) {
                selectedFilePaths.sumOf { File(it).length() }
            }
            checkStorageForArchive(binding.lowStorageWarning, parentPath, totalSize, binding.okButton, defaultColor)
        }

        var storageCheckJob: Job? = null
        binding.outputPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                storageCheckJob?.cancel()
                storageCheckJob = lifecycleScope.launch {
                    delay(1000)
                    checkStorageForArchive(
                        binding.lowStorageWarning,
                        s.toString(),
                        totalSize,
                        binding.okButton,
                        defaultColor
                    )
                }
            }
        })

        binding.archiveNameEditText.setText(defaultName)
        binding.archiveNameEditText.setOnClickListener {
            binding.archiveNameEditText.selectAll()
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val archivePath = sharedPreferences.getString(PREFERENCE_ARCHIVE_DIR_PATH, null)
        val defaultPath = if (!archivePath.isNullOrEmpty()) {
            if (File(archivePath).isAbsolute) {
                archivePath
            } else {
                File(Environment.getExternalStorageDirectory(), archivePath).absolutePath
            }
        } else {
            parentPath
        }

        binding.outputPathInput.setText(defaultPath)
        binding.outputPathLayout.setEndIconOnClickListener {
            val pathPicker = PathPickerFragment.newInstance()
            pathPicker.setPathPickerListener(object : PathPickerFragment.PathPickerListener {
                override fun onPathSelected(path: String) {
                    binding.outputPathInput.setText(path)
                }
            })
            pathPicker.show(parentFragmentManager, "path_picker")
        }

        binding.compressionChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
            selectedCompressionFormat = when (checkedId) {
                R.id.chipTarLzma -> org.apache.commons.compress.compressors.CompressorStreamFactory.LZMA
                R.id.chipTarBz2 -> org.apache.commons.compress.compressors.CompressorStreamFactory.BZIP2
                R.id.chipTarXz -> org.apache.commons.compress.compressors.CompressorStreamFactory.XZ
                R.id.chipTarZstd -> org.apache.commons.compress.compressors.CompressorStreamFactory.ZSTANDARD
                R.id.chipTarGz -> org.apache.commons.compress.compressors.CompressorStreamFactory.GZIP
                else -> "TAR_ONLY"
            }
        }

        binding.okButton.setOnClickListener {
            val archiveName = binding.archiveNameEditText.text.toString().ifBlank { defaultName }
            val destinationPath = binding.outputPathInput.text.toString()

            val mainFragment =
                parentFragmentManager.findFragmentById(R.id.container) as? MainFragment
            mainFragment?.startArchiveTarService(
                selectedFilePaths,
                archiveName,
                selectedCompressionFormat,
                destinationPath
            )
            dismiss()
        }


        binding.cancelButton.setOnClickListener {
            dismiss()
        }
    }

    companion object {
        private const val ARG_JOB_ID = "arg_job_id"

        fun newInstance(adapter: FileAdapter): TarOptionsDialogFragment {
            val fragment = TarOptionsDialogFragment()
            fragment.adapter = adapter
            fragment.launchedWithFilePaths = false
            return fragment
        }

        fun newInstance(jobId: String): TarOptionsDialogFragment {
            val fragment = TarOptionsDialogFragment()
            val args = Bundle()
            args.putString(ARG_JOB_ID, jobId)
            fragment.arguments = args
            return fragment
        }
    }
}