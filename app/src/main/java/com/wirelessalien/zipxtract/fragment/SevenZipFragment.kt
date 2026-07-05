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

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.wirelessalien.zipxtract.adapter.ArchiveItemAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.BottomSheetOptionBinding
import com.wirelessalien.zipxtract.databinding.FragmentSevenZipBinding
import com.wirelessalien.zipxtract.databinding.PasswordInputDialogBinding
import com.wirelessalien.zipxtract.helper.AppEvent
import com.wirelessalien.zipxtract.helper.EventBus
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.PathUtils
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.Update7zService
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Date

class SevenZipFragment : Fragment(), ArchiveItemAdapter.OnItemClickListener, FilePickerFragment.FilePickerListener, ArchiveItemAdapter.OnFileLongClickListener {

    private var actionMode: ActionMode? = null

    data class ArchiveItem(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Date?,
        val isEncrypted: Boolean
    )

    private lateinit var binding: FragmentSevenZipBinding
    private lateinit var fileOperationsDao: FileOperationsDao
    private var archivePath: String? = null
    private lateinit var adapter: ArchiveItemAdapter
    private var inArchive: IInArchive? = null
    private var currentPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            archivePath = it.getString(ARG_ARCHIVE_PATH)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSevenZipBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fileOperationsDao = FileOperationsDao(requireContext())

