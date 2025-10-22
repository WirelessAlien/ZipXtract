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

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.icu.text.DateFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.FileObserver.CREATE
import android.os.FileObserver.DELETE
import android.os.FileObserver.DELETE_SELF
import android.os.FileObserver.MODIFY
import android.os.FileObserver.MOVED_FROM
import android.os.FileObserver.MOVED_TO
import android.os.FileObserver.MOVE_SELF
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import com.wirelessalien.zipxtract.BuildConfig
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.SettingsActivity
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.BottomSheetCompressorArchiveBinding
import com.wirelessalien.zipxtract.databinding.BottomSheetOptionBinding
import com.wirelessalien.zipxtract.databinding.DialogFileInfoBinding
import com.wirelessalien.zipxtract.databinding.FragmentMainBinding
import com.wirelessalien.zipxtract.databinding.LayoutPermissionRequestBinding
import com.wirelessalien.zipxtract.databinding.PasswordInputDialogBinding
import com.wirelessalien.zipxtract.databinding.ProgressDialogArchiveBinding
import com.wirelessalien.zipxtract.databinding.ProgressDialogExtractBinding
import com.wirelessalien.zipxtract.helper.ChecksumUtils
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.service.Archive7zService
import com.wirelessalien.zipxtract.service.ArchiveSplitZipService
import com.wirelessalien.zipxtract.service.ArchiveTarService
import com.wirelessalien.zipxtract.service.ArchiveZipService
import com.wirelessalien.zipxtract.service.CompressCsArchiveService
import com.wirelessalien.zipxtract.service.CopyMoveService
import com.wirelessalien.zipxtract.service.DeleteFilesService
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService
import com.wirelessalien.zipxtract.viewmodel.FileOperationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class MainFragment : Fragment(), FileAdapter.OnItemClickListener, FileAdapter.OnFileLongClickListener {

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var adapter: FileAdapter
    private var isSearchActive: Boolean = false
    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private var currentPath: String? = null
    var actionMode: ActionMode? = null
    private val selectedFiles = mutableListOf<File>()
    private var fileObserver: FileObserver? = null
    private var searchView: SearchView? = null
    private var isObserving: Boolean = false
    private var searchHandler: Handler? = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private lateinit var eProgressDialog: AlertDialog
    private lateinit var aProgressDialog: AlertDialog
    private lateinit var aProgressText: TextView
    private lateinit var eProgressText: TextView
    private var areFabsVisible: Boolean = false
    private val fileOperationViewModel: FileOperationViewModel by activityViewModels()
    private lateinit var eProgressBar: LinearProgressIndicator
    private lateinit var aProgressBar: LinearProgressIndicator
    private lateinit var binding: FragmentMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var fileLoadingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null
    private lateinit var fileOperationsDao: FileOperationsDao


    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (aProgressBar.progress >= 100) {
                aProgressBar.progress = 70
            } else {
                aProgressBar.progress += 1
            }
            aProgressText.text = getString(R.string.compressing_progress, aProgressBar.progress)
            handler.postDelayed(this, 1000)

            if (eProgressBar.progress >= 100) {
                eProgressBar.progress = 70
            } else {
                eProgressBar.progress += 1
            }
            eProgressText.text = getString(R.string.extracting_progress, eProgressBar.progress)
            handler.postDelayed(this, 1000)
        }
    }

    private val extractionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded) return

            when (intent?.action) {
                ACTION_EXTRACTION_COMPLETE -> {
                    handler.removeCallbacks(updateProgressRunnable)
                    val dirPath = intent.getStringExtra(EXTRA_DIR_PATH)
                    Log.d("MainFragment", "onReceive: $dirPath")
                    if (dirPath != null) {
                        Snackbar.make(binding.root, getString(R.string.open_folder), Snackbar.LENGTH_LONG)
                            .setAction(getString(R.string.ok)) {
                                navigateToPath(dirPath)
                            }
                            .show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.extraction_success), Toast.LENGTH_SHORT).show()
                    }
                    unselectAllFiles()
                    eProgressDialog.dismiss()
                }
                ACTION_EXTRACTION_ERROR -> {
                    handler.removeCallbacks(updateProgressRunnable)
                    unselectAllFiles()
                    eProgressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                }
                ACTION_EXTRACTION_PROGRESS -> {
                    val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    if (progress == 100) {
                        eProgressBar.progress = 80
                        eProgressText.text = getString(R.string.extracting_progress, 80)
                        handler.post(updateProgressRunnable)
                    } else {
                        eProgressBar.progress = progress
                        eProgressText.text = getString(R.string.extracting_progress, progress)
                    }
                }
                ACTION_ARCHIVE_COMPLETE -> {
                    handler.removeCallbacks(updateProgressRunnable)
                    val dirPath = intent.getStringExtra(EXTRA_DIR_PATH)
                    if (dirPath != null) {
                        Snackbar.make(binding.root, getString(R.string.open_folder), Snackbar.LENGTH_LONG)
                            .setAction(getString(R.string.ok)) {
                                navigateToPath(dirPath)
                            }
                            .show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.archive_success), Toast.LENGTH_SHORT).show()
                    }
                    unselectAllFiles()
                    aProgressDialog.dismiss()
                }
                ACTION_ARCHIVE_ERROR -> {
                    handler.removeCallbacks(updateProgressRunnable)
                    unselectAllFiles()
                    aProgressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                }
                ACTION_ARCHIVE_PROGRESS -> {
                    val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    if (progress == 100) {
                        aProgressBar.progress = 80
                        aProgressText.text = getString(R.string.compressing_progress, 80)
                        handler.post(updateProgressRunnable)
                    } else {
                        aProgressBar.progress = progress
                        aProgressText.text = getString(R.string.compressing_progress, progress)
                    }
                }
            }
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted
        } else {
            // Permission denied
            Toast.makeText(requireContext(), getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun unselectAllFiles() {
        selectedFiles.clear()
        adapter.clearSelection()
        updateActionModeTitle()
        actionMode?.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isDialogShown = sharedPreferences.getBoolean("isInfoDialogShown", false)

        if (!isDialogShown) {
            showInfoDialog()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        sortBy = SortBy.valueOf(sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name) ?: SortBy.SORT_BY_NAME.name)
        sortAscending = sharedPreferences.getBoolean("sortAscending", true)

        (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)

        fileOperationsDao = FileOperationsDao(requireContext())
        adapter = FileAdapter(requireContext(), this, ArrayList())
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        binding.recyclerView.adapter = adapter

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)

                val searchItem = menu.findItem(R.id.menu_search)
                searchView = searchItem?.actionView as SearchView?

                // Configure the search view
                searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean {
                        // Perform search when the user submits the query
                        searchFiles(query)
                        return true
                    }

                    override fun onQueryTextChange(newText: String?): Boolean {
                        // Perform search as the user types with a delay
                        searchRunnable?.let { searchHandler?.removeCallbacks(it) }
                        searchRunnable = Runnable {
                            searchFiles(newText)
                        }
                        searchHandler?.postDelayed(searchRunnable!!, 200)
                        return true
                    }
                })
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                sharedPreferences.edit {
                    when (menuItem.itemId) {
                        R.id.menu_sort_by_name -> {
                            sortBy = SortBy.SORT_BY_NAME
                            putString("sortBy", sortBy.name)
                            updateAdapterWithFullList()
                        }

                        R.id.menu_sort_by_size -> {
                            sortBy = SortBy.SORT_BY_SIZE
                            putString("sortBy", sortBy.name)
                            updateAdapterWithFullList()
                        }

                        R.id.menu_sort_by_time_of_creation -> {
                            sortBy = SortBy.SORT_BY_MODIFIED
                            putString("sortBy", sortBy.name)
                            updateAdapterWithFullList()
                        }

                        R.id.menu_sort_by_extension -> {
                            sortBy = SortBy.SORT_BY_EXTENSION
                            putString("sortBy", sortBy.name)
                            updateAdapterWithFullList()
                        }

                        R.id.menu_sort_ascending -> {
                            sortAscending = true
                            putBoolean("sortAscending", sortAscending)
                            updateAdapterWithFullList()
                        }

                        R.id.menu_sort_descending -> {
                            sortAscending = false
                            putBoolean("sortAscending", sortAscending)
                            updateAdapterWithFullList()
                        }

                        R.id.menu_settings -> {
                            val intent = Intent(requireContext(), SettingsActivity::class.java)
                            startActivity(intent)
                        }
                    }
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        currentPath = arguments?.getString("path")
        searchHandler = Handler(Looper.getMainLooper())

        lifecycleScope.launch {
            fileOperationViewModel.filesToCopyMove.collect { files ->
                if (files.isNotEmpty()) {
                    showPasteFab()
                } else {
                    binding.pasteFab.visibility = View.GONE
                }
            }
        }

        if (!checkStoragePermissions()) {
            showPermissionRequestLayout()
        } else {
            initRecyclerView()
        }

        val sdCardPath = getSdCardPath()
        if (sdCardPath != null) {
            binding.externalStorageChip.visibility = View.VISIBLE
            binding.externalStorageChip.setOnClickListener {
                val currentPath = currentPath ?: Environment.getExternalStorageDirectory().absolutePath
                val basePath = Environment.getExternalStorageDirectory().absolutePath
                if (currentPath.startsWith(basePath) && !currentPath.startsWith(sdCardPath)) {
                    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    navigateToPath(sdCardPath)
                } else if (currentPath.startsWith(sdCardPath) && !currentPath.startsWith(basePath)) {
                    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    navigateToPath(basePath)
                }
            }
        }
        startFileObserver()

        val filter = IntentFilter().apply {
            addAction(ACTION_EXTRACTION_COMPLETE)
            addAction(ACTION_EXTRACTION_ERROR)
            addAction(ACTION_EXTRACTION_PROGRESS)
            addAction(ACTION_ARCHIVE_COMPLETE)
            addAction(ACTION_ARCHIVE_ERROR)
            addAction(ACTION_ARCHIVE_PROGRESS)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(extractionReceiver, filter)

        extractProgressDialog()
        archiveProgressDialog()

        binding.mainFab.setOnClickListener {
            if (areFabsVisible) {
                hideExtendedFabs()
            } else {
                showExtendedFabs()
            }
        }

        binding.createZipFab.setOnClickListener {
            showZipOptionsDialog()
            hideExtendedFabs()
        }

        binding.create7zFab.setOnClickListener {
            show7zOptionsDialog()
            hideExtendedFabs()
        }

        binding.createTarFab.setOnClickListener {
            showTarOptionsDialog()
            hideExtendedFabs()
        }

        checkAndShowDonationFragment()

        updateCurrentPathChip()

        handleOpenWithIntent()
    }

    private fun handleOpenWithIntent() {
        val jobId = arguments?.getString(ARG_JOB_ID)
        val archiveType = arguments?.getString(ARG_ARCHIVE_TYPE)

        if (jobId != null && archiveType != null) {
            val filePathsStringList = fileOperationsDao.getFilesForJob(jobId)

            val validFilePaths = filePathsStringList.filter { File(it).exists() }

            if (validFilePaths.isEmpty()) {
                Toast.makeText(requireContext(),
                    getString(R.string.no_valid_files_found_to_archive), Toast.LENGTH_LONG).show()
                // Clear arguments even if no valid files, to prevent re-processing
                arguments?.remove(ARG_JOB_ID)
                arguments?.remove(ARG_ARCHIVE_TYPE)
                updateAdapterWithFullList()
                return
            }

            val fragmentManager = parentFragmentManager
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)

            val newFragment = when (archiveType) {
                "ZIP" -> ZipOptionDialogFragment.newInstance(jobId)
                "7Z" -> SevenZOptionDialogFragment.newInstance(jobId)
                "TAR" -> TarOptionsDialogFragment.newInstance(jobId)
                else -> null
            }

            newFragment?.let {
                transaction.add(android.R.id.content, it).addToBackStack(null)
                transaction.commit()
            } ?: Toast.makeText(requireContext(),
                getString(R.string.invalid_archive_type), Toast.LENGTH_SHORT).show()

            arguments?.remove(ARG_JOB_ID)
            arguments?.remove(ARG_ARCHIVE_TYPE)
            fileOperationsDao.deleteFilesForJob(jobId)

            Handler(Looper.getMainLooper()).postDelayed({
                if (isAdded) {
                    if (filePathsStringList.size > 1) {
                        unselectAllFiles()
                    }
                    updateAdapterWithFullList()
                }
            }, 1000) // Delay to allow dialog to process & avoid immediate UI flicker

        }
    }

    private fun getSdCardPath(): String? {
        val storageManager = requireContext().getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val storageVolumes = storageManager.storageVolumes
        for (storageVolume in storageVolumes) {
            if (storageVolume.isRemovable) {
                val storageVolumePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    storageVolume.directory?.absolutePath
                } else {
                    Environment.getExternalStorageDirectory().absolutePath
                }
                if (storageVolumePath != null && storageVolumePath != Environment.getExternalStorageDirectory().absolutePath) {
                    return storageVolumePath
                }
            }
        }
        return null
    }

    private fun showPermissionRequestLayout() {
        val permissionBinding = LayoutPermissionRequestBinding.inflate(layoutInflater)
        binding.root.removeAllViews()
        binding.root.addView(permissionBinding.root)

        permissionBinding.btnGrantAccess.setOnClickListener {
            requestForStoragePermissions()
        }

        permissionBinding.tvPrivacyPolicy.setOnClickListener {
        val intent = Intent(Intent.ACTION_VIEW, "https://sites.google.com/view/privacy-policy-zipxtract/home".toUri())
            startActivity(intent)
        }
    }

    private fun updateCurrentPathChip() {
        val internalStorage = getString(R.string.internal_storage)
        val sdCardPath = getSdCardPath()
        val basePath = Environment.getExternalStorageDirectory().absolutePath
        val currentPath = currentPath ?: basePath
        val displayPath = when {
            currentPath.startsWith(basePath) -> currentPath.replace(basePath, internalStorage)
            sdCardPath != null && currentPath.startsWith(sdCardPath) -> currentPath.replace(sdCardPath, getString(R.string.sd_card))
            else -> currentPath
        }.split("/").filter { it.isNotEmpty() }

        binding.chipGroupPath.removeAllViews()

        var pathAccumulator = ""

        for (part in displayPath) {
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
            chip.text = part

            pathAccumulator = when (part) {
                internalStorage -> basePath
                getString(R.string.sd_card) -> sdCardPath!!
                else -> if (pathAccumulator.endsWith("/")) "$pathAccumulator$part" else "$pathAccumulator/$part"
            }

            val finalPathForChip = pathAccumulator

            chip.setOnClickListener {
                navigateToPath(finalPathForChip)
            }
            binding.chipGroupPath.addView(chip)
        }
    }

    private fun navigateToPath(path: String) {
        if (isAdded) {
            unselectAllFiles()
        }
        fileLoadingJob?.cancel()
        currentPath = path
        updateCurrentPathChip()
        updateAdapterWithFullList()
    }

    private fun showExtendedFabs() {
        binding.createZipFab.show()
        binding.create7zFab.show()
        binding.createTarFab.show()
        areFabsVisible = true
    }

    private fun hideExtendedFabs() {
        binding.createZipFab.hide()
        binding.create7zFab.hide()
        binding.createTarFab.hide()
        areFabsVisible = false
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.info_tip))
            .setMessage(getString(R.string.info_tip_description))
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                sharedPreferences.edit {
                    putBoolean("isInfoDialogShown", true)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun show7zOptionsDialog() {
        lifecycleScope.launch {
            val selectedFiles = adapter.getSelectedFilesPaths()
            if (selectedFiles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val jobId = fileOperationsDao.addFilesForJob(selectedFiles)
            val fragmentManager = parentFragmentManager
            val newFragment = SevenZOptionDialogFragment.newInstance(jobId)

            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()

            actionMode?.finish() // Destroy the action mode
        }
    }

    private fun showTarOptionsDialog() {
        lifecycleScope.launch {
            val selectedFiles = adapter.getSelectedFilesPaths()
            if (selectedFiles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val jobId = fileOperationsDao.addFilesForJob(selectedFiles)
            val fragmentManager = parentFragmentManager
            val newFragment = TarOptionsDialogFragment.newInstance(jobId)

            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()

            actionMode?.finish() // Destroy the action mode
        }
    }

    private fun showZipOptionsDialog() {
        lifecycleScope.launch {
            val selectedFiles = adapter.getSelectedFilesPaths()
            if (selectedFiles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_files_to_archive, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val jobId = fileOperationsDao.addFilesForJob(selectedFiles)
            val fragmentManager = parentFragmentManager
            val newFragment = ZipOptionDialogFragment.newInstance(jobId)

            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()

            actionMode?.finish()
        }
    }

    private fun archiveProgressDialog() {
        val binding = ProgressDialogArchiveBinding.inflate(layoutInflater)
        aProgressBar = binding.progressBar
        aProgressText = binding.progressText

        binding.backgroundButton.setOnClickListener {
            aProgressDialog.dismiss()
        }

        aProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    private fun extractProgressDialog() {
        val binding = ProgressDialogExtractBinding.inflate(layoutInflater)
        eProgressBar = binding.progressBar
        eProgressText = binding.progressText

        binding.backgroundButton.setOnClickListener {
            eProgressDialog.dismiss()
        }

        eProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    override fun onFileLongClick(file: File, view: View) {
        val position = adapter.files.indexOf(file)
        if (position != -1) {
            startActionMode(position)
        }
    }

    override fun onItemClick(file: File, filePath: String) {
        if (actionMode != null) {
            val position = adapter.files.indexOf(file)
            if (position != -1) {
                toggleSelection(position)
                if (getSelectedItemCount() == 0) {
                    actionMode?.finish()
                }
            }
        } else {
            if (file.isDirectory) {
                val fragment = MainFragment().apply {
                    arguments = Bundle().apply {
                        putString("path", file.absolutePath)
                    }
                }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commit()

            } else {
                if (file.extension.equals("tar", ignoreCase = true)) {
                    showCompressorArchiveDialog(filePath, file)
                } else {
                    showBottomSheetOptions(filePath, file)
                }
            }
        }
    }

    fun startActionMode(position: Int) {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    requireActivity().menuInflater.inflate(R.menu.menu_action, menu)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                    return when (item?.itemId) {
                        R.id.menu_action_select_all -> {
                            selectAllFiles()
                            true
                        }
                        R.id.m_archive_7z -> {
                            val fragmentManager = parentFragmentManager
                            val newFragment = SevenZOptionDialogFragment.newInstance(adapter)

                            // Show the fragment fullscreen.
                            val transaction = fragmentManager.beginTransaction()
                            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                            transaction.add(android.R.id.content, newFragment)
                                .addToBackStack(null)
                                .commit()

                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.m_archive_zip -> {
                            val fragmentManager = parentFragmentManager
                            val newFragment = ZipOptionDialogFragment.newInstance(adapter)

                            // Show the fragment fullscreen.
                            val transaction = fragmentManager.beginTransaction()
                            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                            transaction.add(android.R.id.content, newFragment)
                                .addToBackStack(null)
                                .commit()

                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.m_archive_tar -> {
                            val fragmentManager = parentFragmentManager
                            val newFragment = TarOptionsDialogFragment.newInstance(adapter)

                            // Show the fragment fullscreen.
                            val transaction = fragmentManager.beginTransaction()
                            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                            transaction.add(android.R.id.content, newFragment)
                                .addToBackStack(null)
                                .commit()

                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.menu_action_unselect_all -> {
                            unselectAllFiles()
                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.menu_action_copy_to -> {
                            startCopyMoveAction(isCopy = true)
                            true
                        }

                        R.id.menu_action_move_to -> {
                            startCopyMoveAction(isCopy = false)
                            true
                        }

                        R.id.menu_action_delete -> {
                            deleteSelectedFiles()
                            actionMode?.finish()
                            true
                        }
                        else -> false
                    }
                }

                override fun onDestroyActionMode(mode: ActionMode?) {
                    actionMode = null
//                    clearSelection()
                }
            })
        }

        toggleSelection(position)
        updateActionModeTitle()
    }


    private fun startCopyMoveAction(isCopy: Boolean) {
        fileOperationViewModel.isCopyAction = isCopy
        fileOperationViewModel.setFilesToCopyMove(selectedFiles.toList())
        actionMode?.finish()
    }

    private fun showPasteFab() {
        binding.pasteFab.visibility = View.VISIBLE
        binding.pasteFab.setOnClickListener {
            pasteFiles()
        }
    }

    private fun pasteFiles() {
        val destinationPath = currentPath ?: Environment.getExternalStorageDirectory().absolutePath
        val filesToCopyMove = fileOperationViewModel.filesToCopyMove.value.map { it.absolutePath }
        val isCopyAction = fileOperationViewModel.isCopyAction
        val jobId = fileOperationsDao.addFilesForJob(filesToCopyMove)

        val intent = Intent(requireContext(), CopyMoveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_DESTINATION_PATH, destinationPath)
            putExtra(ServiceConstants.EXTRA_IS_COPY_ACTION, isCopyAction)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        fileOperationViewModel.setFilesToCopyMove(emptyList())
    }

    private fun deleteSelectedFiles() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.confirm_delete))
            .setMessage(getString(R.string.confirm_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val filesToDelete = selectedFiles.map { it.absolutePath }
                val jobId = fileOperationsDao.addFilesForJob(filesToDelete)
                val intent = Intent(requireContext(), DeleteFilesService::class.java).apply {
                    putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                unselectAllFiles()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun toggleSelection(position: Int) {
        val file = adapter.files[position]
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
        } else {
            selectedFiles.add(file)
        }
        adapter.toggleSelection(position)
        updateActionModeTitle()
    }

    fun getSelectedItemCount(): Int {
        return selectedFiles.size
    }

    private fun updateActionModeTitle() {
        val selectedItemCount = getSelectedItemCount()
        actionMode?.title = "$selectedItemCount selected"
    }

    private fun updateProgressBar(progress: Int) {
        binding.progressBar.progress = progress
        if (progress == 100) {
            binding.progressBar.visibility = View.GONE
        } else {
            binding.progressBar.visibility = View.VISIBLE
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true &&
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true) {
            initRecyclerView()
            updateAdapterWithFullList()
        } else {
            Toast.makeText(requireContext(),
                getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            requireActivity().finish()
        }
    }

    private fun requestForStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                storageActivityResultLauncher.launch(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storageActivityResultLauncher.launch(intent)
            }
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    private val storageActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission granted
                initRecyclerView()
                updateAdapterWithFullList()
            } else {
                // Permission denied
                Toast.makeText(requireContext(), getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        } else {
            if (result.resultCode == Activity.RESULT_OK) {
                // Permission granted
                initRecyclerView()
                updateAdapterWithFullList()
            } else {
                // Permission denied
                Toast.makeText(requireContext(), getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        }
    }

    private fun checkAndShowDonationFragment() {
        val savedVersionPreference = sharedPreferences.getInt("donation_dialog_version", 1)

        if (checkStoragePermissions() && savedVersionPreference == 1) {
            val donationFragment = DonationFragment()
            donationFragment.show(requireActivity().supportFragmentManager, "donationFragment")
        }
    }

    private fun initRecyclerView() {
        // Initialize RecyclerView and adapter as before
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.isNestedScrollingEnabled = false
        adapter = FileAdapter(requireContext(), this, ArrayList())
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        binding.recyclerView.adapter = adapter
        // Update the adapter with the initial file list
        updateAdapterWithFullList()
    }

    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //Android is 11 (R) or above
            Environment.isExternalStorageManager()
        } else {
            //Below android 11
            val write =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun startFileObserver() {
        if (!isObserving) {
            val directoryToObserve = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fileObserver = object : FileObserver(directoryToObserve, CREATE or DELETE or MOVE_SELF or MOVED_TO or MOVED_FROM or MODIFY or CLOSE_WRITE or ATTRIB or DELETE_SELF) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null) return

                        val file = File(directoryToObserve, path)
                        handleFileEvent(event, file)
                    }
                }
            } else {
                fileObserver = object : FileObserver(directoryToObserve.absolutePath, CREATE or DELETE or MOVE_SELF or MOVED_TO or MOVED_FROM or MODIFY or CLOSE_WRITE or ATTRIB or DELETE_SELF) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null) return

                        val file = File(directoryToObserve, path)
                        handleFileEvent(event, file)
                    }
                }
            }

            fileObserver?.startWatching()
            isObserving = true
        }
    }

    private fun handleFileEvent(event: Int, file: File) {
        CoroutineScope(Dispatchers.Main).launch {
            when {
                (event and CREATE) != 0 || (event and MOVED_TO) != 0 || (event and MOVED_FROM) != 0 || (event and MOVE_SELF) != 0 -> {
                    // Check if the file already exists in the list
                    val existingPosition = adapter.files.indexOfFirst { it.absolutePath == file.absolutePath }
                    if (existingPosition != -1) {
                        // Update the existing file entry
                        adapter.files[existingPosition] = file
                        adapter.notifyItemChanged(existingPosition)
                    } else {
                        // Add new file
                        val position = findInsertPosition(file)
                        adapter.files.add(position, file)
                        adapter.notifyItemInserted(position)
                    }
                }
                (event and DELETE) != 0 || (event and DELETE_SELF) != 0 -> {
                    // Remove deleted file
                    val position = adapter.files.indexOfFirst { it.absolutePath == file.absolutePath }
                    if (position != -1) {
                        adapter.files.removeAt(position)
                        adapter.notifyItemRemoved(position)
                    }
                }
                (event and MODIFY) != 0 -> {
                    // Update modified file
                    val position = adapter.files.indexOfFirst { it.absolutePath == file.absolutePath }
                    if (position != -1) {
                        adapter.files[position] = file
                        adapter.notifyItemChanged(position)
                    }
                }
            }
        }
    }

    private fun findInsertPosition(newFile: File): Int {
        val comparator = when (sortBy) {
            SortBy.SORT_BY_NAME -> compareBy { it.name }
            SortBy.SORT_BY_SIZE -> compareBy { it.length() }
            SortBy.SORT_BY_MODIFIED -> compareBy { getFileTimeOfCreation(it) }
            SortBy.SORT_BY_EXTENSION -> compareBy<File> { it.extension }
        }

        val finalComparator = if (sortAscending) {
            compareBy<File> { !it.isDirectory }.then(comparator)
        } else {
            compareBy<File> { !it.isDirectory }.then(comparator.reversed())
        }

        return adapter.files.binarySearch(newFile, finalComparator).let {
            if (it < 0) -(it + 1) else it
        }
    }

    private fun stopFileObserver() {
        // Stop the file observer when the activity is destroyed
        fileObserver?.stopWatching()
        isObserving = false
    }

    override fun onDestroy() {
        super.onDestroy()
        fileLoadingJob?.cancel()
        coroutineScope.cancel()
        stopFileObserver()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(extractionReceiver)
    }

    override fun onResume() {
        super.onResume()
        if (checkStoragePermissions()) {
//            updateAdapterWithFullList()
            startFileObserver()
        }
    }

    private fun showCompressorArchiveDialog(filePath: String, file: File) {
        val binding = BottomSheetCompressorArchiveBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(binding.root)

        binding.fileName.text = file.name

        binding.fileExtension.text = if (file.extension.isNotEmpty()) {
            if (file.extension.length > 4) {
                "FILE"
            } else {
                if (file.extension.length == 4) {
                    binding.fileExtension.textSize = 16f
                } else {
                    binding.fileExtension.textSize = 18f
                }
                file.extension.uppercase(Locale.getDefault())
            }
        } else {
            "..."
        }

        binding.fileSize.text = bytesToString(file.length())

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        binding.fileDate.text = dateFormat.format(Date(file.lastModified()))

        binding.btnLzma.setOnClickListener {
            startCompressService(filePath, CompressorStreamFactory.LZMA)
            bottomSheetDialog.dismiss()
        }

        binding.btnBzip2.setOnClickListener {
            startCompressService(filePath, CompressorStreamFactory.BZIP2)
            bottomSheetDialog.dismiss()
        }

        binding.btnXz.setOnClickListener {
            startCompressService(filePath, CompressorStreamFactory.XZ)
            bottomSheetDialog.dismiss()
        }

        binding.btnGzip.setOnClickListener {
            startCompressService(filePath, CompressorStreamFactory.GZIP)
            bottomSheetDialog.dismiss()
        }

        binding.btnZstd.setOnClickListener {
            startCompressService(filePath, CompressorStreamFactory.ZSTANDARD)
            bottomSheetDialog.dismiss()
        }

        binding.btnExtract.setOnClickListener {
            startExtractionService(filePath, null)
            bottomSheetDialog.dismiss()
        }

        binding.btnOpenWith.setOnClickListener {
            val uri = FileProvider.getUriForFile(requireContext().applicationContext, "${BuildConfig.APPLICATION_ID}.provider", file)
            val mime: String = getMimeType(uri.toString())

            // Open file with user selected app
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setDataAndType(uri, mime)

            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
            bottomSheetDialog.dismiss()
        }

        binding.btnFileInfo.setOnClickListener {
            showFileInfo(file)
            bottomSheetDialog.dismiss()
        }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
                .setTitle(getString(R.string.confirm_delete))
                .setMessage(getString(R.string.confirm_delete_message))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    val filesToDelete = listOf(file.absolutePath)
                    val jobId = fileOperationsDao.addFilesForJob(filesToDelete)
                    val intent = Intent(requireContext(), DeleteFilesService::class.java).apply {
                        putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                    bottomSheetDialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        bottomSheetDialog.show()
    }

    private fun startCompressService(file: String, compressionFormat: String) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), CompressCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT, compressionFormat)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun startArchiveTarService(file: List<String>, archiveName: String, compressionFormat: String) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(file)
        val intent = Intent(requireContext(), ArchiveTarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT, compressionFormat)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showBottomSheetOptions(filePaths: String, file: File) {
        val binding = BottomSheetOptionBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(binding.root)

        binding.fileName.text = file.name

        binding.fileExtension.text = if (file.extension.isNotEmpty()) {
            if (file.extension.length > 4) {
                "FILE"
            } else {
                if (file.extension.length == 4) {
                    binding.fileExtension.textSize = 16f
                } else {
                    binding.fileExtension.textSize = 18f
                }
                file.extension.uppercase(Locale.getDefault())
            }
        } else {
            "..."
        }

        binding.fileSize.text = bytesToString(file.length())

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        binding.fileDate.text = dateFormat.format(Date(file.lastModified()))

        binding.btnExtract.setOnClickListener {
            val fileExtension = file.name.split('.').takeLast(2).joinToString(".").lowercase()
            val supportedExtensions = listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz", "tar.zstd", "tar.zst")

            if (supportedExtensions.any { fileExtension.endsWith(it) }) {
                startExtractionCsService(filePaths)
            } else {
                if (file.extension.equals("rar", ignoreCase = true)) {
                    showPasswordInputMultiRarDialog(filePaths)
                } else {
                    showPasswordInputDialog(filePaths)
                }
            }
            bottomSheetDialog.dismiss()
        }

        val previewExtensions = listOf("7z", "zip")

        if (file.extension.lowercase() in previewExtensions) {
            binding.btnPreviewArchive.visibility = View.VISIBLE
            binding.btnPreviewArchive.setOnClickListener {
                val fragment = SevenZipFragment.newInstance(file.absolutePath)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .addToBackStack(null)
                    .commit()
                bottomSheetDialog.dismiss()
            }
        }

        binding.btnMultiExtract.setOnClickListener {
            showPasswordInputMultiRarDialog(file.absolutePath)
            bottomSheetDialog.dismiss()
        }

        binding.btnMulti7zExtract.setOnClickListener {
            showPasswordInputMulti7zDialog(file.absolutePath)
            bottomSheetDialog.dismiss()
        }

        binding.btnMultiZipExtract.setOnClickListener {
            showPasswordInputMultiZipDialog(file.absolutePath)
            bottomSheetDialog.dismiss()
        }

        binding.btnFileInfo.setOnClickListener {
            showFileInfo(file)
            bottomSheetDialog.dismiss()
        }

        binding.btnOpenWith.setOnClickListener {

            val uri = FileProvider.getUriForFile(requireContext().applicationContext, "${BuildConfig.APPLICATION_ID}.provider", file)
            val mime: String = getMimeType(uri.toString())

            // Open file with user selected app
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setDataAndType(uri, mime)

            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
            bottomSheetDialog.dismiss()
        }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
                .setTitle(getString(R.string.confirm_delete))
                .setMessage(getString(R.string.confirm_delete_message))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    val filesToDelete = listOf(file.absolutePath)
                    val jobId = fileOperationsDao.addFilesForJob(filesToDelete)
                    val intent = Intent(requireContext(), DeleteFilesService::class.java).apply {
                        putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                    bottomSheetDialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        bottomSheetDialog.show()
    }

    private fun getMimeType(url: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
    }

    private fun showPasswordInputDialog(file: String) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMultiRarDialog(file: String) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startRarExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startRarExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMulti7zDialog(file: String) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startMulti7zExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMulti7zExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMultiZipDialog(file: String) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startMultiZipExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMultiZipExtractionService(file, null)
            }
            .show()
    }

    private fun startExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startExtractionCsService(file: String) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startRarExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractRarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMulti7zExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractMultipart7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMultiZipExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractMultipartZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showFileInfo(file: File) {
        val binding = DialogFileInfoBinding.inflate(LayoutInflater.from(requireContext()))

        binding.fileName.text = Editable.Factory.getInstance().newEditable(file.name)
        binding.filePath.text = Editable.Factory.getInstance().newEditable(file.absolutePath)
        val fileSizeText = bytesToString(file.length())
        binding.fileSize.text = Editable.Factory.getInstance().newEditable(fileSizeText)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        binding.lastModified.text = Editable.Factory.getInstance().newEditable(dateFormat.format(Date(file.lastModified())))

        binding.md5Checksum.keyListener = null
        binding.sha1Checksum.keyListener = null
        binding.sha256Checksum.keyListener = null

        val clipboardManager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        binding.fileName.setOnLongClickListener {
            val clip = ClipData.newPlainText("File Name", file.name)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        binding.filePath.setOnLongClickListener {
            val clip = ClipData.newPlainText("File Path", file.absolutePath)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        binding.md5Checksum.setOnLongClickListener {
            val clip = ClipData.newPlainText("MD5", binding.md5Checksum.text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        binding.sha1Checksum.setOnLongClickListener {
            val clip = ClipData.newPlainText("SHA1", binding.sha1Checksum.text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        binding.sha256Checksum.setOnLongClickListener {
            val clip = ClipData.newPlainText("SHA256", binding.sha256Checksum.text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(binding.root)
            .setTitle(getString(R.string.file_info))
            .create()

        binding.okButton.setOnClickListener {
            dialog.dismiss()
        }

        ChecksumUtils.calculateChecksums(file, binding, lifecycleScope, requireContext())

        dialog.show()
    }

    private fun bytesToString(bytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            bytes < kilobyte -> "$bytes B"
            bytes < megabyte -> String.format(Locale.US, "%.2f KB", bytes.toFloat() / kilobyte)
            bytes < gigabyte -> String.format(Locale.US, "%.2f MB", bytes.toFloat() / megabyte)
            else -> String.format(Locale.US, "%.2f GB", bytes.toFloat() / gigabyte)
        }
    }

    private fun selectAllFiles() {
        for (i in 0 until adapter.itemCount) {
            if (!selectedFiles.contains(adapter.files[i])) {
                selectedFiles.add(adapter.files[i])
                adapter.toggleSelection(i)
            }
        }
        updateActionModeTitle()
    }

    fun startZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(filesToArchive)
        val intent = Intent(requireContext(), ArchiveZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ServiceConstants.EXTRA_AES_STRENGTH, aesStrength)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun startSplitZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, splitSize: Long?) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(filesToArchive)
        val intent = Intent(requireContext(), ArchiveSplitZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ServiceConstants.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ServiceConstants.EXTRA_AES_STRENGTH, aesStrength)
            putExtra(ServiceConstants.EXTRA_SPLIT_SIZE, splitSize)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun convertToBytes(size: Long, unit: String): Long {
        return size.times(when (unit) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            "GB" -> 1024L * 1024 * 1024
            else -> 1024L
        })
    }

    /** Returns the number of parts (rounded up) */
    fun getMultiZipPartsCount(selectedFilesSize: Long, splitZipSize: Long): Long {
        if (splitZipSize <= 0) {
            return Long.MAX_VALUE
        }
        val division = selectedFilesSize / splitZipSize
        val remainder = selectedFilesSize % splitZipSize
        return if (remainder > 0) division + 1 else division
    }

    fun startSevenZService(password: String?, archiveName: String, compressionLevel: Int, solid: Boolean, threadCount: Int, filesToArchive: List<String>) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(filesToArchive)
        val intent = Intent(requireContext(), Archive7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_SOLID, solid)
            putExtra(ServiceConstants.EXTRA_THREAD_COUNT, threadCount)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private suspend fun getFiles(): ArrayList<File> = withContext(Dispatchers.IO) {
        val files = ArrayList<File>()
        val directories = ArrayList<File>()

        val directory = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

        if (!directory.canRead()) {
            withContext(Dispatchers.Main) {
                binding.statusTextView.text = getString(R.string.access_denied)
                binding.statusTextView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.emptyFolderLayout.visibility = View.GONE
            }
            return@withContext files
        }

        val fileList = directory.listFiles()

        if (fileList == null || fileList.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.emptyFolderLayout.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.statusTextView.visibility = View.GONE
            }
            return@withContext files
        }

        fileList.forEach { file ->
            if (file.isDirectory) {
                directories.add(file)
            } else {
                files.add(file)
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

        val combinedList = ArrayList<File>()
        combinedList.addAll(directories)
        combinedList.addAll(files)

        withContext(Dispatchers.Main) {
            binding.statusTextView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyFolderLayout.visibility = View.GONE
        }

        combinedList
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

    private fun updateAdapterWithFullList() {
        if (!isSearchActive) {
            adapter.updateFilesAndFilter(ArrayList())
            fileLoadingJob?.cancel()

            binding.shimmerViewContainer.startShimmer()
            binding.shimmerViewContainer.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.emptyFolderLayout.visibility = View.GONE
            binding.statusTextView.visibility = View.GONE

            fileLoadingJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fullFileList = getFiles()

                    withContext(Dispatchers.Main) {
                        binding.shimmerViewContainer.stopShimmer()
                        binding.shimmerViewContainer.visibility = View.GONE
                        if (fullFileList.isEmpty()) {
                            binding.emptyFolderLayout.visibility = View.VISIBLE
                        } else {
                            binding.recyclerView.visibility = View.VISIBLE
                        }
                        adapter.updateFilesAndFilter(fullFileList)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    withContext(Dispatchers.Main) {
                        binding.shimmerViewContainer.stopShimmer()
                        binding.shimmerViewContainer.visibility = View.GONE
                        binding.statusTextView.apply {
                            text = getString(R.string.general_error_msg)
                            visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun searchFiles(query: String?) {
        isSearchActive = !query.isNullOrEmpty()

        if (query.isNullOrEmpty()) {
            updateAdapterWithFullList()
            return
        }

        binding.shimmerViewContainer.startShimmer()
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        adapter.updateFilesAndFilter(ArrayList())

        val fastSearchEnabled = sharedPreferences.getBoolean("fast_search", false)

        // Cancel previous search if any
        searchJob?.cancel()

        searchJob = coroutineScope.launch {
            val searchFlow = if (fastSearchEnabled) {
                searchFilesWithMediaStore(query)
            } else {
                val basePath = Environment.getExternalStorageDirectory().absolutePath
                val sdCardPath = getSdCardPath()
                val searchPath = if (currentPath?.startsWith(sdCardPath ?: "") == true) {
                    sdCardPath ?: basePath
                } else {
                    basePath
                }
                searchAllFiles(File(searchPath), query)
            }

            searchFlow
                .flowOn(Dispatchers.IO)
                .catch { e ->
                    withContext(Dispatchers.Main) {
                        binding.shimmerViewContainer.stopShimmer()
                        binding.shimmerViewContainer.visibility = View.GONE
                        binding.statusTextView.apply {
                            text = e.message ?: getString(R.string.general_error_msg)
                            visibility = View.VISIBLE
                        }
                    }
                }
                .collect { files ->
                    withContext(Dispatchers.Main) {
                        adapter.updateFilesAndFilter(ArrayList(files))
                        binding.shimmerViewContainer.stopShimmer()
                        binding.shimmerViewContainer.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                    }
                }
        }
    }

    private fun searchAllFiles(directory: File, query: String): Flow<List<File>> = flow {
        val results = mutableListOf<File>()

        suspend fun searchRecursively(dir: File) {
            val files = dir.listFiles() ?: return

            for (file in files) {
                if (!currentCoroutineContext().isActive) return

                if (file.isDirectory) {
                    searchRecursively(file)
                } else if (file.name.contains(query, true)) {
                    results.add(file)
                    emit(results.toList())
                }
            }
        }

        searchRecursively(directory)
        emit(results)
    }.distinctUntilChanged { old, new -> old.size == new.size }

    private fun searchFilesWithMediaStore(query: String): Flow<List<File>> = flow {
        val results = mutableListOf<File>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            requireActivity().contentResolver.query(
                queryUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(dataColumn)
                    val file = File(filePath)
                    if (file.exists()) {
                        results.add(file)
                        emit(results.toList())
                    }
                }
            }
        } catch (_: Exception) {
            // exceptions
        }
        emit(results)
    }.distinctUntilChanged { old, new -> old.size == new.size }

    companion object {
        const val ARG_JOB_ID = "com.wirelessalien.zipxtract.ARG_JOB_ID"
        const val ARG_ARCHIVE_TYPE = "com.wirelessalien.zipxtract.ARG_ARCHIVE_TYPE"
    }
}