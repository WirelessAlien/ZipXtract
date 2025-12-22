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


import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.icu.text.DateFormat
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.BroadcastConstants.PREFERENCE_EXTRACT_DIR_PATH
import com.wirelessalien.zipxtract.constant.ServiceConstants.EXTRA_DESTINATION_PATH
import com.wirelessalien.zipxtract.constant.ServiceConstants.EXTRA_JOB_ID
import com.wirelessalien.zipxtract.constant.ServiceConstants.EXTRA_PASSWORD
import com.wirelessalien.zipxtract.databinding.BottomSheetOptionBinding
import com.wirelessalien.zipxtract.databinding.DialogFileInfoBinding
import com.wirelessalien.zipxtract.databinding.FragmentArchiveBinding
import com.wirelessalien.zipxtract.databinding.PasswordInputDialogBinding
import com.wirelessalien.zipxtract.databinding.ProgressDialogExtractBinding
import com.wirelessalien.zipxtract.helper.ChecksumUtils
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.service.DeleteFilesService
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

class ArchiveFragment : Fragment(), FileAdapter.OnItemClickListener {

    private lateinit var binding: FragmentArchiveBinding
    private lateinit var adapter: FileAdapter
    private val archiveExtensions = listOf(
        "rar",
        "r00",
        "001",
        "7z",
        "7z.001",
        "zip",
        "tar",
        "gz",
        "bz2",
        "xz",
        "lz4",
        "lzma",
        "sz"
    )
    private lateinit var eProgressDialog: AlertDialog
    private lateinit var progressText: TextView
    private lateinit var eProgressBar: LinearProgressIndicator
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }

    private var isSearchActive: Boolean = false
    private lateinit var fileOperationsDao: FileOperationsDao

    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true
    private lateinit var sharedPreferences: SharedPreferences
    private var searchView: SearchView? = null
    private var searchHandler: Handler? = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val extractionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded) return

            when (intent?.action) {
                BroadcastConstants.ACTION_EXTRACTION_COMPLETE -> {
                    eProgressDialog.dismiss()
                    val dirPath = intent.getStringExtra(BroadcastConstants.EXTRA_DIR_PATH)

                    if (dirPath != null) {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.open_folder),
                            Snackbar.LENGTH_LONG
                        )
                            .setAction(getString(R.string.ok)) {
                                navigateToParentDir(File(dirPath))
                            }
                            .show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.extraction_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                BroadcastConstants.ACTION_EXTRACTION_ERROR -> {
                    eProgressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(BroadcastConstants.EXTRA_ERROR_MESSAGE)
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                }

                BroadcastConstants.ACTION_EXTRACTION_PROGRESS -> {
                    val progress = intent.getIntExtra(BroadcastConstants.EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    eProgressBar.progress = progress
                    progressText.text = getString(R.string.extracting_progress, progress)
                }
            }
        }
    }

    private fun navigateToParentDir(parentDir: File) {
        val fragment = MainFragment().apply {
            arguments = Bundle().apply {
                putString("path", parentDir.absolutePath)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
        fileOperationsDao = FileOperationsDao(requireContext())
        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
        sortBy = SortBy.valueOf(
            sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name)
                ?: SortBy.SORT_BY_NAME.name
        )
        sortAscending = sharedPreferences.getBoolean("sortAscending", true)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = FileAdapter(requireContext(), null, ArrayList())
        adapter.setOnItemClickListener(this)
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
                        }

                        R.id.menu_sort_by_size -> {
                            sortBy = SortBy.SORT_BY_SIZE
                            putString("sortBy", sortBy.name)
                        }

                        R.id.menu_sort_by_time_of_creation -> {
                            sortBy = SortBy.SORT_BY_MODIFIED
                            putString("sortBy", sortBy.name)
                        }

                        R.id.menu_sort_by_extension -> {
                            sortBy = SortBy.SORT_BY_EXTENSION
                            putString("sortBy", sortBy.name)
                        }

                        R.id.menu_sort_ascending -> {
                            sortAscending = true
                            putBoolean("sortAscending", sortAscending)
                        }

                        R.id.menu_sort_descending -> {
                            sortAscending = false
                            putBoolean("sortAscending", sortAscending)
                        }

                        R.id.menu_settings -> {
                            val intent = Intent(requireContext(), SettingsActivity::class.java)
                            startActivity(intent)
                        }
                    }
                }
                updateAdapterWithFullList()
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val filter = IntentFilter().apply {
            addAction(BroadcastConstants.ACTION_EXTRACTION_COMPLETE)
            addAction(BroadcastConstants.ACTION_EXTRACTION_ERROR)
            addAction(BroadcastConstants.ACTION_EXTRACTION_PROGRESS)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(extractionReceiver, filter)

        extractProgressDialog()
        setupFilterChips()
        viewLifecycleOwner.lifecycleScope.launch {
            loadArchiveFiles(null)
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            scanStorageAndLoadFiles()
        }
    }

    private fun setupFilterChips() {
        val extensions = listOf("All", "zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        val chipGroup = binding.chipGroupFilter
        chipGroup.isSingleSelection = true
        extensions.forEach { extension ->
            val chip = Chip(requireContext())
            chip.text = extension
            chip.isCheckable = true
            chip.isCheckedIconVisible = false
            chipGroup.addView(chip)

            if (extension == "All") {
                chip.isChecked = true
            }
        }

        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val checkedChipId = checkedIds[0]
                val checkedChip = group.findViewById<Chip>(checkedChipId)
                val selectedExtension = if (checkedChip.text.toString() == "All") {
                    null
                } else {
                    checkedChip.text.toString()
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    loadArchiveFiles(selectedExtension)
                }
            }
        }
    }

    private suspend fun loadArchiveFiles(extension: String?, showShimmer: Boolean = true) {
        if (showShimmer) {
            binding.shimmerViewContainer.startShimmer()
            binding.shimmerViewContainer.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        }

        val archiveFiles = withContext(Dispatchers.IO) {
            getArchiveFiles(null, extension)
        }
        adapter.updateFilesAndFilter(archiveFiles)

        if (showShimmer) {
            binding.shimmerViewContainer.stopShimmer()
            binding.shimmerViewContainer.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun searchFiles(query: String?) {
        isSearchActive = !query.isNullOrEmpty()

        binding.shimmerViewContainer.startShimmer()
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        adapter.updateFilesAndFilter(ArrayList())

        searchJob?.cancel()

        searchJob = coroutineScope.launch {
            searchAllFiles(query)
                .flowOn(Dispatchers.IO)
                .catch { e ->
                    withContext(Dispatchers.Main) {
                        binding.shimmerViewContainer.stopShimmer()
                        binding.shimmerViewContainer.visibility = View.GONE
                        Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
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

    private fun searchAllFiles(query: String?): Flow<List<File>> = flow {
        val results = getArchiveFiles(query, null)
        emit(results)
    }

    private fun getArchiveFiles(query: String? = null, extension: String? = null): ArrayList<File> {
        val archiveFiles = ArrayList<File>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (extension != null) {
            selectionParts.add("${MediaStore.Files.FileColumns.DATA} LIKE ?")
            selectionArgs.add("%.$extension")
        } else {
            val extensionSelection = archiveExtensions.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            }
            selectionParts.add("($extensionSelection)")
            selectionArgs.addAll(archiveExtensions.map { "%.$it" })
        }

        if (!query.isNullOrBlank()) {
            selectionParts.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            selectionArgs.add("%$query%")
        }

        val finalSelection = selectionParts.joinToString(" AND ")
        val finalSelectionArgs = selectionArgs.toTypedArray()

        val sortOrderColumn = when (sortBy) {
            SortBy.SORT_BY_NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
            SortBy.SORT_BY_SIZE -> MediaStore.Files.FileColumns.SIZE
            SortBy.SORT_BY_MODIFIED -> MediaStore.Files.FileColumns.DATE_MODIFIED
            SortBy.SORT_BY_EXTENSION -> MediaStore.Files.FileColumns.DISPLAY_NAME // Fallback for extension sort
        }
        val sortDirection = if (sortAscending) "ASC" else "DESC"
        val sortOrder = "$sortOrderColumn $sortDirection"

        try {
            requireContext().contentResolver.query(
                uri,
                projection,
                finalSelection,
                finalSelectionArgs,
                sortOrder
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    if (path != null) {
                        val file = File(path)
                        if (file.isFile && archiveExtensions.contains(file.extension.lowercase())) {
                            archiveFiles.add(file)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (sortBy == SortBy.SORT_BY_EXTENSION) {
            if (sortAscending) {
                archiveFiles.sortBy { it.extension }
            } else {
                archiveFiles.sortByDescending { it.extension }
            }
        }

        return archiveFiles
    }

    private fun scanStorageAndLoadFiles() {
        if (!isAdded) return

        binding.shimmerViewContainer.startShimmer()
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        val externalStoragePath =
            Environment.getExternalStorageDirectory().absolutePath

        MediaScannerConnection.scanFile(
            context, arrayOf(externalStoragePath), null
        ) { _, _ ->

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                viewLifecycleOwner.lifecycleScope.launch {
                    if (!isAdded) return@launch

                    loadArchiveFiles(null, false)

                    binding.shimmerViewContainer.stopShimmer()
                    binding.shimmerViewContainer.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }


    private fun updateAdapterWithFullList() {
        if (!isSearchActive) {
            viewLifecycleOwner.lifecycleScope.launch {
                loadArchiveFiles(null)
            }
        }
    }

    private fun extractProgressDialog() {
        val binding = ProgressDialogExtractBinding.inflate(layoutInflater)
        eProgressBar = binding.progressBar
        progressText = binding.progressText

        binding.backgroundButton.setOnClickListener {
            eProgressDialog.dismiss()
        }

        eProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    private fun updateProgressBar(progress: Int) {
        binding.linearProgressBar.progress = progress
        if (progress == 100) {
            binding.linearProgressBar.visibility = View.GONE
        } else {
            binding.linearProgressBar.visibility = View.VISIBLE
        }
    }

    override fun onItemClick(file: File, filePath: String) {
        showBottomSheetOptions(filePath, file)
    }

    @SuppressLint("InflateParams")
    private fun showBottomSheetOptions(filePaths: String, file: File) {
        val binding = BottomSheetOptionBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(binding.root)

        val buttons = listOf(
            binding.btnExtract,
            binding.btnMultiExtract,
            binding.btnMulti7zExtract,
            binding.btnMultiZipExtract
        )

        val defaultColor = binding.btnExtract.backgroundTintList

        checkStorageForExtraction(
            binding.lowStorageWarning,
            file.parent ?: Environment.getExternalStorageDirectory().absolutePath,
            file.length(),
            buttons,
            defaultColor
        )

        var storageCheckJob: Job? = null
        binding.outputPathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                storageCheckJob?.cancel()
                storageCheckJob = lifecycleScope.launch {
                    delay(1000)
                    checkStorageForExtraction(
                        binding.lowStorageWarning,
                        s.toString(),
                        file.length(),
                        buttons,
                        defaultColor
                    )
                }
            }
        })

        val filePath = file.absolutePath
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

        binding.outputPathLayout.setEndIconOnClickListener {
            val pathPicker = PathPickerFragment.newInstance()
            pathPicker.setPathPickerListener(object : PathPickerFragment.PathPickerListener {
                override fun onPathSelected(path: String) {
                    binding.outputPathInput.setText(path)
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

        val dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.DEFAULT,
            DateFormat.SHORT,
            Locale.getDefault()
        )
        binding.fileDate.text = dateFormat.format(Date(file.lastModified()))

        binding.btnExtract.setOnClickListener {
            val fileExtension = file.name.split('.').takeLast(2).joinToString(".").lowercase()
            val supportedExtensions =
                listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz")
            val destinationPath = binding.outputPathInput.text.toString()

            if (supportedExtensions.any { fileExtension.endsWith(it) }) {
                startExtractionCsService(filePaths, destinationPath)
            } else {
                if (file.extension.lowercase() == "tar") {
                    startExtractionService(filePaths, null, destinationPath)
                    bottomSheetDialog.dismiss()
                } else if (file.extension.equals("zip", ignoreCase = true)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        var isEncrypted = true
                        try {
                            net.lingala.zip4j.ZipFile(file).use { zipFile ->
                                isEncrypted = zipFile.isEncrypted
                            }
                        } catch (e: Exception) {
                            // isEncrypted remains true
                        }
                        withContext(Dispatchers.Main) {
                            if (isEncrypted) {
                                showPasswordInputDialog(filePaths, destinationPath)
                            } else {
                                startExtractionService(filePaths, null, destinationPath)
                            }
                            bottomSheetDialog.dismiss()
                        }
                    }
                } else {
                    showPasswordInputDialog(filePaths, destinationPath)
                    bottomSheetDialog.dismiss()
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

        binding.btnMultiExtract.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            showPasswordInputMultiRarDialog(filePath, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnMulti7zExtract.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            showPasswordInputMulti7zDialog(filePath, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnMultiZipExtract.setOnClickListener {
            val destinationPath = binding.outputPathInput.text.toString()
            showPasswordInputMultiZipDialog(filePath, destinationPath)
            bottomSheetDialog.dismiss()
        }

        binding.btnFileInfo.setOnClickListener {
            showFileInfo(file)
            bottomSheetDialog.dismiss()
        }

        binding.btnOpenWith.setOnClickListener {
            val uri = FileProvider.getUriForFile(
                requireContext().applicationContext,
                "${BuildConfig.APPLICATION_ID}.provider",
                file
            )
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
                    val filesToDelete = arrayListOf(file.absolutePath)
                    val jobId = fileOperationsDao.addFilesForJob(filesToDelete)
                    val intent = Intent(requireContext(), DeleteFilesService::class.java).apply {
                        putExtra(EXTRA_JOB_ID, jobId)
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)

                    val position = adapter.files.indexOf(file)
                    if (position != -1) {
                        adapter.removeItem(position)
                    }

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
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startExtractionCsService(file: String, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractCsArchiveService::class.java).apply {
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startRarExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractRarService::class.java).apply {
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMulti7zExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractMultipart7zService::class.java).apply {
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMultiZipExtractionService(file: String, password: String?, destinationPath: String?) {
        eProgressDialog.show()
        val jobId = fileOperationsDao.addFilesForJob(listOf(file))
        val intent = Intent(requireContext(), ExtractMultipartZipService::class.java).apply {
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_PASSWORD, password)
            putExtra(EXTRA_DESTINATION_PATH, destinationPath)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showFileInfo(file: File) {
        val binding = DialogFileInfoBinding.inflate(LayoutInflater.from(requireContext()))

        binding.fileName.text = Editable.Factory.getInstance().newEditable(file.name)
        binding.filePath.text = Editable.Factory.getInstance().newEditable(file.absolutePath)
        val fileSizeText = bytesToString(file.length())
        binding.fileSize.text = Editable.Factory.getInstance().newEditable(fileSizeText)
        val dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.DEFAULT,
            DateFormat.SHORT,
            Locale.getDefault()
        )
        binding.lastModified.text =
            Editable.Factory.getInstance().newEditable(dateFormat.format(Date(file.lastModified())))

        binding.md5Checksum.keyListener = null
        binding.sha1Checksum.keyListener = null
        binding.sha256Checksum.keyListener = null

        val clipboardManager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        binding.fileName.setOnLongClickListener {
            val clip = ClipData.newPlainText("File Name", file.name)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                getString(R.string.copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        binding.filePath.setOnLongClickListener {
            val clip = ClipData.newPlainText("File Path", file.absolutePath)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                getString(R.string.copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        binding.md5Checksum.setOnLongClickListener {
            val clip = ClipData.newPlainText("MD5", binding.md5Checksum.text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                getString(R.string.copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        binding.sha1Checksum.setOnLongClickListener {
            val clip = ClipData.newPlainText("SHA1", binding.sha1Checksum.text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                getString(R.string.copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        binding.sha256Checksum.setOnLongClickListener {
            val clip = ClipData.newPlainText("SHA256", binding.sha256Checksum.text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(
                requireContext(),
                getString(R.string.copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
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

    private fun checkStorageForExtraction(
        warningTextView: TextView,
        path: String,
        requiredSize: Long,
        buttons: List<View>? = null,
        defaultColor: android.content.res.ColorStateList? = null
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
                        val errorColor = MaterialColors.getColor(warningTextView, com.google.android.material.R.attr.colorOnError)
                        buttons?.forEach { it.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor) }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        warningTextView.visibility = View.GONE
                        if (defaultColor != null) {
                            buttons?.forEach { it.backgroundTintList = defaultColor }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}