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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.adapter.FilePathAdapter
import com.wirelessalien.zipxtract.databinding.ZipOptionDialogBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getStringArrayList(ARG_FILE_PATHS)?.let {
            selectedFilePaths = it.toMutableList()
            launchedWithFilePaths = true
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
                    Toast.makeText(context, "Error: No file information provided.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(requireContext(), com.wirelessalien.zipxtract.R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(requireContext(), com.wirelessalien.zipxtract.R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
                        dismiss()
                    }
                }
            } else {
                Toast.makeText(requireContext(), "File list is fixed for this operation.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.filePathsRv.layoutManager = LinearLayoutManager(context)
        binding.filePathsRv.adapter = filePathAdapter

        if (launchedWithFilePaths) {
            binding.filePathsRv.visibility = View.GONE
            binding.toggleFileViewBtn.visibility = View.GONE
        }

        binding.toggleFileViewBtn.setOnClickListener {
            if (binding.filePathsRv.visibility == View.GONE) {
                binding.filePathsRv.visibility = View.VISIBLE
            } else {
                binding.filePathsRv.visibility = View.GONE
            }
        }

        binding.encInfo.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), com.wirelessalien.zipxtract.R.style.MaterialDialog)
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

        val compressionMethodSpinner = binding.compressionMethodInput
        val compressionLevelSpinner = binding.compressionLevelInput
        val encryptionMethodSpinner = binding.encryptionMethodInput
        val encryptionStrengthSpinner = binding.encryptionStrengthInput
        val passwordInput = binding.passwordEditText
        val confirmPasswordInput = binding.confirmPasswordEditText
        val zipNameEditText = binding.zipNameEditText

        val splitZipCheckbox = binding.splitZipCheckbox
        val splitSizeInput = binding.splitSizeEditText

        val splitSizeUnitSpinner = binding.splitSizeUnitSpinner
        val splitSizeUnits = arrayOf("KB", "MB", "GB")
        val splitSizeUnitAdapter = ArrayAdapter(requireContext(), R.layout.simple_spinner_item, splitSizeUnits)
        splitSizeUnitAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        splitSizeUnitSpinner.adapter = splitSizeUnitAdapter

        splitZipCheckbox.setOnCheckedChangeListener { _, isChecked ->
            splitSizeInput.isEnabled = isChecked
            if (!isChecked) {
                splitSizeInput.text?.clear()
            }
        }

        zipNameEditText.setText(defaultName)
        zipNameEditText.setOnClickListener {
            zipNameEditText.selectAll()
        }

        val compressionMethods = CompressionMethod.entries.filter { it != CompressionMethod.AES_INTERNAL_ONLY }.map { it.name }.toTypedArray()
        val compressionMethodAdapter = ArrayAdapter(requireContext(), R.layout.simple_spinner_item, compressionMethods)
        compressionMethodAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        compressionMethodSpinner.adapter = compressionMethodAdapter

        val compressionLevels = CompressionLevel.entries.map { it.name }.toTypedArray()
        val compressionLevelAdapter = ArrayAdapter(requireContext(), R.layout.simple_spinner_item, compressionLevels)
        compressionLevelAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        compressionLevelSpinner.adapter = compressionLevelAdapter

        val encryptionMethods = EncryptionMethod.entries.map { it.name }.toTypedArray()
        val encryptionMethodAdapter = ArrayAdapter(requireContext(), R.layout.simple_spinner_item, encryptionMethods)
        encryptionMethodAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        encryptionMethodSpinner.adapter = encryptionMethodAdapter

        val encryptionStrengths = AesKeyStrength.entries.filter { it != AesKeyStrength.KEY_STRENGTH_192 }.map { it.name }.toTypedArray()
        val encryptionStrengthAdapter = ArrayAdapter(requireContext(), R.layout.simple_spinner_item, encryptionStrengths)
        encryptionStrengthAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        encryptionStrengthSpinner.adapter = encryptionStrengthAdapter

        val mainFragment = parentFragmentManager.findFragmentById(com.wirelessalien.zipxtract.R.id.container) as? MainFragment
            ?: return

        binding.okButton.setOnClickListener {
            val archiveName = zipNameEditText.text.toString().ifBlank { defaultName }
            val password = passwordInput.text.toString()
            val confirmPassword = confirmPasswordInput.text.toString()

            if (password != confirmPassword) {
                confirmPasswordInput.error = getString(com.wirelessalien.zipxtract.R.string.passwords_do_not_match)
                return@setOnClickListener
            }
            val isEncryptionEnabled = password.isNotEmpty()
            val selectedCompressionMethod = CompressionMethod.valueOf(compressionMethods[compressionMethodSpinner.selectedItemPosition])
            val selectedCompressionLevel = CompressionLevel.valueOf(compressionLevels[compressionLevelSpinner.selectedItemPosition])
            val selectedEncryptionMethod = if (encryptionMethodSpinner.selectedItemPosition != 0) {
                EncryptionMethod.valueOf(encryptionMethods[encryptionMethodSpinner.selectedItemPosition])
            } else {
                null
            }
            val selectedEncryptionStrength = if (selectedEncryptionMethod != null && selectedEncryptionMethod != EncryptionMethod.NONE) {
                AesKeyStrength.valueOf(encryptionStrengths[encryptionStrengthSpinner.selectedItemPosition])
            } else {
                null
            }

            val isSplitZip = splitZipCheckbox.isChecked
            val splitSizeText = splitSizeInput.text.toString().toLongOrNull() ?: 64L
            val splitSizeUnit = splitSizeUnits[splitSizeUnitSpinner.selectedItemPosition]
            val splitZipSize = mainFragment.convertToBytes(splitSizeText, splitSizeUnit)

            if (!isSplitZip) {
                mainFragment.startZipService(
                    archiveName,
                    password,
                    selectedCompressionMethod,
                    selectedCompressionLevel,
                    isEncryptionEnabled,
                    selectedEncryptionMethod,
                    selectedEncryptionStrength,
                    selectedFilePaths
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
                    splitZipSize
                )
                dismiss()
            }
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        encryptionMethodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedEncryptionMethod = EncryptionMethod.valueOf(encryptionMethods[position])
                passwordInput.isEnabled = selectedEncryptionMethod != EncryptionMethod.NONE
                confirmPasswordInput.isEnabled = selectedEncryptionMethod != EncryptionMethod.NONE
                encryptionStrengthSpinner.isEnabled = selectedEncryptionMethod == EncryptionMethod.AES
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    companion object {
        private const val ARG_FILE_PATHS = "arg_file_paths"
        private const val MAX_MULTI_ZIP_PARTS = 100L

        fun newInstance(adapter: FileAdapter): ZipOptionDialogFragment {
            val fragment = ZipOptionDialogFragment()
            fragment.adapter = adapter
            fragment.launchedWithFilePaths = false
            return fragment
        }

        fun newInstance(filePaths: List<String>): ZipOptionDialogFragment {
            val fragment = ZipOptionDialogFragment()
            val args = Bundle()
            args.putStringArrayList(ARG_FILE_PATHS, ArrayList(filePaths))
            fragment.arguments = args
            return fragment
        }
    }
}