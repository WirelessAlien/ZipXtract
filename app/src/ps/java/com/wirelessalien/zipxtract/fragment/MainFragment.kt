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
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import com.google.android.material.color.MaterialColors
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
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_ARCHIVE_DIR_PATH
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants.EXTRA_DESTINATION_PATH
import com.wirelessalien.zipxtract.databinding.BottomSheetCompressorArchiveBinding
import com.wirelessalien.zipxtract.databinding.BottomSheetOptionBinding
import com.wirelessalien.zipxtract.databinding.DialogFileInfoBinding
import com.wirelessalien.zipxtract.databinding.FragmentMainBinding
import com.wirelessalien.zipxtract.databinding.LayoutPermissionRequestBinding
import com.wirelessalien.zipxtract.databinding.PasswordInputDialogBinding
import com.wirelessalien.zipxtract.databinding.ProgressDialogArchiveBinding
import com.wirelessalien.zipxtract.databinding.ProgressDialogExtractBinding
import com.wirelessalien.zipxtract.helper.ChecksumUtils
import com.wirelessalien.zipxtract.helper.EncryptionCheckHelper
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.helper.MultipartArchiveHelper
import com.wirelessalien.zipxtract.helper.PathUtils
import com.wirelessalien.zipxtract.helper.Searchable
import com.wirelessalien.zipxtract.helper.StorageHelper
import com.wirelessalien.zipxtract.model.FileItem
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
import kotlinx.coroutines.delay
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
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class MainFragment : Fragment(), FileAdapter.OnItemClickListener, FileAdapter.OnFileLongClickListener, Searchable {

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var adapter: FileAdapter
    private var isSearchActive: Boolean = false
    private var currentQuery: String? = null
    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private var currentPath: String? = null
    var actionMode: ActionMode? = null
    private val selectedFiles = mutableListOf<File>()
    private var fileObserver: FileObserver? = null
    private var isObserving: Boolean = false
    private var searchHandler: Handler? = Handler(Looper.getMainLooper())
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
    private var storageInfoJob: Job? = null
    private var searchJob: Job? = null
    private lateinit var fileOperationsDao: FileOperationsDao

    private var isLowStorage: Boolean = false

    override fun onSearch(query: String) {
        searchFiles(query)
    }

    override fun getCurrentSearchQuery(): String? {
        return if (isSearchActive) currentQuery else null
    }

    private val pendingFileEvents = mutableListOf<Pair<Int, File>>()
    private var processEventsJob: Job? = null


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

        fileOperationsDao = FileOperationsDao(requireContext())
        adapter = FileAdapter(requireContext(), this, ArrayList())
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        binding.recyclerView.adapter = adapter

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                var isHandled = true
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
                        else -> isHandled = false
                    }
                }
                return isHandled
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

        val sdCardPath = StorageHelper.getSdCardPath(requireContext())
        if (sdCardPath != null) {
            binding.externalStorageChip.visibility = View.VISIBLE
            binding.externalStorageChip.setOnClickListener {
                val currentPathToCheck = currentPath ?: Environment.getExternalStorageDirectory().absolutePath
                val basePath = Environment.getExternalStorageDirectory().absolutePath
                if (currentPathToCheck.startsWith(basePath) && !currentPathToCheck.startsWith(sdCardPath)) {
                    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    val fragment = MainFragment().apply {
                        arguments = Bundle().apply {
                            putString("path", sdCardPath)
                        }
                    }
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container, fragment)
                        .commit()
                } else if (currentPathToCheck.startsWith(sdCardPath) && !currentPathToCheck.startsWith(basePath)) {
                    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    val fragment = MainFragment().apply {
                        arguments = Bundle().apply {
                            putString("path", basePath)
                        }
                    }
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container, fragment)
                        .commit()
                }
            }
        }

        updateStorageInfo(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)
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

        binding.swipeRefreshLayout.setOnRefreshListener {
            updateStorageInfo(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)
            if (isSearchActive) {
                searchFiles(currentQuery)
            } else {
                updateAdapterWithFullList()
            }
        }

        updateCurrentPathChip()

        handleOpenWithIntent()

        val directoryPath = arguments?.getString(ARG_DIRECTORY_PATH)
        if (directoryPath != null) {
            navigateToPath(directoryPath)
        }

        binding.storageWarningText.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.general_error_msg), Toast.LENGTH_SHORT).show()
            }
        }
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
        val sdCardString = getString(R.string.sd_card)
        val sdCardPath = StorageHelper.getSdCardPath(requireContext())
        val basePath = Environment.getExternalStorageDirectory().absolutePath
        val currentPath = currentPath ?: basePath
        val isSdCard = sdCardPath != null && currentPath.startsWith(sdCardPath)

        val displayPath = when {
            currentPath.startsWith(basePath) -> currentPath.replace(basePath, internalStorage)
            isSdCard -> currentPath.replace(sdCardPath, sdCardString)
            else -> currentPath
        }.split("/").filter { it.isNotEmpty() }

        binding.chipGroupPath.removeAllViews()

        var pathAccumulator = ""

        for ((index, part) in displayPath.withIndex()) {
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.custom_chip, binding.chipGroupPath, false) as Chip
            chip.text = part

            if (index == 0) {
                if (part == internalStorage) pathAccumulator = basePath
                else if (part == sdCardString && isSdCard) pathAccumulator = sdCardPath
                else pathAccumulator = "/$part"
            } else {
                pathAccumulator = if (pathAccumulator.endsWith("/")) "$pathAccumulator$part" else "$pathAccumulator/$part"
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
        stopFileObserver()
        processEventsJob?.cancel()
        synchronized(pendingFileEvents) {
            pendingFileEvents.clear()
        }
        currentPath = path
        startFileObserver()
        updateCurrentPathChip()
        updateAdapterWithFullList()
        updateStorageInfo(path)
    }

    private fun updateStorageInfo(path: String) {
        storageInfoJob?.cancel()
        storageInfoJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Determine which storage volume the path belongs to
                val sdCardPath = StorageHelper.getSdCardPath(requireContext())
                val rootPath = if (sdCardPath != null && path.startsWith(sdCardPath)) {
                    sdCardPath
                } else {
                    Environment.getExternalStorageDirectory().absolutePath
                }

                val stat = StatFs(rootPath)
                val totalSize = stat.totalBytes
                val availableSize = stat.availableBytes
                val usedSize = totalSize - availableSize

                val progress = if (totalSize > 0) ((usedSize.toDouble() / totalSize) * 100).toInt() else 0
                val availablePercentage = if (totalSize > 0) ((availableSize.toDouble() / totalSize) * 100).toInt() else 0

                val totalSizeStr = android.text.format.Formatter.formatFileSize(requireContext(), totalSize)
                val availableSizeStr = android.text.format.Formatter.formatFileSize(requireContext(), availableSize)

                isLowStorage = availablePercentage < 10

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        if (isLowStorage) {
                            binding.storageWarningText.text = "\u26A0 " + getString(R.string.storage_info_format, availableSizeStr, totalSizeStr)
                            binding.storageWarningText.visibility = View.VISIBLE
                        } else {
                            binding.storageWarningText.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isLowStorage = false
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        binding.storageWarningText.visibility = View.GONE
                    }
                }
            }
        }
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

        binding.cancelButton.setOnClickListener {
            cancelAllServices()
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

        binding.cancelButton.setOnClickListener {
            cancelAllServices()
            eProgressDialog.dismiss()
        }

        eProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    private fun cancelAllServices() {
        val intent = Intent(com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_CANCEL_OPERATION)
        intent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(intent)
    }

    override fun onFileLongClick(file: File, view: View) {
        val position = adapter.files.indexOfFirst { it.file == file }
        if (position != -1) {
            startActionMode(position)
        }
    }

    override fun onItemClick(file: File, filePath: String) {
        if (actionMode != null) {
            val position = adapter.files.indexOfFirst { it.file == file }
            if (position != -1) {
                toggleSelection(position)
                if (getSelectedItemCount() == 0) {
                    actionMode?.finish()
                }
            }
        } else {
            if (!file.exists()) {
                Toast.makeText(requireContext(), getString(R.string.file_does_not_exist), Toast.LENGTH_SHORT).show()
                updateAdapterWithFullList()
                return
            }
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
                            show7zOptionsDialog()
                            true
                        }

                        R.id.m_archive_zip -> {
                            showZipOptionsDialog()
                            true
                        }

                        R.id.m_archive_tar -> {
                            showTarOptionsDialog()
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
                    selectedFiles.clear()
                    adapter.clearSelection()
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
        val filesToDelete = selectedFiles.map { it.absolutePath }
        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.confirm_delete))
            .setMessage(getString(R.string.confirm_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
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
        val file = adapter.files[position].file
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
        synchronized(pendingFileEvents) {
            pendingFileEvents.add(event to file)
        }

        if (processEventsJob?.isActive != true) {
            processEventsJob = lifecycleScope.launch(Dispatchers.Main) {
                delay(500)
                while (isActive) {
                    val hasEvents = synchronized(pendingFileEvents) {
                        pendingFileEvents.isNotEmpty()
                    }
                    if (!hasEvents) break
                    processPendingEvents()
                }
            }
        }
    }

    private suspend fun processPendingEvents() {
        val events = synchronized(pendingFileEvents) {
            val copy = pendingFileEvents.toList()
            pendingFileEvents.clear()
            copy
        }

        if (events.isEmpty()) return

        // Capture snapshot on Main thread
        val snapshotFiles = ArrayList(adapter.files)

        // Process in background
        val updatedFiles = withContext(Dispatchers.IO) {
            for ((event, file) in events) {
                when {
                    (event and CREATE) != 0 || (event and MOVED_TO) != 0 -> {
                        val existingPosition = snapshotFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
                        if (existingPosition != -1) {
                            snapshotFiles[existingPosition] = FileItem.fromFile(file)
                        } else {
                            snapshotFiles.add(FileItem.fromFile(file))
                        }
                    }
                    (event and DELETE) != 0 || (event and DELETE_SELF) != 0 || (event and MOVED_FROM) != 0 -> {
                        val position = snapshotFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
                        if (position != -1) {
                            snapshotFiles.removeAt(position)
                        }
                    }
                    (event and MODIFY) != 0 -> {
                        val position = snapshotFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
                        if (position != -1) {
                            snapshotFiles[position] = FileItem.fromFile(file)
                        }
                    }
                }
            }

            val comparator = when (sortBy) {
                SortBy.SORT_BY_NAME -> compareBy { it.file.name }
                SortBy.SORT_BY_SIZE -> compareBy { it.size }
                SortBy.SORT_BY_MODIFIED -> compareBy { it.lastModified }
                SortBy.SORT_BY_EXTENSION -> compareBy<FileItem> { it.file.extension }
            }

            val finalComparator = if (sortAscending) {
                compareBy<FileItem> { !it.isDirectory }.then(comparator)
            } else {
                compareBy<FileItem> { !it.isDirectory }.then(comparator.reversed())
            }

            snapshotFiles.sortWith(finalComparator)
            snapshotFiles
        }

        adapter.updateFilesAndFilter(updatedFiles, currentQuery)
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

        val buttons: List<View> = listOf(
            binding.btnLzma,
            binding.btnBzip2,
            binding.btnXz,
            binding.btnGzip,
            binding.btnZstd,
            binding.btnExtract
        )

        val defaultColors = buttons.associateWith { view ->
            if (view is Chip) view.chipBackgroundColor else view.backgroundTintList
        }

        checkStorageForOperation(
            binding.lowStorageWarning,
            file.parent ?: Environment.getExternalStorageDirectory().absolutePath,
            file.length(),
            defaultColors
        )

        var storageCheckJob: Job? = null
        binding.outputPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val path = s.toString()
                binding.outputPathDisplay.text = PathUtils.formatPath(path, requireContext())
                storageCheckJob?.cancel()
                storageCheckJob = lifecycleScope.launch {
                    delay(1000)
                    checkStorageForOperation(
                        binding.lowStorageWarning,
                        path,
                        file.length(),
                        defaultColors
                    )
                }
            }
        })

        binding.fileName.text = file.name

        val archivePath = sharedPreferences.getString(PREFERENCE_ARCHIVE_DIR_PATH, null)
        val defaultPath = if (!archivePath.isNullOrEmpty()) {
            if (File(archivePath).isAbsolute) {
                archivePath
            } else {
                File(Environment.getExternalStorageDirectory(), archivePath).absolutePath
            }
        } else {
            file.parent ?: Environment.getExternalStorageDirectory().absolutePath
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
            val destinationPath = binding.outputPathInput.text.toString()
            startCompressService(filePath, CompressorStreamFactory.LZMA, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnBzip2.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            startCompressService(filePath, CompressorStreamFactory.BZIP2, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnXz.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            startCompressService(filePath, CompressorStreamFactory.XZ, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnGzip.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            startCompressService(filePath, CompressorStreamFactory.GZIP, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnZstd.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            startCompressService(filePath, CompressorStreamFactory.ZSTANDARD, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnExtract.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            startExtractionService(filePath, null, destinationPath)
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

        binding.btnShare.setOnClickListener {
            val uri = FileProvider.getUriForFile(requireContext().applicationContext, "${BuildConfig.APPLICATION_ID}.provider", file)
            val mime: String = getMimeType(uri.toString())

            val intent = Intent(Intent.ACTION_SEND)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.type = mime
            intent.putExtra(Intent.EXTRA_STREAM, uri)

            startActivity(Intent.createChooser(intent, getString(R.string.share_file)))
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

    private fun startCompressService(file: String, compressionFormat: String, destinationPath: String?) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), CompressCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT, compressionFormat)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun startArchiveTarService(file: List<String>, archiveName: String, compressionFormat: String, destinationPath: String? = null, compressionLevel: Int = 3) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(file)
        val intent = Intent(requireContext(), ArchiveTarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_FORMAT, compressionFormat)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showBottomSheetOptions(filePaths: String, file: File) {
        val binding = BottomSheetOptionBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(binding.root)

        val buttons: List<View> = listOf(binding.btnExtract)

        val defaultColors = buttons.associateWith { it.backgroundTintList }

        checkStorageForOperation(
            binding.lowStorageWarning,
            file.parent ?: Environment.getExternalStorageDirectory().absolutePath,
            file.length(),
            defaultColors
        )

        var storageCheckJob: Job? = null
        binding.outputPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val path = s.toString()
                binding.outputPathDisplay.text = PathUtils.formatPath(path, requireContext())
                storageCheckJob?.cancel()
                storageCheckJob = lifecycleScope.launch {
                    delay(1000)
                    checkStorageForOperation(
                        binding.lowStorageWarning,
                        path,
                        file.length(),
                        defaultColors
                    )
                }
            }
        })

        binding.fileName.text = file.name

        val extractPath = sharedPreferences.getString(PREFERENCE_EXTRACT_DIR_PATH, null)
        val defaultPath = if (!extractPath.isNullOrEmpty()) {
            if (File(extractPath).isAbsolute) {
                extractPath
            } else {
                File(Environment.getExternalStorageDirectory(), extractPath).absolutePath
            }
        } else {
            file.parent ?: Environment.getExternalStorageDirectory().absolutePath
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
            val destinationPath = binding.outputPathInput.text.toString()

            if (supportedExtensions.any { fileExtension.endsWith(it) }) {
                startExtractionCsService(filePaths, destinationPath)
            } else {
                val loadingDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
                    .setMessage(getString(R.string.please_wait))
                    .setCancelable(false)
                    .create()

                if (file.extension.equals("tar", ignoreCase = true)) {
                    startExtractionService(filePaths, null, destinationPath)
                    bottomSheetDialog.dismiss()
                } else {
                    loadingDialog.show()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val isMultipartZip = MultipartArchiveHelper.isMultipartZip(file)
                        val isMultipart7z = MultipartArchiveHelper.isMultipart7z(file)
                        val isMultipartRar = MultipartArchiveHelper.isMultipartRar(file)

                        val isEncrypted = EncryptionCheckHelper.isEncrypted(file)
                        withContext(Dispatchers.Main) {
                            loadingDialog.dismiss()
                            if (isMultipartZip) {
                                if (isEncrypted) showPasswordInputMultiZipDialog(filePaths, destinationPath)
                                else startMultiZipExtractionService(filePaths, null, destinationPath)
                            } else if (isMultipart7z) {
                                if (isEncrypted) showPasswordInputMulti7zDialog(filePaths, destinationPath)
                                else startMulti7zExtractionService(filePaths, null, destinationPath)
                            } else if (isMultipartRar) {
                                if (isEncrypted) showPasswordInputMultiRarDialog(filePaths, destinationPath)
                                else startRarExtractionService(filePaths, null, destinationPath)
                            } else {
                                if (file.extension.equals("rar", ignoreCase = true)) {
                                    if (isEncrypted) showPasswordInputMultiRarDialog(filePaths, destinationPath)
                                    else startRarExtractionService(filePaths, null, destinationPath)
                                } else {
                                    if (isEncrypted) {
                                        showPasswordInputDialog(filePaths, destinationPath)
                                    } else {
                                        startExtractionService(filePaths, null, destinationPath)
                                    }
                                }
                            }
                            bottomSheetDialog.dismiss()
                        }
                    }
                }
            }
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

        binding.btnFileInfo.setOnClickListener {
            showFileInfo(file)
            bottomSheetDialog.dismiss()
        }

        binding.btnShare.setOnClickListener {
            val uri = FileProvider.getUriForFile(requireContext().applicationContext, "${BuildConfig.APPLICATION_ID}.provider", file)
            val mime: String = getMimeType(uri.toString())

            val intent = Intent(Intent.ACTION_SEND)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.type = mime
            intent.putExtra(Intent.EXTRA_STREAM, uri)

            startActivity(Intent.createChooser(intent, getString(R.string.share_file)))
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

    private fun showPasswordInputDialog(file: String, destinationPath: String?) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startExtractionService(file, password.ifBlank { null }, destinationPath)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startExtractionService(file, null, destinationPath)
            }
            .show()
    }

    private fun showPasswordInputMultiRarDialog(file: String, destinationPath: String?) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startRarExtractionService(file, password.ifBlank { null }, destinationPath)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startRarExtractionService(file, null, destinationPath)
            }
            .show()
    }

    private fun showPasswordInputMulti7zDialog(file: String, destinationPath: String?) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startMulti7zExtractionService(file, password.ifBlank { null }, destinationPath)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMulti7zExtractionService(file, null, destinationPath)
            }
            .show()
    }

    private fun showPasswordInputMultiZipDialog(file: String, destinationPath: String?) {
        val binding = PasswordInputDialogBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(binding.root)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = binding.passwordInput.text.toString()
                startMultiZipExtractionService(file, password.ifBlank { null }, destinationPath)
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMultiZipExtractionService(file, null, destinationPath)
            }
            .show()
    }

    private fun startExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startExtractionCsService(file: String, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startRarExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractRarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMulti7zExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractMultipart7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMultiZipExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractMultipartZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showFileInfo(file: File) {
        val binding = DialogFileInfoBinding.inflate(LayoutInflater.from(requireContext()))
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(binding.root)

        binding.fileName.text = file.name
        binding.filePath.text = file.absolutePath
        val fileSizeText = bytesToString(file.length())
        binding.fileSize.text = fileSizeText
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        binding.lastModified.text = dateFormat.format(Date(file.lastModified()))

        val clipboardManager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        binding.fileName.setOnLongClickListener {
            val clip = ClipData.newPlainText("File Name", file.name)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        binding.btnCopyPath.setOnClickListener {
            val clip = ClipData.newPlainText("File Path", file.absolutePath)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
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

        binding.okButton.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        ChecksumUtils.calculateChecksums(file, binding, lifecycleScope, requireContext())

        bottomSheetDialog.show()
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
            val file = adapter.files[i].file
            if (!selectedFiles.contains(file)) {
                selectedFiles.add(file)
                adapter.toggleSelection(i)
            }
        }
        updateActionModeTitle()
    }

    fun startZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, destinationPath: String? = null) {
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
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    fun startSplitZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, splitSize: Long?, destinationPath: String? = null) {
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
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
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

    fun startSevenZService(password: String?, archiveName: String, compressionLevel: Int, solid: Boolean, threadCount: Int, filesToArchive: List<String>, destinationPath: String? = null) {
        aProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(filesToArchive)
        val intent = Intent(requireContext(), Archive7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ServiceConstants.EXTRA_SOLID, solid)
            putExtra(ServiceConstants.EXTRA_THREAD_COUNT, threadCount)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private suspend fun getFiles(): ArrayList<FileItem> = withContext(Dispatchers.IO) {
        val files = ArrayList<FileItem>()
        val directories = ArrayList<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.newDirectoryStream(directory.toPath()).use { directoryStream ->
                    for (path in directoryStream) {
                        val file = path.toFile()
                        if (!showHiddenFiles && file.name.startsWith(".")) continue

                        if (file.isDirectory) {
                            directories.add(FileItem.fromFile(file))
                        } else {
                            files.add(FileItem.fromFile(file))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback if error
                val fileList = directory.listFiles()
                if (fileList != null) {
                    for (file in fileList) {
                        if (!showHiddenFiles && file.name.startsWith(".")) continue

                        if (file.isDirectory) {
                            directories.add(FileItem.fromFile(file))
                        } else {
                            files.add(FileItem.fromFile(file))
                        }
                    }
                }
            }
        } else {
            val fileList = directory.listFiles()
            if (fileList != null) {
                for (file in fileList) {
                    if (!showHiddenFiles && file.name.startsWith(".")) continue

                    if (file.isDirectory) {
                        directories.add(FileItem.fromFile(file))
                    } else {
                        files.add(FileItem.fromFile(file))
                    }
                }
            }
        }

        if (files.isEmpty() && directories.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.emptyFolderLayout.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.statusTextView.visibility = View.GONE
            }
            return@withContext files
        }

        // Sort files based on current criteria
        when (sortBy) {
            SortBy.SORT_BY_NAME -> {
                directories.sortBy { it.file.name }
                files.sortBy { it.file.name }
            }
            SortBy.SORT_BY_SIZE -> {
                directories.sortBy { it.size }
                files.sortBy { it.size }
            }
            SortBy.SORT_BY_MODIFIED -> {
                directories.sortBy { it.lastModified }
                files.sortBy { it.lastModified }
            }
            SortBy.SORT_BY_EXTENSION -> {
                directories.sortBy { it.file.extension }
                files.sortBy { it.file.extension }
            }
        }

        if (!sortAscending) {
            directories.reverse()
            files.reverse()
        }

        val combinedList = ArrayList<FileItem>()
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
            adapter.updateFilesAndFilter(ArrayList(), currentQuery)
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
                        adapter.updateFilesAndFilter(fullFileList, currentQuery)
                        binding.swipeRefreshLayout.isRefreshing = false
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
                        binding.swipeRefreshLayout.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun checkStorageForOperation(
        warningTextView: TextView,
        path: String,
        requiredSize: Long,
        viewsDefaultColors: Map<View, android.content.res.ColorStateList?>? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stat = StatFs(path)
                val availableSize = stat.availableBytes
                val safeRequiredSize = (requiredSize * 1.1).toLong()

                if (availableSize < safeRequiredSize) {
                    val availableSizeStr = android.text.format.Formatter.formatFileSize(requireContext(), availableSize)
                    val requiredSizeStr = android.text.format.Formatter.formatFileSize(requireContext(), requiredSize)
                    val warningText = getString(R.string.low_storage_warning_dynamic, availableSizeStr, requiredSizeStr)
                    withContext(Dispatchers.Main) {
                        warningTextView.text = warningText
                        warningTextView.visibility = View.VISIBLE
                        val errorColor = MaterialColors.getColor(warningTextView, androidx.appcompat.R.attr.colorError)
                        viewsDefaultColors?.keys?.forEach { view ->
                            if (view is Chip) {
                                view.chipBackgroundColor = android.content.res.ColorStateList.valueOf(errorColor)
                            } else {
                                view.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        warningTextView.visibility = View.GONE
                        viewsDefaultColors?.forEach { (view, color) ->
                            if (view is Chip) {
                                view.chipBackgroundColor = color
                            } else {
                                view.backgroundTintList = color
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun searchFiles(query: String?) {
        isSearchActive = !query.isNullOrEmpty()
        currentQuery = query

        if (query.isNullOrEmpty()) {
            updateAdapterWithFullList()
            return
        }

        fileLoadingJob?.cancel()
        binding.shimmerViewContainer.startShimmer()
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        adapter.updateFilesAndFilter(ArrayList(), currentQuery)

        val fastSearchEnabled = sharedPreferences.getBoolean("fast_search", false)

        // Cancel previous search if any
        searchJob?.cancel()

        searchJob = coroutineScope.launch {
            val searchFlow = if (fastSearchEnabled) {
                searchFilesWithMediaStore(query)
            } else {
                val basePath = Environment.getExternalStorageDirectory().absolutePath
                val sdCardPath = StorageHelper.getSdCardPath(requireContext())
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
                        binding.swipeRefreshLayout.isRefreshing = false
                    }
                }
                .collect { files ->
                    withContext(Dispatchers.Main) {
                        adapter.updateFilesAndFilter(ArrayList(files), currentQuery)
                        binding.shimmerViewContainer.stopShimmer()
                        binding.shimmerViewContainer.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.swipeRefreshLayout.isRefreshing = false
                    }
                }
        }
    }

    private fun searchAllFiles(directory: File, query: String): Flow<List<FileItem>> = flow {
        val results = mutableListOf<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)
        var lastEmitTime = 0L

        suspend fun searchRecursively(dir: File) {
            val files = dir.listFiles() ?: return

            for (file in files) {
                if (!showHiddenFiles && file.name.startsWith(".")) continue

                if (!currentCoroutineContext().isActive) return

                if (file.isDirectory) {
                    searchRecursively(file)
                } else if (file.name.contains(query, true)) {
                    results.add(FileItem.fromFile(file))
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastEmitTime > 300) {
                        emit(results.toList())
                        lastEmitTime = currentTime
                    }
                }
            }
        }

        searchRecursively(directory)
        emit(results.toList())
    }.distinctUntilChanged { old, new -> old.size == new.size }

    private fun searchFilesWithMediaStore(query: String): Flow<List<FileItem>> = flow {
        val results = mutableListOf<FileItem>()
        val showHiddenFiles = sharedPreferences.getBoolean("show_hidden_files", false)
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
                var lastEmitTime = 0L
                while (cursor.moveToNext()) {
                    if (!currentCoroutineContext().isActive) break

                    val filePath = cursor.getString(dataColumn)
                    val file = File(filePath)

                    if (!showHiddenFiles && file.name.startsWith(".")) continue

                    if (file.exists()) {
                        results.add(FileItem.fromFile(file))
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastEmitTime > 300) {
                            emit(results.toList())
                            lastEmitTime = currentTime
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // exceptions
        }
        emit(results.toList())
    }.distinctUntilChanged { old, new -> old.size == new.size }

    companion object {
        const val ARG_JOB_ID = "com.wirelessalien.zipxtract.ARG_JOB_ID"
        const val ARG_ARCHIVE_TYPE = "com.wirelessalien.zipxtract.ARG_ARCHIVE_TYPE"
        const val ARG_DIRECTORY_PATH = "com.wirelessalien.zipxtract.ARG_DIRECTORY_PATH"
    }
}