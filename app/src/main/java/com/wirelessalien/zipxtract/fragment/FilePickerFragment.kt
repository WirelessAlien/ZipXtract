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

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FilePickerAdapter
import com.wirelessalien.zipxtract.databinding.DialogFilePickerBinding
import java.io.File

class FilePickerFragment : BottomSheetDialogFragment(), FilePickerAdapter.OnItemClickListener, FilePickerAdapter.ActionModeProvider {

    override var actionMode: ActionMode? = null

    interface FilePickerListener {
        fun onFilesSelected(files: List<File>)
    }

    private lateinit var binding: DialogFilePickerBinding
    private lateinit var adapter: FilePickerAdapter
    private var currentPath: String = Environment.getExternalStorageDirectory().absolutePath
    private var listener: FilePickerListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogFilePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FilePickerAdapter(requireContext(), this, ArrayList(), selectionMode = FilePickerAdapter.SelectionMode.MULTIPLE)
        adapter.setOnItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.backBtn.setOnClickListener {
            handleBackNavigation()
        }
        binding.backFab.setOnClickListener {
            handleBackNavigation()
        }
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
        binding.btnSelect.setOnClickListener {
            val selectedFiles = adapter.getSelectedItems()
            if (selectedFiles.isNotEmpty()) {
                listener?.onFilesSelected(selectedFiles)
            }
            dismiss()
        }

        loadFiles(currentPath)
    }

    private fun handleBackNavigation() {
        val file = File(currentPath)
        val parent = file.parentFile
        if (parent != null && parent.canRead()) {
            loadFiles(parent.absolutePath)
        } else {
            dismiss()
        }
    }

    private fun loadFiles(path: String) {
        currentPath = path
        binding.currentPath.text = currentPath
        val file = File(path)
        val files = file.listFiles()?.toList() ?: emptyList()
        adapter.updateFilesAndFilter(ArrayList(files.sortedBy { it.name }))
    }

    override fun onItemClick(file: File, filePath: String) {
        if (actionMode != null) {
            val position = adapter.files.indexOf(file)
            if (position != -1) {
                toggleSelection(position)
            }
        } else {
            if (file.isDirectory) {
                loadFiles(file.absolutePath)
            }
        }
    }

    override fun startActionMode(position: Int) {
        if (actionMode == null) {
            actionMode = (activity as? androidx.appcompat.app.AppCompatActivity)?.startSupportActionMode(actionModeCallback)
        }
        toggleSelection(position)
    }

    override fun toggleSelection(position: Int) {
        adapter.toggleSelection(position)
        val count = getSelectedItemCount()
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = "$count selected"
        }
    }

    override fun getSelectedItemCount(): Int {
        return adapter.getSelectedItems().size
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: android.view.Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_archive_action, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: android.view.Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: android.view.MenuItem?): Boolean {
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            adapter.clearSelection()
        }
    }


    fun setFilePickerListener(listener: FilePickerListener) {
        this.listener = listener
    }

    companion object {
        fun newInstance(): FilePickerFragment {
            return FilePickerFragment()
        }
    }
}
