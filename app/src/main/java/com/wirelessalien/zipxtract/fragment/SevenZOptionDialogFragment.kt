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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import androidx.fragment.app.DialogFragment
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.adapter.FileAdapter

class SevenZOptionDialogFragment : DialogFragment() {

    private lateinit var adapter: FileAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout to use as a dialog or embedded fragment.
        return inflater.inflate(R.layout.seven_z_option_dialog, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Remove the dialog title if not needed.
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val passwordEditText = view.findViewById<EditText>(R.id.passwordEditText)
        val compressionSpinner = view.findViewById<Spinner>(R.id.compressionSpinner)
        val solidCheckBox = view.findViewById<CheckBox>(R.id.solidCheckBox)
        val threadCountEditText = view.findViewById<EditText>(R.id.threadCountEditText)
        val archiveNameEditText = view.findViewById<EditText>(R.id.archiveNameEditText)
        val filesToArchive = adapter.getSelectedFilesPaths()

        view.findViewById<View>(R.id.okButton).setOnClickListener {
            val defaultName = if (filesToArchive.isNotEmpty()) {
                filesToArchive.first()
            } else {
                "archive"
            }
            val archiveName = archiveNameEditText.text.toString().ifBlank { defaultName }
            val password = passwordEditText.text.toString()
            val compressionLevel = when (compressionSpinner.selectedItemPosition) {
                0 -> 0
                1 -> 1
                2 -> 3
                3 -> 5
                4 -> 7
                5 -> 9
                else -> -1
            }
            val solid = solidCheckBox.isChecked
            val threadCount = threadCountEditText.text.toString().toIntOrNull() ?: -1

            (activity as MainActivity).startSevenZService(password.ifBlank { null }, archiveName, compressionLevel, solid, threadCount, filesToArchive)
            dismiss()
        }

        view.findViewById<View>(R.id.noPasswordButton).setOnClickListener {
            val defaultName = if (filesToArchive.isNotEmpty()) {
                filesToArchive.first()
            } else {
                "archive"
            }
            val archiveName = archiveNameEditText.text.toString().ifBlank { defaultName }
            val compressionLevel = when (compressionSpinner.selectedItemPosition) {
                0 -> 0
                1 -> 1
                2 -> 3
                3 -> 5
                4 -> 7
                5 -> 9
                else -> -1
            }
            val solid = solidCheckBox.isChecked
            val threadCount = threadCountEditText.text.toString().toIntOrNull() ?: -1

            (activity as MainActivity).startSevenZService(null, archiveName, compressionLevel, solid, threadCount, filesToArchive)
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