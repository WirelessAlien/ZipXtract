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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.adapter.FilePathAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_ARCHIVE_DIR_PATH
import com.wirelessalien.zipxtract.databinding.TarOptionDialogBinding
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.helper.PathUtils
import com.wirelessalien.zipxtract.viewmodel.ArchiveCreationViewModel
import com.wirelessalien.zipxtract.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.fragment.app.activityViewModels

class TarOptionsDialogFragment : DialogFragment() {

    private lateinit var binding: TarOptionDialogBinding
    private var adapter: FileAdapter? = null
    private lateinit var selectedFilePaths: MutableList<String>
    private lateinit var filePathAdapter: FilePathAdapter
    private var launchedWithFilePaths = false
    private var selectedCompressionFormat: String = "TAR_ONLY"
    private var jobId: String? = null
    private val viewModel: ArchiveCreationViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            jobId = it.getString(ARG_JOB_ID)
            if (jobId != null) {
                selectedFilePaths = mainViewModel.getFilesForJob(jobId!!).toMutableList()
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

    private fun initializeUI() {
        if (!::selectedFilePaths.isInitialized || selectedFilePaths.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT)
                .show()
            dismiss()
            return
        }

        filePathAdapter = FilePathAdapter(selectedFilePaths) { filePath ->
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
        }

        binding.filePathsRv.layoutManager = LinearLayoutManager(context)
        binding.filePathsRv.adapter = filePathAdapter

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
        val defaultTextColor = binding.okButton.textColors

        viewModel.calculateTotalSize(selectedFilePaths)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.storageWarning.collect { warning ->
                        if (warning != null) {
                            binding.lowStorageWarning.text = warning
                            binding.lowStorageWarning.visibility = View.VISIBLE
                            val errorColor = MaterialColors.getColor(binding.lowStorageWarning, androidx.appcompat.R.attr.colorError)
                            val onErrorColor = MaterialColors.getColor(binding.lowStorageWarning, com.google.android.material.R.attr.colorOnError)
                            binding.okButton.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                            (binding.okButton as? TextView)?.setTextColor(onErrorColor)
                        } else {
                            binding.lowStorageWarning.visibility = View.GONE
                            if (defaultColor != null) {
                                binding.okButton.backgroundTintList = defaultColor
                            }
                            if (defaultTextColor != null) {
                                (binding.okButton as? TextView)?.setTextColor(defaultTextColor)
                            }
                        }
                    }
                }
                launch {
                    viewModel.totalSize.collect { size ->
                        viewModel.checkStorageForArchive(parentPath, size)
                    }
                }
            }
        }

        binding.outputPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val path = s.toString()
                binding.outputPathDisplay.text = PathUtils.formatPath(path, requireContext())
                viewModel.checkStorageForArchive(path, viewModel.totalSize.value)
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
        binding.outputPathDisplay.text = PathUtils.formatPath(defaultPath, requireContext())
        binding.outputPathLayout.setEndIconOnClickListener {
            val pathPicker = PathPickerFragment.newInstance()
            pathPicker.setPathPickerListener(object : PathPickerFragment.PathPickerListener {
                override fun onPathSelected(path: String) {
                    binding.outputPathInput.setText(path)
                    binding.outputPathDisplay.text = PathUtils.formatPath(path, requireContext())
                }
            })
            pathPicker.show(parentFragmentManager, "path_picker")
        }

        binding.zstdCompressionLevelSlider.addOnChangeListener { _, value, _ ->
            binding.zstdCompressionLevelLabel.text = getString(R.string.zstd_compression_level_format, value.toInt())
        }

        // Initialize label with default value
        binding.zstdCompressionLevelLabel.text = getString(R.string.zstd_compression_level_format, binding.zstdCompressionLevelSlider.value.toInt())

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
            if (selectedCompressionFormat == org.apache.commons.compress.compressors.CompressorStreamFactory.ZSTANDARD) {
                binding.zstdCompressionLevelContainer.visibility = View.VISIBLE
            } else {
                binding.zstdCompressionLevelContainer.visibility = View.GONE
            }
        }

        binding.okButton.setOnClickListener {
            val archiveName = binding.archiveNameEditText.text.toString().ifBlank { defaultName }
            val destinationPath = binding.outputPathInput.text.toString()
            val zstdCompressionLevel = binding.zstdCompressionLevelSlider.value.toInt()

            mainViewModel.startArchiveTarService(
                selectedFilePaths,
                archiveName,
                selectedCompressionFormat,
                destinationPath,
                zstdCompressionLevel
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