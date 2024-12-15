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
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import androidx.fragment.app.DialogFragment
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.adapter.FileAdapter
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

class CompressionSettingsDialogFragment : DialogFragment() {

    private lateinit var adapter: FileAdapter
    private lateinit var onCompressionSettingsEntered: (String, String?, CompressionMethod, CompressionLevel, Boolean, EncryptionMethod?, AesKeyStrength?, Long?) -> Unit

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.zip_settings_dialog, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val compressionMethodSpinner = view.findViewById<Spinner>(R.id.compression_method_input)
        val compressionLevelSpinner = view.findViewById<Spinner>(R.id.compression_level_input)
        val encryptionMethodSpinner = view.findViewById<Spinner>(R.id.encryption_method_input)
        val encryptionStrengthSpinner = view.findViewById<Spinner>(R.id.encryption_strength_input)
        val passwordInput = view.findViewById<EditText>(R.id.passwordEditText)
        val showPasswordButton = view.findViewById<ImageButton>(R.id.showPasswordButton)
        val zipNameEditText = view.findViewById<EditText>(R.id.zipNameEditText)

        val splitZipCheckbox = view.findViewById<CheckBox>(R.id.splitZipCheckbox)
        val splitSizeInput = view.findViewById<EditText>(R.id.splitSizeEditText)

        val splitSizeUnitSpinner = view.findViewById<Spinner>(R.id.splitSizeUnitSpinner)
        val splitSizeUnits = arrayOf("KB", "MB", "GB")
        val splitSizeUnitAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, splitSizeUnits)
        splitSizeUnitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        splitSizeUnitSpinner.adapter = splitSizeUnitAdapter

        splitZipCheckbox.setOnCheckedChangeListener { _, isChecked ->
            splitSizeInput.isEnabled = isChecked
            if (!isChecked) {
                splitSizeInput.text.clear()
            }
        }

        val compressionMethods = CompressionMethod.entries.filter { it != CompressionMethod.AES_INTERNAL_ONLY }.map { it.name }.toTypedArray()
        val compressionMethodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, compressionMethods)
        compressionMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        compressionMethodSpinner.adapter = compressionMethodAdapter

        val compressionLevels = CompressionLevel.entries.map { it.name }.toTypedArray()
        val compressionLevelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, compressionLevels)
        compressionLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        compressionLevelSpinner.adapter = compressionLevelAdapter

        val encryptionMethods = EncryptionMethod.entries.map { it.name }.toTypedArray()
        val encryptionMethodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, encryptionMethods)
        encryptionMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        encryptionMethodSpinner.adapter = encryptionMethodAdapter

        val encryptionStrengths = AesKeyStrength.entries.filter { it != AesKeyStrength.KEY_STRENGTH_192 }.map { it.name }.toTypedArray()
        val encryptionStrengthAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, encryptionStrengths)
        encryptionStrengthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        encryptionStrengthSpinner.adapter = encryptionStrengthAdapter

        val filesToArchive = adapter.getSelectedFilesPaths()
        var isPasswordVisible = false

        view.findViewById<View>(R.id.okButton).setOnClickListener {
            val defaultName = if (filesToArchive.isNotEmpty()) {
                filesToArchive.first()
            } else {
                ""
            }
            val archiveName = zipNameEditText.text.toString().ifBlank { defaultName }
            val password = passwordInput.text.toString()
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

            val splitSizeText = splitSizeInput.text.toString()
            val splitSizeUnit = splitSizeUnits[splitSizeUnitSpinner.selectedItemPosition]
            val splitZipSize = if (splitZipCheckbox.isChecked) {
                Log.d("SplitZipSize", "Split size: $splitSizeText $splitSizeUnit")
                (activity as MainActivity).convertToBytes(splitSizeText.toLongOrNull() ?: 64, splitSizeUnit)
            } else {
                null
            }

            if (splitZipCheckbox.isChecked) {
                (activity as MainActivity).startSplitZipService(archiveName, password, selectedCompressionMethod, selectedCompressionLevel, isEncryptionEnabled, selectedEncryptionMethod, selectedEncryptionStrength, filesToArchive, splitZipSize)
            } else {
                (activity as MainActivity).startZipService(archiveName, password, selectedCompressionMethod, selectedCompressionLevel, isEncryptionEnabled, selectedEncryptionMethod, selectedEncryptionStrength, filesToArchive)
            }
            dismiss()
        }

        view.findViewById<View>(R.id.cancelButton).setOnClickListener {
            dismiss()
        }

        showPasswordButton.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                showPasswordButton.setImageResource(R.drawable.ic_visibility_on)
            } else {
                passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                showPasswordButton.setImageResource(R.drawable.ic_visibility_off)
            }
        }

        encryptionMethodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedEncryptionMethod = EncryptionMethod.valueOf(encryptionMethods[position])
                passwordInput.isEnabled = selectedEncryptionMethod != EncryptionMethod.NONE
                encryptionStrengthSpinner.isEnabled = selectedEncryptionMethod == EncryptionMethod.AES
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    companion object {
        fun newInstance(adapter: FileAdapter): CompressionSettingsDialogFragment {
            val fragment = CompressionSettingsDialogFragment()
            fragment.adapter = adapter
            return fragment
        }
    }
}