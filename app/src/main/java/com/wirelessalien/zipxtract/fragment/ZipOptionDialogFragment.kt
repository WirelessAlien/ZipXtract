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

import android.R
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
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.adapter.FilePathAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_ARCHIVE_DIR_PATH
import com.wirelessalien.zipxtract.databinding.ZipOptionDialogBinding
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.helper.PathUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

class ZipOptionDialogFragment : DialogFragment() {

    private lateinit var binding: ZipOptionDialogBinding
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
        binding = ZipOptionDialogBinding.inflate(inflater, container, false)
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
                        getString(com.wirelessalien.zipxtract.R.string.error_no_file_information_provided),
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
        defaultColor: android.content.res.ColorStateList? = null,
        defaultTextColor: android.content.res.ColorStateList? = null
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
                        com.wirelessalien.zipxtract.R.string.low_storage_warning_dynamic,
                        availableSizeStr,
                        requiredSizeStr
                    )
                    withContext(Dispatchers.Main) {
                        warningTextView.text = warningText
                        warningTextView.visibility = View.VISIBLE
                        val errorColor = MaterialColors.getColor(warningTextView, androidx.appcompat.R.attr.colorError)
                        val onErrorColor = MaterialColors.getColor(warningTextView, com.google.android.material.R.attr.colorOnError)
                        okButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                        (okButton as? TextView)?.setTextColor(onErrorColor)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        warningTextView.visibility = View.GONE
                        if (defaultColor != null) {
                            okButton?.backgroundTintList = defaultColor
                        }
                        if (defaultTextColor != null) {
                            (okButton as? TextView)?.setTextColor(defaultTextColor)
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
            Toast.makeText(
                requireContext(),
                com.wirelessalien.zipxtract.R.string.no_files_to_archive,
                Toast.LENGTH_SHORT
            ).show()
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
                        com.wirelessalien.zipxtract.R.string.no_files_to_archive,
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

        binding.encInfo.setOnClickListener {
            MaterialAlertDialogBuilder(
                requireContext(),
                com.wirelessalien.zipxtract.R.style.MaterialDialog
            )
                .setMessage(getString(com.wirelessalien.zipxtract.R.string.settings_info_text))
                .setPositiveButton(getString(com.wirelessalien.zipxtract.R.string.ok)) { _, _ ->
                }
                .show()
        }

        val defaultName = if (selectedFilePaths.isNotEmpty()) {
            File(selectedFilePaths.first()).name
        } else {
            "outputZp"
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
        var totalSize = 0L

        lifecycleScope.launch {
            totalSize = withContext(Dispatchers.IO) {
                selectedFilePaths.sumOf { File(it).length() }
            }
            checkStorageForArchive(binding.lowStorageWarning, parentPath, totalSize, binding.okButton, defaultColor, defaultTextColor)
        }

        var storageCheckJob: Job? = null
        binding.outputPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val path = s.toString()
                binding.outputPathDisplay.text = PathUtils.formatPath(path, requireContext())
                storageCheckJob?.cancel()
                storageCheckJob = lifecycleScope.launch {
                    delay(1000)
                    checkStorageForArchive(
                        binding.lowStorageWarning,
                        path,
                        totalSize,
                        binding.okButton,
                        defaultColor,
                        defaultTextColor
                    )
                }
            }
        })

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

        val passwordInput = binding.passwordEditText
        val confirmPasswordInput = binding.confirmPasswordEditText
        val zipNameEditText = binding.zipNameEditText

        val splitZipCheckbox = binding.splitZipCheckbox
        val splitSizeInput = binding.splitSizeEditText

        val splitSizeUnitAutoComplete = binding.splitSizeUnitAutoComplete
        val splitSizeUnits = arrayOf("KB", "MB", "GB")
        val splitSizeUnitAdapter =
            ArrayAdapter(requireContext(), R.layout.simple_spinner_dropdown_item, splitSizeUnits)
        splitSizeUnitAutoComplete.setAdapter(splitSizeUnitAdapter)
        splitSizeUnitAutoComplete.setText(splitSizeUnits[1], false) // Default MB

        splitZipCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.splitZipContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                splitSizeInput.text?.clear()
            }
        }

        zipNameEditText.setText(defaultName)
        zipNameEditText.setOnClickListener {
            zipNameEditText.selectAll()
        }


        val compressionMethodChipGroup = binding.compressionMethodChipGroup
        val compressionMethods = listOf(CompressionMethod.STORE, CompressionMethod.DEFLATE)

        compressionMethodChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            if (checkedId != null && checkedId < compressionMethods.size) {
                val method = compressionMethods[checkedId]
                val isStore = method == CompressionMethod.STORE
                binding.compressionLevelSlider.isEnabled = !isStore
                binding.compressionLevelDescription.alpha = if (isStore) 0.5f else 1.0f
                if (isStore) {
                    binding.compressionLevelDescription.text = "Level 0 (STORE)"
                } else {
                    updateCompressionLevelDescription(binding.compressionLevelSlider.value.toInt())
                }
            }
        }

        compressionMethods.forEachIndexed { index, method ->
            val chip = com.google.android.material.chip.Chip(requireContext())
            chip.text = method.name
            chip.isCheckable = true
            chip.id = index
            if (method == CompressionMethod.DEFLATE) {
                chip.isChecked = true
            }
            compressionMethodChipGroup.addView(chip)
        }

        binding.compressionLevelSlider.addOnChangeListener { _, value, _ ->
            updateCompressionLevelDescription(value.toInt())
        }
        updateCompressionLevelDescription(5)

        val encryptionMethodChipGroup = binding.encryptionMethodChipGroup
        // Display Names mapping
        val encryptionMethodsMap = mapOf(
            "NONE" to EncryptionMethod.NONE,
            "ZIP STANDARD" to EncryptionMethod.ZIP_STANDARD,
            "ZIP STANDARD STRONG" to EncryptionMethod.ZIP_STANDARD_VARIANT_STRONG,
            "AES" to EncryptionMethod.AES
        )
        val encryptionMethodKeys = encryptionMethodsMap.keys.toList()

        encryptionMethodChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            if (checkedId != null && checkedId < encryptionMethodKeys.size) {
                val methodKey = encryptionMethodKeys[checkedId]
                val method = encryptionMethodsMap[methodKey]

                passwordInput.isEnabled = method != EncryptionMethod.NONE
                confirmPasswordInput.isEnabled = method != EncryptionMethod.NONE

                if (method == EncryptionMethod.AES) {
                    binding.encryptionStrengthContainer.visibility = View.VISIBLE
                } else {
                    binding.encryptionStrengthContainer.visibility = View.GONE
                }
            }
        }

        encryptionMethodKeys.forEachIndexed { index, name ->
            val chip = com.google.android.material.chip.Chip(requireContext())
            chip.text = name
            chip.isCheckable = true
            chip.id = index
            if (name == "NONE") {
                chip.isChecked = true
                passwordInput.isEnabled = false
                confirmPasswordInput.isEnabled = false
            }
            encryptionMethodChipGroup.addView(chip)
        }

        val encryptionStrengthChipGroup = binding.encryptionStrengthChipGroup
        val encryptionStrengthsMap = mapOf(
            "AES 128" to AesKeyStrength.KEY_STRENGTH_128,
            "AES 256" to AesKeyStrength.KEY_STRENGTH_256
        )
        val encryptionStrengthKeys = encryptionStrengthsMap.keys.toList()

        encryptionStrengthKeys.forEachIndexed { index, name ->
            val chip = com.google.android.material.chip.Chip(requireContext())
            chip.text = name
            chip.isCheckable = true
            chip.id = index
            if (name == "AES 256") {
                chip.isChecked = true
            }
            encryptionStrengthChipGroup.addView(chip)
        }

        val mainFragment =
            parentFragmentManager.findFragmentById(com.wirelessalien.zipxtract.R.id.container) as? MainFragment
                ?: return

        binding.okButton.setOnClickListener {
            val archiveName = zipNameEditText.text.toString().ifBlank { defaultName }
            val password = passwordInput.text.toString()
            val confirmPassword = confirmPasswordInput.text.toString()

            if (password != confirmPassword) {
                confirmPasswordInput.error =
                    getString(com.wirelessalien.zipxtract.R.string.passwords_do_not_match)
                return@setOnClickListener
            }
            val isEncryptionEnabled = password.isNotEmpty()

            val selectedMethodChipId = binding.compressionMethodChipGroup.checkedChipId
            val selectedCompressionMethod = if (selectedMethodChipId != -1 && selectedMethodChipId < compressionMethods.size) {
                compressionMethods[selectedMethodChipId]
            } else {
                CompressionMethod.DEFLATE
            }

            val selectedCompressionLevel = if (selectedCompressionMethod == CompressionMethod.STORE) {
                CompressionLevel.NO_COMPRESSION // Or NO_COMPRESSION if available, but Store method overrides level usually
            } else {
                getCompressionLevelFromInt(binding.compressionLevelSlider.value.toInt())
            }

            val selectedEncMethodChipId = binding.encryptionMethodChipGroup.checkedChipId
            val selectedEncryptionMethod = if (selectedEncMethodChipId != -1 && selectedEncMethodChipId < encryptionMethodKeys.size) {
                encryptionMethodsMap[encryptionMethodKeys[selectedEncMethodChipId]]
            } else {
                null
            }

            val selectedEncStrengthChipId = binding.encryptionStrengthChipGroup.checkedChipId
            val selectedEncryptionStrength =
                if (selectedEncryptionMethod != null && selectedEncryptionMethod != EncryptionMethod.NONE && selectedEncStrengthChipId != -1 && selectedEncStrengthChipId < encryptionStrengthKeys.size) {
                    encryptionStrengthsMap[encryptionStrengthKeys[selectedEncStrengthChipId]]
                } else {
                    null
                }

            val isSplitZip = splitZipCheckbox.isChecked
            if (isSplitZip && splitSizeInput.text.toString().isBlank()) {
                splitSizeInput.error = getString(com.wirelessalien.zipxtract.R.string.invalid_value)
                return@setOnClickListener
            }

            val splitSizeText = splitSizeInput.text.toString().toLongOrNull() ?: 64L
            val splitSizeUnit = splitSizeUnitAutoComplete.text.toString()
            val splitZipSize = mainFragment.convertToBytes(splitSizeText, splitSizeUnit)

            if (isSplitZip && splitZipSize < 65536) {
                splitSizeInput.error = getString(com.wirelessalien.zipxtract.R.string.min_split_size_error)
                return@setOnClickListener
            }
            val destinationPath = binding.outputPathInput.text.toString()

            if (!isSplitZip) {
                mainFragment.startZipService(
                    archiveName,
                    password,
                    selectedCompressionMethod,
                    selectedCompressionLevel,
                    isEncryptionEnabled,
                    selectedEncryptionMethod,
                    selectedEncryptionStrength,
                    selectedFilePaths,
                    destinationPath
                )
                dismiss()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val selectedFilesSize = withContext(Dispatchers.IO) {
                    selectedFilePaths.sumOf { File(it).length() }
                }
                val partsCount = mainFragment.getMultiZipPartsCount(selectedFilesSize, splitZipSize)
                if (partsCount > MAX_MULTI_ZIP_PARTS) {
                    splitSizeInput.error = getString(
                        com.wirelessalien.zipxtract.R.string.error_too_many_parts,
                        partsCount
                    )
                    binding.overrideLimitCheckbox.visibility = View.VISIBLE
                    if (!binding.overrideLimitCheckbox.isChecked) {
                        return@launch
                    }
                } else {
                    splitSizeInput.error = null
                    binding.overrideLimitCheckbox.visibility = View.GONE
                }

                mainFragment.startSplitZipService(
                    archiveName,
                    password,
                    selectedCompressionMethod,
                    selectedCompressionLevel,
                    isEncryptionEnabled,
                    selectedEncryptionMethod,
                    selectedEncryptionStrength,
                    selectedFilePaths,
                    splitZipSize,
                    destinationPath
                )
                dismiss()
            }
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }
    }

    companion object {
        private const val ARG_JOB_ID = "arg_job_id"
        private const val MAX_MULTI_ZIP_PARTS = 100L

        fun newInstance(adapter: FileAdapter): ZipOptionDialogFragment {
            val fragment = ZipOptionDialogFragment()
            fragment.adapter = adapter
            fragment.launchedWithFilePaths = false
            return fragment
        }

        fun newInstance(jobId: String): ZipOptionDialogFragment {
            val fragment = ZipOptionDialogFragment()
            val args = Bundle()
            args.putString(ARG_JOB_ID, jobId)
            fragment.arguments = args
            return fragment
        }
    }

    private fun updateCompressionLevelDescription(level: Int) {
        val description = when (level) {
            1 -> "Level 1 (FASTEST)"
            2 -> "Level 2 (FASTER)"
            3 -> "Level 3 (FAST)"
            4 -> "Level 4 (MEDIUM FAST)"
            5 -> "Level 5 (NORMAL)"
            6 -> "Level 6 (HIGHER)"
            7 -> "Level 7 (MAXIMUM)"
            8 -> "Level 8 (PRE ULTRA)"
            9 -> "Level 9 (ULTRA)"
            else -> "Level $level"
        }
        binding.compressionLevelDescription.text = description
    }

    private fun getCompressionLevelFromInt(level: Int): CompressionLevel {
        return when (level) {
            1 -> CompressionLevel.FASTEST
            2 -> CompressionLevel.FASTER
            3 -> CompressionLevel.FAST
            4 -> CompressionLevel.MEDIUM_FAST
            5 -> CompressionLevel.NORMAL
            6 -> CompressionLevel.HIGHER
            7 -> CompressionLevel.MAXIMUM
            8 -> CompressionLevel.PRE_ULTRA
            9 -> CompressionLevel.ULTRA
            else -> CompressionLevel.NORMAL
        }
    }
}