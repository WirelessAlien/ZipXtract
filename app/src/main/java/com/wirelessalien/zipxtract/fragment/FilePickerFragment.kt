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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
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

        adapter = FilePickerAdapter(requireContext(), ArrayList())
        adapter.setOnItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.backChip.setOnClickListener {
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
        binding.fabSelectAll.setOnClickListener {
            if (adapter.getSelectedItems().size == adapter.itemCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
            updateFabIcon()
        }

        loadFiles(currentPath)
        updateCurrentPathChip()
    }

    private fun updateFabIcon() {
        if (adapter.getSelectedItems().size == adapter.itemCount) {
            binding.fabSelectAll.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_close))
        } else {
            binding.fabSelectAll.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_select_all))
        }
    }

    private fun updateCurrentPathChip() {
        binding.chipGroupPath.removeAllViews()
        val pathParts = currentPath.split("/").filter { it.isNotEmpty() }
        var cumulativePath = ""

        val rootFile = Environment.getExternalStorageDirectory()
        // root chip
        val rootChip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
        rootChip.text = getString(R.string.internal_storage)
        rootChip.setOnClickListener {
            loadFiles(rootFile.absolutePath)
        }
        binding.chipGroupPath.addView(rootChip)

        for (part in pathParts) {
            if (part == rootFile.name) continue
            cumulativePath += "/$part"
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
            chip.text = part
            chip.setOnClickListener {
                loadFiles(rootFile.absolutePath + cumulativePath)
            }
            binding.chipGroupPath.addView(chip)
        }
    }

    private fun handleBackNavigation() {
        val file = File(currentPath)
        if (file.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
            dismiss()
            return
        }
        val parent = file.parentFile
        if (parent != null && parent.canRead()) {
            loadFiles(parent.absolutePath)
        } else {
            dismiss()
        }
    }

    private fun loadFiles(path: String) {
        currentPath = path
        updateCurrentPathChip()
        val file = File(path)
        val files = file.listFiles()?.toList() ?: emptyList()
        adapter.updateFilesAndFilter(ArrayList(files.sortedBy { it.name }))
    }

    override fun onItemClick(file: File, filePath: String) {
        if (file.isDirectory) {
            loadFiles(file.absolutePath)
        } else {
            toggleSelection(adapter.files.indexOf(file))
        }
    }

    override fun startActionMode(position: Int) {
        // Not needed
    }

    override fun toggleSelection(position: Int) {
        if (adapter.getSelectedItems().size >= 2) {
            binding.fabSelectAll.visibility = View.VISIBLE
        } else {
            binding.fabSelectAll.visibility = View.GONE
        }
        updateFabIcon()
    }

    override fun getSelectedItemCount(): Int {
        return adapter.getSelectedItems().size
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
