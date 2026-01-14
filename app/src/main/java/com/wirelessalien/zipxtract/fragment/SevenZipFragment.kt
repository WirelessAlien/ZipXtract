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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.ArchiveItemAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.FragmentSevenZipBinding
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.service.Update7zService
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
        val lastModified: Date?
    )

    private lateinit var binding: FragmentSevenZipBinding
    private lateinit var fileOperationsDao: FileOperationsDao
    private var archivePath: String? = null
    private lateinit var adapter: ArchiveItemAdapter
    private var inArchive: IInArchive? = null
    private var currentPath: String = ""
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BroadcastConstants.ACTION_ARCHIVE_PROGRESS -> {
                    val progress = intent.getIntExtra(BroadcastConstants.EXTRA_PROGRESS, 0)
                    binding.progressBar.progress = progress
                }
                BroadcastConstants.ACTION_ARCHIVE_COMPLETE -> {
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
                BroadcastConstants.ACTION_ARCHIVE_ERROR -> {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(),
                        getString(R.string.error_updating_archive), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

        try {
            val randomAccessFile = RandomAccessFile(archivePath, "r")
            inArchive = SevenZip.openInArchive(null, RandomAccessFileInStream(randomAccessFile))
            loadArchiveItems(currentPath)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(),
                getString(R.string.error_opening_archive, e.message), Toast.LENGTH_LONG).show()
        }

        val filter = IntentFilter().apply {
            addAction(BroadcastConstants.ACTION_ARCHIVE_COMPLETE)
            addAction(BroadcastConstants.ACTION_ARCHIVE_ERROR)
            addAction(BroadcastConstants.ACTION_ARCHIVE_PROGRESS)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(updateReceiver, filter)

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
                            children[dirPath] = ArchiveItem(dirPath, true, 0, null)
                        }
                    } else {
                        // It's a direct child file or an empty directory entry
                        val isDirectory = it.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                        val size = it.getProperty(i, PropID.SIZE) as? Long ?: 0L
                        val lastModified = it.getProperty(i, PropID.LAST_MODIFICATION_TIME) as? Date
                        if (!children.containsKey(itemPath)) {
                            children[itemPath] = ArchiveItem(itemPath, isDirectory, size, lastModified)
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
                // will add a confirmation dialog to remove the file
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
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(updateReceiver)
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