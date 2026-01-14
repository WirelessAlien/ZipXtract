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
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FilePickerAdapter
import com.wirelessalien.zipxtract.databinding.DialogPathPickerBinding
import com.wirelessalien.zipxtract.helper.StorageHelper
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

class PathPickerFragment : BottomSheetDialogFragment(), FilePickerAdapter.OnItemClickListener,
    FilePickerAdapter.ActionModeProvider {

    override var actionMode: ActionMode? = null

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private lateinit var sharedPreferences: SharedPreferences
    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private var fileLoadingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface PathPickerListener {
        fun onPathSelected(path: String)
    }

    private lateinit var binding: DialogPathPickerBinding
    private lateinit var adapter: FilePickerAdapter
    private var currentPath: String = Environment.getExternalStorageDirectory().absolutePath
    private var listener: PathPickerListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogPathPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isCancelable = false

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        sortBy = SortBy.valueOf(
            sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name)
                ?: SortBy.SORT_BY_NAME.name
        )
        sortAscending = sharedPreferences.getBoolean("sortAscending", true)

        dialog?.setOnShowListener {
            val bottomSheetDialog = it as BottomSheetDialog
            val bottomSheet =
                bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = false
            }
        }

        // We use FilePickerAdapter but will filter out files, showing only directories
        adapter = FilePickerAdapter(requireContext(), ArrayList())
        adapter.setOnItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnBackTop.setOnClickListener {
            handleBackNavigation()
        }
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
        binding.btnSelect.setOnClickListener {
            listener?.onPathSelected(currentPath)
            dismiss()
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

        loadFiles(currentPath)
        updateCurrentPathChip()

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                handleBackNavigation()
                true
            } else {
                false
            }
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

                val directories = ArrayList<File>()

                fileList.forEach { f ->
                    if (f.isDirectory) {
                        directories.add(f)
                    }
                }

                // Sort files based on current criteria
                when (sortBy) {
                    SortBy.SORT_BY_NAME -> {
                        directories.sortBy { it.name }
                    }

                    SortBy.SORT_BY_SIZE -> {
                        directories.sortBy { it.length() }
                    }

                    SortBy.SORT_BY_MODIFIED -> {
                        directories.sortBy { getFileTimeOfCreation(it) }
                    }

                    SortBy.SORT_BY_EXTENSION -> {
                        directories.sortBy { it.extension }
                    }
                }

                if (!sortAscending) {
                    directories.reverse()
                }

                directories
            }

//            if (path == Environment.getExternalStorageDirectory().absolutePath) {
//                binding.btnAddFolder.visibility = View.GONE
//            } else {
//                binding.btnAddFolder.visibility = View.VISIBLE
//            }

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
        }
    }

    override fun startActionMode(position: Int) {
        // Not needed
    }

    override fun toggleSelection(position: Int) {
        // Not needed
    }

    override fun getSelectedItemCount(): Int {
        return 0
    }

    fun setPathPickerListener(listener: PathPickerListener) {
        this.listener = listener
    }

    companion object {
        fun newInstance(): PathPickerFragment {
            return PathPickerFragment()
        }
    }
}