        val activity = activity as AppCompatActivity
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        activity.supportActionBar?.title = File(archivePath ?: "Archive").name
        activity.findViewById<View>(R.id.tabLayout)?.visibility = View.GONE

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        binding.cancelBtn.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        adapter = ArchiveItemAdapter(requireContext(), emptyList())
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }

        val initialFabMarginBottom = (binding.fabAddFile.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val initialFabMarginRight = (binding.fabAddFile.layoutParams as ViewGroup.MarginLayoutParams).rightMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabAddFile) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.layoutParams = (v.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = initialFabMarginBottom + insets.bottom
                rightMargin = initialFabMarginRight + insets.right
            }
            windowInsets
        }

        try {
            val randomAccessFile = RandomAccessFile(archivePath, "r")
            inArchive = SevenZip.openInArchive(null, RandomAccessFileInStream(randomAccessFile))
            loadArchiveItems(currentPath)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(),
                getString(R.string.error_opening_archive, e.message), Toast.LENGTH_LONG).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ArchiveProgress -> {
                        binding.progressBar.progress = event.progress
                    }
                    is AppEvent.ArchiveComplete -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(),
                            getString(R.string.archive_updated_successfully), Toast.LENGTH_SHORT).show()
                        try {
                            inArchive?.close()
                            val randomAccessFile = RandomAccessFile(archivePath, "r")
                            inArchive = SevenZip.openInArchive(null, RandomAccessFileInStream(randomAccessFile))
                            loadArchiveItems(currentPath)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    is AppEvent.ArchiveError -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(),
                            getString(R.string.error_updating_archive), Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        binding.fabAddFile.setOnClickListener {
            val filePicker = FilePickerFragment.newInstance()
            filePicker.setFilePickerListener(this)
            filePicker.show(parentFragmentManager, "file_picker")
        }
        updateCurrentPathChip()

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // No menu items to add for now
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return if (menuItem.itemId == android.R.id.home) {
                    handleBackNavigation()
                    true
                } else {
                    false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun updateCurrentPathChip() {
        binding.chipGroupPath.removeAllViews()
        val pathParts = currentPath.split("/").filter { it.isNotEmpty() }
        var cumulativePath = ""

        // root chip
        val rootChip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
        rootChip.text = "/"
        rootChip.setOnClickListener {
            loadArchiveItems("")
        }
        binding.chipGroupPath.addView(rootChip)

        for (part in pathParts) {
            cumulativePath += if (cumulativePath.isEmpty()) part else "/$part"
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
            chip.text = part
            val pathToLoad = cumulativePath
            chip.setOnClickListener {
                loadArchiveItems(pathToLoad)
            }
            binding.chipGroupPath.addView(chip)
        }

        binding.horizontalScrollView.post {
            binding.horizontalScrollView.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun loadArchiveItems(path: String) {
        currentPath = path
        updateCurrentPathChip()

        val children = mutableMapOf<String, ArchiveItem>()

        inArchive?.let {
            val count = it.numberOfItems
            for (i in 0 until count) {
                val itemPath = it.getProperty(i, PropID.PATH) as String

                if (itemPath.replace("\\", "/").let { p -> p.startsWith(currentPath) && p != currentPath && (currentPath.isEmpty() || p.substring(currentPath.length).removePrefix("/").isNotEmpty()) }) {
                    val relativePath = itemPath.substring(currentPath.length).removePrefix("/")

                    val separatorIndex = relativePath.indexOf('/')
                    if (separatorIndex > -1) {
                        // It's in a subdirectory. We only care about the subdirectory itself.
                        val dirName = relativePath.substring(0, separatorIndex)
                        val dirPath = if (currentPath.isEmpty()) dirName else "$currentPath/$dirName"
                        if (!children.containsKey(dirPath)) {
                            children[dirPath] = ArchiveItem(dirPath, true, 0, null, false)
                        }
                    } else {
                        // It's a direct child file or an empty directory entry
                        val isDirectory = it.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                        val size = it.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val lastModified = it.getProperty(i, PropID.LAST_MODIFICATION_TIME) as? Date
                        val isEncrypted = it.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false
                        if (!children.containsKey(itemPath)) {
                            children[itemPath] = ArchiveItem(itemPath, isDirectory, size, lastModified, isEncrypted)
                        }
                    }
                }
            }
        }
        adapter.updateItems(children.values.toList().sortedBy { it.path })
    }

    override fun onItemClick(item: ArchiveItem) {
        if (actionMode != null) {
            val position = adapter.itemCount.let { count ->
                (0 until count).firstOrNull { adapter.getItem(it) == item }
            }
            if (position != null) {
                toggleSelection(position)
            }
        } else {
            if (item.isDirectory) {
                loadArchiveItems(item.path)
            } else {
                showBottomSheetOptions(item)
            }
        }
    }

    override fun onFileLongClick(item: ArchiveItem, view: View) {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(actionModeCallback)
        }
        val position = adapter.itemCount.let { count ->
            (0 until count).firstOrNull { adapter.getItem(it) == item }
        }
        if (position != null) {
            toggleSelection(position)
        }
    }

    private fun toggleSelection(position: Int) {
        adapter.toggleSelection(position)
        val count = adapter.getSelectedItems().size
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = "$count selected"
            actionMode?.invalidate()
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_archive_action, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.menu_action_delete -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.confirm_delete))
                        .setMessage(getString(R.string.confirm_delete_message))
                        .setNegativeButton(resources.getString(R.string.cancel)) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setPositiveButton(resources.getString(R.string.delete)) { _, _ ->
                            binding.progressBar.visibility = View.VISIBLE
                            val selectedItems = adapter.getSelectedItems()
                            val pathsToRemove = selectedItems.map { it.path }
                            val jobId = fileOperationsDao.addFilesForJob(pathsToRemove)
                            val intent = Intent(requireContext(), Update7zService::class.java).apply {
                                putExtra(ServiceConstants.EXTRA_ARCHIVE_PATH, archivePath)
                                putExtra(ServiceConstants.EXTRA_ITEMS_TO_REMOVE_JOB_ID, jobId)
                            }
                            requireContext().startService(intent)
                            mode?.finish()
                        }
                        .show()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            adapter.clearSelection()
        }
    }

    override fun onFilesSelected(files: List<File>) {
        binding.progressBar.visibility = View.VISIBLE
        val filePairs = files.map { it.absolutePath to (if (currentPath.isEmpty()) it.name else "$currentPath/${it.name}") }
        val jobId = fileOperationsDao.addFilePairsForJob(filePairs)

        val intent = Intent(requireContext(), Update7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_ARCHIVE_PATH, archivePath)
            putExtra(ServiceConstants.EXTRA_ITEMS_TO_ADD_JOB_ID, jobId)
        }
        requireContext().startService(intent)
    }

    private fun showBottomSheetOptions(item: ArchiveItem) {
        val binding = BottomSheetOptionBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        bottomSheetDialog.setContentView(binding.root)

        binding.fileName.text = item.path.substringAfterLast('/')
        val fileSizeText = bytesToString(item.size)
        binding.fileSize.text = fileSizeText

        val dateFormat = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.DEFAULT,
            java.text.DateFormat.SHORT,
            java.util.Locale.getDefault()
        )
        binding.fileDate.text = item.lastModified?.let { dateFormat.format(it) } ?: ""

        val extension = item.path.substringAfterLast('.', "")
        binding.fileExtension.text = if (extension.isNotEmpty()) {
            if (extension.length > 4) {
                "FILE"
            } else {
                if (extension.length == 4) {
                    binding.fileExtension.textSize = 16f
                } else {
                    binding.fileExtension.textSize = 18f
                }
                extension.uppercase(java.util.Locale.getDefault())
            }
        } else {
            "..."
        }

        binding.btnPreviewArchive.visibility = View.GONE
        binding.btnShare.visibility = View.GONE
        binding.btnOpenWith.visibility = View.GONE
        binding.btnFileInfo.visibility = View.GONE
        binding.btnDelete.visibility = View.GONE
        binding.lowStorageWarning.visibility = View.GONE

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val extractPath = sharedPreferences.getString(BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH, null)
        val defaultPath = if (!extractPath.isNullOrEmpty()) {
            if (File(extractPath).isAbsolute) {
                extractPath
            } else {
                File(android.os.Environment.getExternalStorageDirectory(), extractPath).absolutePath
            }
        } else {
            File(archivePath ?: android.os.Environment.getExternalStorageDirectory().absolutePath).parent ?: android.os.Environment.getExternalStorageDirectory().absolutePath
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
        
        binding.outputPathInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val path = s.toString()
                binding.outputPathDisplay.text = PathUtils.formatPath(path, requireContext())
            }
        })

        binding.btnExtract.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            if (item.isEncrypted) {
                showPasswordInputDialog(item, destinationPath)
            } else {
                startExtractionService(item, null, destinationPath)
            }
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun showPasswordInputDialog(item: ArchiveItem, destinationPath: String) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startExtractionService(item, password.ifBlank { null }, destinationPath)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startExtractionService(item, null, destinationPath)
            }
            .show()
    }

    private fun startExtractionService(item: ArchiveItem, password: String?, destinationPath: String) {
        val path = archivePath ?: return
        val jobId = fileOperationsDao.addFilesForJob(listOf(path))
        val itemsToExtract = ArrayList<String>()
        itemsToExtract.add(item.path)
        
        val intent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
            putStringArrayListExtra(ServiceConstants.EXTRA_ITEMS_TO_EXTRACT, itemsToExtract)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun bytesToString(bytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            bytes < kilobyte -> "$bytes B"
            bytes < megabyte -> String.format(java.util.Locale.US, "%.2f KB", bytes.toFloat() / kilobyte)
            bytes < gigabyte -> String.format(java.util.Locale.US, "%.2f MB", bytes.toFloat() / megabyte)
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes.toFloat() / gigabyte)
        }
    }

    private fun handleBackNavigation() {
        if (currentPath.isNotEmpty()) {
            currentPath = currentPath.substringBeforeLast('/', "")
            loadArchiveItems(currentPath)
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val activity = activity as? AppCompatActivity
        activity?.findViewById<View>(R.id.tabLayout)?.visibility = View.VISIBLE
        activity?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        activity?.supportActionBar?.title = getString(R.string.app_name)
    }

    override fun onDestroy() {
        super.onDestroy()
        inArchive?.close()
    }

    companion object {
        private const val ARG_ARCHIVE_PATH = "archive_path"

        @JvmStatic
        fun newInstance(archivePath: String) =
            SevenZipFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARCHIVE_PATH, archivePath)
                }
            }
    }
}