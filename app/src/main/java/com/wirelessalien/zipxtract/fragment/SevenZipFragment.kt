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

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.ArchiveItemAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.BottomSheetOptionBinding
import com.wirelessalien.zipxtract.databinding.FragmentSevenZipBinding
import com.wirelessalien.zipxtract.databinding.PasswordInputDialogBinding
import com.wirelessalien.zipxtract.helper.AppEvent
import com.wirelessalien.zipxtract.helper.DeletionSafetyHelper
import com.wirelessalien.zipxtract.helper.EventBus
import com.wirelessalien.zipxtract.helper.FileIconHelper
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.HapticHelper
import com.wirelessalien.zipxtract.helper.PathUtils
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.Update7zService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private data class RawEntry(
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
    private var randomAccessFile: RandomAccessFile? = null
    private var inStream: RandomAccessFileInStream? = null
    private var inArchive: IInArchive? = null
    private var cachedEntries: List<RawEntry> = emptyList()
    private var currentPath: String = ""
    private var currentSearchQuery: String = ""

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

        binding.btnExtractSelected.setOnClickListener {
            val selectedItems = adapter.getSelectedItems()
            if (selectedItems.isNotEmpty()) {
                showBottomSheetOptions(selectedItems)
            }
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

        val initialBottomMargin = (binding.bottomBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val initialRightMargin = (binding.bottomBar.layoutParams as ViewGroup.MarginLayoutParams).rightMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.layoutParams = (v.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = initialBottomMargin + insets.bottom
                rightMargin = initialRightMargin + insets.right
            }
            windowInsets
        }

        binding.searchArchiveInput.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            if (query != currentSearchQuery) {
                currentSearchQuery = query
                applySearchFilter()
            }
        }

        binding.searchArchiveInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.searchArchiveInput.windowToken, 0)
                binding.searchArchiveInput.clearFocus()
                true
            } else {
                false
            }
        }

        openArchiveAndLoad()

        viewLifecycleOwner.lifecycleScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ArchiveProgress -> {
                        binding.progressBar.progress = event.progress
                    }
                    is AppEvent.ArchiveComplete -> {
                        binding.progressBar.visibility = View.GONE
                        HapticHelper.performConfirm(binding.root)
                        Toast.makeText(requireContext(),
                            getString(R.string.archive_updated_successfully), Toast.LENGTH_SHORT).show()
                        openArchiveAndLoad()
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
                menu.clear()
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
            binding.chipGroupPath.addView(com.wirelessalien.zipxtract.helper.PathUtils.createBreadcrumbSeparator(requireContext()))
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

    private fun openArchiveAndLoad() {
        val path = archivePath ?: return
        binding.progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    closeArchive()
                    val raf = RandomAccessFile(path, "r")
                    randomAccessFile = raf
                    val stream = RandomAccessFileInStream(raf)
                    inStream = stream
                    val archive = SevenZip.openInArchive(null, stream)
                    inArchive = archive

                    val count = archive.numberOfItems
                    val entries = ArrayList<RawEntry>(count)
                    for (i in 0 until count) {
                        val rawPath = archive.getStringProperty(i, PropID.PATH) ?: ""
                        val normalized = rawPath.replace("\\", "/")
                        val isDir = java.lang.Boolean.TRUE == archive.getProperty(i, PropID.IS_FOLDER)
                        val size = archive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val lastModified = archive.getProperty(i, PropID.LAST_MODIFICATION_TIME) as? Date
                        val isEncrypted = archive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false
                        entries.add(RawEntry(normalized, isDir, size, lastModified, isEncrypted))
                    }
                    cachedEntries = entries
                }
                binding.progressBar.visibility = View.GONE
                if (currentSearchQuery.isNotEmpty()) {
                    applySearchFilter()
                } else {
                    loadArchiveItems(currentPath)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                e.printStackTrace()
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_opening_archive, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun closeArchive() {
        try {
            inArchive?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inArchive = null
        }
        try {
            inStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inStream = null
        }
        try {
            randomAccessFile?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            randomAccessFile = null
        }
    }

    private fun loadArchiveItems(path: String) {
        currentPath = path
        updateCurrentPathChip()

        if (currentSearchQuery.isNotEmpty()) {
            applySearchFilter()
            return
        }

        adapter.isSearchMode = false
        binding.horizontalScrollView.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE

        val children = mutableMapOf<String, ArchiveItem>()

        for (entry in cachedEntries) {
            val itemPath = entry.path
            if (itemPath.let { p -> p.startsWith(currentPath) && p != currentPath && (currentPath.isEmpty() || p.substring(currentPath.length).removePrefix("/").isNotEmpty()) }) {
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
                    if (!children.containsKey(itemPath)) {
                        children[itemPath] = ArchiveItem(itemPath, entry.isDirectory, entry.size, entry.lastModified, entry.isEncrypted)
                    }
                }
            }
        }
        adapter.updateItems(children.values.toList().sortedBy { it.path })
    }

    private fun applySearchFilter() {
        if (currentSearchQuery.isEmpty()) {
            adapter.isSearchMode = false
            binding.horizontalScrollView.visibility = View.VISIBLE
            loadArchiveItems(currentPath)
            return
        }

        adapter.isSearchMode = true
        binding.horizontalScrollView.visibility = View.GONE

        val filtered = cachedEntries.filter { entry ->
            val name = entry.path.removeSuffix("/").substringAfterLast('/')
            name.contains(currentSearchQuery, ignoreCase = true) || entry.path.contains(currentSearchQuery, ignoreCase = true)
        }.map { entry ->
            ArchiveItem(entry.path, entry.isDirectory, entry.size, entry.lastModified, entry.isEncrypted)
        }.sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))

        if (filtered.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
        adapter.updateItems(filtered)
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
                if (currentSearchQuery.isNotEmpty()) {
                    binding.searchArchiveInput.text?.clear()
                }
                loadArchiveItems(item.path)
            } else {
                showBottomSheetOptions(listOf(item))
            }
        }
    }

    override fun onFileLongClick(item: ArchiveItem, view: View) {
        HapticHelper.performLongPress(view)
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(actionModeCallback)
        }
        val position = adapter.itemCount.let { count ->
            (0 until count).firstOrNull { adapter.getItem(it) == item }
        }
        if (position != null) {
            toggleSelection(position, view)
        }
    }

    private fun toggleSelection(position: Int, view: View? = null) {
        if (view != null) {
            HapticHelper.performContextClick(view)
        }
        adapter.toggleSelection(position)
        updateActionModeTitle()
    }

    private fun updateActionModeTitle() {
        val selectedItems = adapter.getSelectedItems()
        val count = selectedItems.size
        if (count == 0) {
            actionMode?.finish()
            updateBottomBar(false)
        } else {
            val totalBytes = selectedItems.sumOf { it.size }
            val sizeString = bytesToString(totalBytes)
            val countLabel = if (count == 1) getString(R.string.item) else getString(R.string.items)
            actionMode?.title = getString(R.string.selection_status_format, count, countLabel, sizeString)
            actionMode?.invalidate()
            updateBottomBar(true)
        }
    }

    private fun updateBottomBar(inActionMode: Boolean) {
        if (inActionMode) {
            binding.fabAddFile.visibility = View.GONE
            binding.cancelBtn.visibility = View.GONE
            binding.btnExtractSelected.visibility = View.VISIBLE
        } else {
            binding.fabAddFile.visibility = View.VISIBLE
            binding.cancelBtn.visibility = View.VISIBLE
            binding.btnExtractSelected.visibility = View.GONE
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_archive_action, menu)
            updateBottomBar(true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.menu_action_extract_selected -> {
                    val selectedItems = adapter.getSelectedItems()
                    if (selectedItems.isNotEmpty()) {
                        showBottomSheetOptions(selectedItems)
                    }
                    true
                }
                R.id.menu_action_select_all -> {
                    adapter.selectAll()
                    updateActionModeTitle()
                    HapticHelper.performConfirm(view)
                    true
                }
                R.id.menu_action_invert_selection -> {
                    adapter.invertSelection()
                    val count = adapter.getSelectedItems().size
                    if (count == 0) {
                        actionMode?.finish()
                    } else {
                        updateActionModeTitle()
                    }
                    HapticHelper.performConfirm(view)
                    true
                }
                R.id.menu_action_delete -> {
                    val selectedItems = adapter.getSelectedItems()
                    val totalSize = selectedItems.sumOf { it.size }
                    DeletionSafetyHelper.showArchiveDeleteConfirmationSheet(
                        requireContext(),
                        selectedItems.size,
                        totalSize
                    ) {
                        binding.progressBar.visibility = View.VISIBLE
                        val pathsToRemove = selectedItems.map { it.path }
                        val jobId = fileOperationsDao.addFilesForJob(pathsToRemove)
                        val intent = Intent(requireContext(), Update7zService::class.java).apply {
                            putExtra(ServiceConstants.EXTRA_ARCHIVE_PATH, archivePath)
                            putExtra(ServiceConstants.EXTRA_ITEMS_TO_REMOVE_JOB_ID, jobId)
                        }
                        requireContext().startService(intent)
                        mode?.finish()
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            adapter.clearSelection()
            updateBottomBar(false)
        }
    }

    override fun onFilesSelected(files: List<File>) {
        lifecycleScope.launch {
            val loadingDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
                .setMessage(getString(R.string.please_wait))
                .setCancelable(false)
                .create()
            loadingDialog.show()

            try {
                binding.progressBar.visibility = View.VISIBLE
                val filePairs = withContext(Dispatchers.IO) {
                    files.map { it.absolutePath to (if (currentPath.isEmpty()) it.name else "$currentPath/${it.name}") }
                }
                val jobId = withContext(Dispatchers.IO) {
                    fileOperationsDao.addFilePairsForJob(filePairs)
                }

                if (!isAdded) return@launch

                val intent = Intent(requireContext(), Update7zService::class.java).apply {
                    putExtra(ServiceConstants.EXTRA_ARCHIVE_PATH, archivePath)
                    putExtra(ServiceConstants.EXTRA_ITEMS_TO_ADD_JOB_ID, jobId)
                }
                requireContext().startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.general_error_msg, Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
            } finally {
                loadingDialog.dismiss()
            }
        }
    }

    private fun showBottomSheetOptions(items: List<ArchiveItem>) {
        if (items.isEmpty()) return
        val binding = BottomSheetOptionBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        bottomSheetDialog.setContentView(binding.root)

        val totalSize = items.sumOf { it.size }
        val fileSizeText = bytesToString(totalSize)
        binding.fileSize.text = fileSizeText

        val dateFormat = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.DEFAULT,
            java.text.DateFormat.SHORT,
            java.util.Locale.getDefault()
        )

        if (items.size == 1) {
            val item = items.first()
            binding.fileName.text = item.path.removeSuffix("/").substringAfterLast('/')
            binding.fileDate.text = item.lastModified?.let { dateFormat.format(it) } ?: ""
            val itemName = item.path.removeSuffix("/").substringAfterLast('/')
            FileIconHelper.bindFileIcon(
                cardView = binding.cardView,
                fileIcon = binding.fileIcon,
                fileExtension = binding.fileExtension,
                name = itemName,
                isDirectory = item.isDirectory,
                file = null
            )
            binding.btnExtract.text = getString(R.string.extract)
        } else {
            val countLabel = if (items.size == 1) getString(R.string.item) else getString(R.string.items)
            binding.fileName.text = getString(R.string.selection_status_format, items.size, countLabel, fileSizeText)
            binding.fileDate.text = ""
            val containerColor = resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
            val onContainerColor = resolveThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
            binding.cardView.setCardBackgroundColor(containerColor)
            binding.fileIcon.setImageResource(R.drawable.ic_unarchive)
            binding.fileIcon.imageTintList = ColorStateList.valueOf(onContainerColor)
            binding.fileIcon.visibility = View.VISIBLE
            binding.fileExtension.visibility = View.GONE
            binding.btnExtract.text = getString(R.string.extract_selected)
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
            archivePath?.let { File(it).parent } ?: android.os.Environment.getExternalStorageDirectory().absolutePath
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
            val hasEncrypted = items.any { item ->
                item.isEncrypted || (item.isDirectory && cachedEntries.any {
                    it.isEncrypted && it.path.startsWith("${item.path.removeSuffix("/")}/")
                })
            }
            if (hasEncrypted) {
                showPasswordInputDialog(items, destinationPath)
            } else {
                startExtractionService(items, null, destinationPath)
            }
            bottomSheetDialog.dismiss()
            actionMode?.finish()
        }

        bottomSheetDialog.show()
    }

    private fun showPasswordInputDialog(items: List<ArchiveItem>, destinationPath: String) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startExtractionService(items, password.ifBlank { null }, destinationPath)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startExtractionService(items, null, destinationPath)
            }
            .show()
    }

    private fun startExtractionService(items: List<ArchiveItem>, password: String?, destinationPath: String) {
        val path = archivePath ?: return
        lifecycleScope.launch {
            val jobId = withContext(Dispatchers.IO) {
                fileOperationsDao.addFilesForJob(listOf(path))
            }

            if (!isAdded) return@launch

            val itemsToExtract = ArrayList<String>()
            items.forEach { itemsToExtract.add(it.path) }
            
            val intent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
                putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
                putExtra(ServiceConstants.EXTRA_PASSWORD, password)
                putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
                putStringArrayListExtra(ServiceConstants.EXTRA_ITEMS_TO_EXTRACT, itemsToExtract)
            }
            ContextCompat.startForegroundService(requireContext(), intent)
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        }
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
        if (binding.searchArchiveInput.text?.isNotEmpty() == true) {
            binding.searchArchiveInput.text?.clear()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.searchArchiveInput.windowToken, 0)
            return
        }
        if (currentPath.isNotEmpty()) {
            currentPath = currentPath.substringBeforeLast('/', "")
            loadArchiveItems(currentPath)
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actionMode?.finish()
        val activity = activity as? AppCompatActivity
        activity?.findViewById<View>(R.id.tabLayout)?.visibility = View.VISIBLE
        activity?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        activity?.supportActionBar?.title = getString(R.string.app_name)
    }

    override fun onDestroy() {
        super.onDestroy()
        closeArchive()
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