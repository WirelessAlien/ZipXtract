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
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.view.ActionMode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wirelessalien.zipxtract.adapter.FilePickerAdapter
import com.wirelessalien.zipxtract.databinding.DialogFilePickerBinding
import com.wirelessalien.zipxtract.helper.StorageHelper
import com.wirelessalien.zipxtract.viewmodel.PickerViewModel
import kotlinx.coroutines.launch
import java.io.File

class FilePickerFragment : BottomSheetDialogFragment(), FilePickerAdapter.OnItemClickListener, FilePickerAdapter.ActionModeProvider {

    override var actionMode: ActionMode? = null

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private lateinit var sharedPreferences: SharedPreferences
    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private val viewModel: PickerViewModel by viewModels()

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
        isCancelable = false

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        sortBy = SortBy.valueOf(sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name) ?: SortBy.SORT_BY_NAME.name)
        sortAscending = sharedPreferences.getBoolean("sortAscending", true)

        adapter = FilePickerAdapter(requireContext(), ArrayList())
        adapter.setOnItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }

        binding.btnBackTop.setOnClickListener {
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

        binding.storageChip.setOnLongClickListener {
            showStoragePopupMenu(it)
            true
        }

        binding.storageChip.setOnClickListener {
            val sdCardPath = StorageHelper.getSdCardPath(requireContext())
            val basePath = Environment.getExternalStorageDirectory().absolutePath
            val currentPathToCheck = currentPath

            val targetPath = if (sdCardPath != null && currentPathToCheck.startsWith(sdCardPath)) {
                sdCardPath
            } else {
                basePath
            }
            if (currentPathToCheck != targetPath) {
                loadFiles(targetPath)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.files.collect { files ->
                        adapter.updateFilesAndFilter(ArrayList(files.map { it.file }))
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        if (isLoading) {
                            binding.shimmerViewContainer.startShimmer()
                            binding.shimmerViewContainer.visibility = View.VISIBLE
                            binding.recyclerView.visibility = View.GONE
                        } else {
                            binding.shimmerViewContainer.stopShimmer()
                            binding.shimmerViewContainer.visibility = View.GONE
                            binding.recyclerView.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        loadFiles(currentPath)
        updateCurrentPathChip()
        updateSelectedCount()

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                handleBackNavigation()
                true
            } else {
                false
            }
        }
    }

    private fun updateSelectedCount() {
        val selectedCount = adapter.getSelectedItems().size
        if (selectedCount > 0) {
            binding.btnSelect.text = getString(R.string.select_files, selectedCount)
        } else {
            binding.btnSelect.text = getString(R.string.add)
        }
    }

    private fun showStoragePopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        val internalStorage = getString(R.string.internal_storage)
        val sdCardString = getString(R.string.sd_card)
        val sdCardPath = StorageHelper.getSdCardPath(requireContext())

        popup.menu.add(0, 1, 0, internalStorage)
        if (sdCardPath != null) {
            popup.menu.add(0, 2, 1, sdCardString)
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    loadFiles(Environment.getExternalStorageDirectory().absolutePath)
                    true
                }
                2 -> {
                    if (sdCardPath != null) loadFiles(sdCardPath)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun updateCurrentPathChip() {
        val internalStorage = getString(R.string.internal_storage)
        val sdCardString = getString(R.string.sd_card)
        val sdCardPath = StorageHelper.getSdCardPath(requireContext())
        val basePath = Environment.getExternalStorageDirectory().absolutePath
        val isSdCard = sdCardPath != null && currentPath.startsWith(sdCardPath)

        // Update storageChip text based on current root
        if (isSdCard) {
            binding.storageChip.text = sdCardString
        } else {
            binding.storageChip.text = internalStorage
        }
        binding.storageChip.isChipIconVisible = true

        val displayPath = when {
            currentPath.startsWith(basePath) -> currentPath.replace(basePath, internalStorage)
            isSdCard -> currentPath.replace(sdCardPath, sdCardString)
            else -> currentPath
        }.split("/").filter { it.isNotEmpty() }

        binding.chipGroupPath.removeAllViews()

        var pathAccumulator = ""

        for ((index, part) in displayPath.withIndex()) {
            if (index == 0) {
                if (part == internalStorage) pathAccumulator = basePath
                else if (part == sdCardString && isSdCard) pathAccumulator = sdCardPath
                else pathAccumulator = "/$part"

                continue
            } else {
                pathAccumulator = if (pathAccumulator.endsWith("/")) "$pathAccumulator$part" else "$pathAccumulator/$part"
            }

            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
            chip.text = part

            val finalPathForChip = pathAccumulator

            chip.setOnClickListener {
                loadFiles(finalPathForChip)
            }
            binding.chipGroupPath.addView(chip)
        }
    }

    private fun handleBackNavigation() {
        val sdCardPath = StorageHelper.getSdCardPath(requireContext())
        val basePath = Environment.getExternalStorageDirectory().absolutePath
        val file = File(currentPath)
        if (file.absolutePath == basePath || (sdCardPath != null && file.absolutePath == sdCardPath)) {
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
        viewModel.loadFiles(path, sortBy.name, sortAscending, onlyDirectories = false)

        val sdCardPath = StorageHelper.getSdCardPath(requireContext())
        if (path == Environment.getExternalStorageDirectory().absolutePath || (sdCardPath != null && path == sdCardPath)) {
            binding.btnAddFolder.visibility = View.GONE
        } else {
            binding.btnAddFolder.visibility = View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        val bottomSheetDialog = dialog as? BottomSheetDialog
        bottomSheetDialog?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        val bottomSheet = bottomSheetDialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { sheet ->
            val behavior = BottomSheetBehavior.from(sheet)
            sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.isDraggable = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
