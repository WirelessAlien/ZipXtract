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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.icu.text.DateFormat
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.wirelessalien.zipxtract.BuildConfig
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.activity.SettingsActivity
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.databinding.FragmentArchiveBinding
import com.wirelessalien.zipxtract.service.DeleteFilesService
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

class ArchiveFragment : Fragment(), FileAdapter.OnItemClickListener {

    private lateinit var binding: FragmentArchiveBinding
    private lateinit var adapter: FileAdapter
    private val archiveExtensions = listOf("rar", "r00", "001", "7z", "7z.001", "zip", "tar", "gz", "bz2", "xz", "lz4", "lzma", "sz")
    private lateinit var eProgressDialog: AlertDialog
    private lateinit var progressText: TextView
    private lateinit var eProgressBar: LinearProgressIndicator
    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_MODIFIED, SORT_BY_EXTENSION
    }
    private var isSearchActive: Boolean = false

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
                        Snackbar.make(binding.root, getString(R.string.extraction_success), Snackbar.LENGTH_LONG)
                            .setAction(getString(R.string.open_folder)) {
                                navigateToParentDir(File(dirPath))
                            }
                            .show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.extraction_success), Toast.LENGTH_SHORT).show()
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
        sortBy = SortBy.valueOf(sharedPreferences.getString("sortBy", SortBy.SORT_BY_NAME.name) ?: SortBy.SORT_BY_NAME.name)
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
                val editor = sharedPreferences.edit()
                when (menuItem.itemId) {
                    R.id.menu_sort_by_name -> {
                        sortBy = SortBy.SORT_BY_NAME
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_by_size -> {
                        sortBy = SortBy.SORT_BY_SIZE
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_by_time_of_creation -> {
                        sortBy = SortBy.SORT_BY_MODIFIED
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_by_extension -> {
                        sortBy = SortBy.SORT_BY_EXTENSION
                        editor.putString("sortBy", sortBy.name)
                    }
                    R.id.menu_sort_ascending -> {
                        sortAscending = true
                        editor.putBoolean("sortAscending", sortAscending)
                    }
                    R.id.menu_sort_descending -> {
                        sortAscending = false
                        editor.putBoolean("sortAscending", sortAscending)
                    }
                    R.id.menu_settings -> {
                        val intent = Intent(requireContext(), SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
                editor.apply()
                updateAdapterWithFullList()
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val filter = IntentFilter().apply {
            addAction(BroadcastConstants.ACTION_EXTRACTION_COMPLETE)
            addAction(BroadcastConstants.ACTION_EXTRACTION_ERROR)
            addAction(BroadcastConstants.ACTION_EXTRACTION_PROGRESS)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(extractionReceiver, filter)

        extractProgressDialog()
        loadArchiveFiles()
    }

    private fun searchAllFiles(files: List<File>, query: String): List<File> {
        val result = mutableListOf<File>()

        for (file in files) {
            if (file.name.contains(query, true)) {
                result.add(file)
            }
        }
        return result
    }

    private fun loadArchiveFiles() {
        binding.progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val archiveFiles = getArchiveFiles()
            withContext(Dispatchers.Main) {
                adapter.updateFilesAndFilter(archiveFiles)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun searchFiles(query: String?) {
        isSearchActive = !query.isNullOrEmpty()
        binding.progressBar.visibility = View.VISIBLE

        adapter.updateFilesAndFilter(ArrayList())

        CoroutineScope(Dispatchers.IO).launch {
            val result = query?.let { searchAllFiles(getArchiveFiles(), it) } ?: getArchiveFiles()

            withContext(Dispatchers.Main) {
                adapter.updateFilesAndFilter(ArrayList(result))
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun getArchiveFiles(): ArrayList<File> {
        val archiveFiles = ArrayList<File>()
        val internalStorageDirectory = File(Environment.getExternalStorageDirectory().absolutePath)
        val sdCardPath = getSdCardPath()
        val directories = listOfNotNull(internalStorageDirectory, sdCardPath?.let { File(it) })

        for (directory in directories) {
            val files = directory.listFiles() ?: continue

            for (file in files) {
                if (file.isDirectory) {
                    archiveFiles.addAll(getArchiveFilesFromDirectory(file))
                } else if (archiveExtensions.contains(file.extension.lowercase())) {
                    archiveFiles.add(file)
                }
            }
        }

        when (sortBy) {
            SortBy.SORT_BY_NAME -> archiveFiles.sortBy { it.name }
            SortBy.SORT_BY_SIZE -> archiveFiles.sortBy { it.length() }
            SortBy.SORT_BY_MODIFIED -> archiveFiles.sortBy { it.lastModified() }
            SortBy.SORT_BY_EXTENSION -> archiveFiles.sortBy { it.extension }
        }

        if (!sortAscending) {
            archiveFiles.reverse()
        }

        return archiveFiles
    }

    private fun getArchiveFilesFromDirectory(directory: File): ArrayList<File> {
        val archiveFiles = ArrayList<File>()
        val files = directory.listFiles() ?: return archiveFiles

        for (file in files) {
            if (file.isDirectory) {
                archiveFiles.addAll(getArchiveFilesFromDirectory(file))
            } else if (archiveExtensions.contains(file.extension.lowercase())) {
                archiveFiles.add(file)
            }
        }
        return archiveFiles
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

    private fun updateAdapterWithFullList() {
        binding.progressBar.visibility = View.VISIBLE
        if (!isSearchActive) {
            CoroutineScope(Dispatchers.IO).launch {
                val archiveFiles = getArchiveFiles()
                withContext(Dispatchers.Main) {
                    adapter.updateFilesAndFilter(archiveFiles)
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun extractProgressDialog() {
        val ePDialogView = layoutInflater.inflate(R.layout.progress_dialog_extract, null)
        eProgressBar = ePDialogView.findViewById(R.id.progressBar)
        progressText = ePDialogView.findViewById(R.id.progressText)
        val backgroundButton = ePDialogView.findViewById<Button>(R.id.backgroundButton)

        backgroundButton.setOnClickListener {
            eProgressDialog.dismiss()
        }

        eProgressDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(ePDialogView)
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
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_option, null)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        bottomSheetDialog.setContentView(bottomSheetView)

        val btnExtract = bottomSheetView.findViewById<MaterialButton>(R.id.btnExtract)
        val btnMultiExtract = bottomSheetView.findViewById<MaterialButton>(R.id.btnMultiExtract)
        val btnMulti7zExtract = bottomSheetView.findViewById<MaterialButton>(R.id.btnMulti7zExtract)
        val btnFileInfo = bottomSheetView.findViewById<MaterialButton>(R.id.btnFileInfo)
        val btnMultiZipExtract = bottomSheetView.findViewById<MaterialButton>(R.id.btnMultiZipExtract)
        val btnOpenWith = bottomSheetView.findViewById<MaterialButton>(R.id.btnOpenWith)
        val fileNameTv = bottomSheetView.findViewById<TextView>(R.id.fileName)
        val btnDelete = bottomSheetView.findViewById<MaterialButton>(R.id.btnDelete)


        val filePath = file.absolutePath
        fileNameTv.text = file.name

        btnExtract.setOnClickListener {
            val fileExtension = file.name.split('.').takeLast(2).joinToString(".").lowercase()
            val supportedExtensions = listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz")

            if (supportedExtensions.any { fileExtension.endsWith(it) }) {
                startExtractionCsService(filePaths)
            } else {
                if (file.extension.lowercase() == "tar") {
                    startExtractionService(filePaths, null)
                } else {
                    showPasswordInputDialog(filePaths)
                }
            }
            bottomSheetDialog.dismiss()
        }

        btnMultiExtract.setOnClickListener {
            showPasswordInputMultiDialog(filePath)
            bottomSheetDialog.dismiss()
        }

        btnMulti7zExtract.setOnClickListener {
            showPasswordInputMulti7zDialog(filePath)
            bottomSheetDialog.dismiss()
        }

        btnMultiZipExtract.setOnClickListener {
            showPasswordInputMultiZipDialog(filePath)
            bottomSheetDialog.dismiss()
        }

        btnFileInfo.setOnClickListener {
            showFileInfo(file)
            bottomSheetDialog.dismiss()
        }

        btnOpenWith.setOnClickListener {
            val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
            bottomSheetDialog.dismiss()
        }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
                .setTitle(getString(R.string.confirm_delete))
                .setMessage(getString(R.string.confirm_delete_message))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    val filesToDelete = arrayListOf(file.absolutePath)
                    val intent = Intent(requireContext(), DeleteFilesService::class.java).apply {
                        putStringArrayListExtra(DeleteFilesService.EXTRA_FILES_TO_DELETE, filesToDelete)
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

    private fun getMimeType(file: File): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun showPasswordInputDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMultiDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startRarExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startRarExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMulti7zDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startMulti7zExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMulti7zExtractionService(file, null)
            }
            .show()
    }

    private fun showPasswordInputMultiZipDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordEditText.text.toString()
                startMultiZipExtractionService(file, password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                startMultiZipExtractionService(file, null)
            }
            .show()
    }

    private fun startExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractArchiveService::class.java).apply {
            putExtra(ExtractArchiveService.EXTRA_FILE_PATH, file)
            putExtra(ExtractArchiveService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startExtractionCsService(file: String) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractCsArchiveService::class.java).apply {
            putExtra(ExtractCsArchiveService.EXTRA_FILE_PATH, file)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startRarExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractRarService::class.java).apply {
            putExtra(ExtractRarService.EXTRA_FILE_PATH, file)
            putExtra(ExtractRarService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMulti7zExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractMultipart7zService::class.java).apply {
            putExtra(ExtractMultipart7zService.EXTRA_FILE_PATH, file)
            putExtra(ExtractMultipart7zService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun startMultiZipExtractionService(file: String, password: String?) {
        eProgressDialog.show()
        val intent = Intent(requireContext(), ExtractMultipartZipService::class.java).apply {
            putExtra(ExtractMultipartZipService.EXTRA_FILE_PATH, file)
            putExtra(ExtractMultipartZipService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showFileInfo(file: File) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_file_info, null)

        val fileNameTextView = dialogView.findViewById<TextView>(R.id.file_name)
        val filePathTextView = dialogView.findViewById<TextView>(R.id.file_path)
        val fileSizeTextView = dialogView.findViewById<TextView>(R.id.file_size)
        val lastModifiedTextView = dialogView.findViewById<TextView>(R.id.last_modified)
        val okButton = dialogView.findViewById<Button>(R.id.ok_button)

        fileNameTextView.text = getString(R.string.file_name, file.name)
        filePathTextView.text = getString(R.string.file_path, file.absolutePath)
        val fileSizeText = bytesToString(file.length())
        fileSizeTextView.text = getString(R.string.file_size, fileSizeText)
        val dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT, Locale.getDefault())
        lastModifiedTextView.text = getString(R.string.last_modified, dateFormat.format(Date(file.lastModified())))
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setView(dialogView)
            .create()

        okButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun bytesToString(bytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            bytes < kilobyte -> "$bytes B"
            bytes < megabyte -> String.format("%.2f KB", bytes.toFloat() / kilobyte)
            bytes < gigabyte -> String.format("%.2f MB", bytes.toFloat() / megabyte)
            else -> String.format("%.2f GB", bytes.toFloat() / gigabyte)
        }
    }
}