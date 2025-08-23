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

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FilePickerAdapter
import com.wirelessalien.zipxtract.databinding.DialogFilePickerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class FilePickerFragment : BottomSheetDialogFragment(), FilePickerAdapter.OnItemClickListener, FilePickerAdapter.ActionModeProvider {

    override var actionMode: ActionMode? = null

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private lateinit var sharedPreferences: SharedPreferences
    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private var fileLoadingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())


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

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        sortBy = SortBy.valueOf(sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name) ?: SortBy.SORT_BY_NAME.name)
        sortAscending = sharedPreferences.getBoolean("sortAscending", true)

        dialog?.setOnShowListener {
            val bottomSheetDialog = it as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = false
            }
        }

        adapter = FilePickerAdapter(requireContext(), ArrayList())
        adapter.setOnItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.fabBack.setOnClickListener {
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
        binding.btnAddFolder.setOnClickListener {
            val folder = File(currentPath)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.add_folder_confirmation_title))
                .setMessage(getString(R.string.add_folder_confirmation_message, folder.name))
                .setNegativeButton(resources.getString(R.string.cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .setPositiveButton(resources.getString(R.string.add)) { _, _ ->
                    listener?.onFilesSelected(listOf(folder))
                    dismiss()
                }
                .show()
        }
        loadFiles(currentPath)
        updateCurrentPathChip()
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        val selectedCount = adapter.getSelectedItems().size
        if (selectedCount > 0) {
            binding.btnSelect.text = getString(R.string.select_files, selectedCount)
        } else {
            binding.btnSelect.text = getString(R.string.add)
        }
    }

    private fun updateCurrentPathChip() {
        binding.chipGroupPath.removeAllViews()
        val rootPath = Environment.getExternalStorageDirectory().absolutePath

        // root chip
        val rootChip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
        rootChip.text = getString(R.string.internal_storage)
        rootChip.setOnClickListener {
            loadFiles(rootPath)
        }
        binding.chipGroupPath.addView(rootChip)

        val relativePath = currentPath.removePrefix(rootPath)
        val pathParts = relativePath.split("/").filter { it.isNotEmpty() }
        var cumulativePath = rootPath

        for (part in pathParts) {
            cumulativePath += "/$part"
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
            chip.text = part
            val pathToLoad = cumulativePath
            chip.setOnClickListener {
                loadFiles(pathToLoad)
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

    private fun getFileTimeOfCreation(file: File): Long {
        return if (file.exists()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                attr.lastModifiedTime().toMillis()
            } else {
                file.lastModified()
            }
        } else {
            0L
        }
    }

    private fun loadFiles(path: String) {
        fileLoadingJob?.cancel()

        currentPath = path
        updateCurrentPathChip()

        binding.shimmerViewContainer.startShimmer()
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        fileLoadingJob = coroutineScope.launch {
            val combinedList = withContext(Dispatchers.IO) {
                val file = File(path)
                val fileList = file.listFiles()?.toList() ?: emptyList()

                val files = ArrayList<File>()
                val directories = ArrayList<File>()

                fileList.forEach { f ->
                    if (f.isDirectory) {
                        directories.add(f)
                    } else {
                        files.add(f)
                    }
                }

                // Sort files based on current criteria
                when (sortBy) {
                    SortBy.SORT_BY_NAME -> {
                        directories.sortBy { it.name }
                        files.sortBy { it.name }
                    }
                    SortBy.SORT_BY_SIZE -> {
                        directories.sortBy { it.length() }
                        files.sortBy { it.length() }
                    }
                    SortBy.SORT_BY_MODIFIED -> {
                        directories.sortBy { getFileTimeOfCreation(it) }
                        files.sortBy { getFileTimeOfCreation(it) }
                    }
                    SortBy.SORT_BY_EXTENSION -> {
                        directories.sortBy { it.extension }
                        files.sortBy { it.extension }
                    }
                }

                if (!sortAscending) {
                    directories.reverse()
                    files.reverse()
                }

                val resultList = ArrayList<File>()
                resultList.addAll(directories)
                resultList.addAll(files)
                resultList
            }

            if (path == Environment.getExternalStorageDirectory().absolutePath) {
                binding.btnAddFolder.visibility = View.GONE
            } else {
                binding.btnAddFolder.visibility = View.VISIBLE
            }

            adapter.updateFilesAndFilter(combinedList)

            binding.shimmerViewContainer.stopShimmer()
            binding.shimmerViewContainer.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fileLoadingJob?.cancel()
        coroutineScope.cancel()
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
        updateSelectedCount()
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
