package com.wirelessalien.zipxtract.activity

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentTransaction
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.FileAdapter
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_7Z_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_ARCHIVE_SPLIT_ZIP_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_COMPLETE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_ERROR
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACTION_PROGRESS
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_EXTRACT_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_MULTI_7Z_EXTRACTION_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_MULTI_ZIP_EXTRACTION_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.ACTION_RAR_EXTRACTION_CANCEL
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_ERROR_MESSAGE
import com.wirelessalien.zipxtract.constant.BroadcastConstants.EXTRA_PROGRESS
import com.wirelessalien.zipxtract.databinding.ActivityMainBinding
import com.wirelessalien.zipxtract.fragment.SevenZOptionDialogFragment
import com.wirelessalien.zipxtract.fragment.ZipOptionDialogFragment
import com.wirelessalien.zipxtract.service.Archive7zService
import com.wirelessalien.zipxtract.service.ArchiveSplitZipService
import com.wirelessalien.zipxtract.service.ArchiveZipService
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date


class MainActivity : AppCompatActivity(), FileAdapter.OnItemClickListener, FileAdapter.OnFileLongClickListener {

    enum class SortBy {
           SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_TIME_OF_CREATION, SORT_BY_EXTENSION
    }

    companion object {
        private const val STORAGE_PERMISSION_CODE = 123
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private var isSearchActive: Boolean = false


    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true

    private var currentPath: String? = null

    private lateinit var toolbar: MaterialToolbar


    var actionMode: ActionMode? = null
    private val selectedFiles = mutableListOf<File>()
    private var fileObserver: FileObserver? = null

    private var searchView: SearchView? = null

    private var isObserving: Boolean = false
    private var fileUpdateJob: Job? = null
    private var searchHandler: Handler? = null
    private var searchRunnable: Runnable? = null
    private lateinit var progressDialog: AlertDialog
    private lateinit var progressText: TextView
    private var areFabsVisible: Boolean = false

    private lateinit var progressBarDialog: LinearProgressIndicator
    private var isLargeLayout: Boolean = false
    private lateinit var binding: ActivityMainBinding


    private val extractionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_EXTRACTION_COMPLETE -> {
                    // Handle extraction complete
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Extraction Complete", Toast.LENGTH_SHORT).show()
                }
                ACTION_EXTRACTION_ERROR -> {
                    // Handle extraction error
                    progressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    Toast.makeText(this@MainActivity, "Extraction Error: $errorMessage", Toast.LENGTH_SHORT).show()
                }
                ACTION_EXTRACTION_PROGRESS -> {
                    // Handle extraction progress
                    val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    progressBarDialog.progress = progress
                    progressText.text = "Extracting... $progress%"
                }
                ACTION_ARCHIVE_COMPLETE -> {
                    // Handle archive complete
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Archive Complete", Toast.LENGTH_SHORT).show()
                }
                ACTION_ARCHIVE_ERROR -> {
                    // Handle archive error
                    progressDialog.dismiss()
                    val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                    Toast.makeText(this@MainActivity, "Archive Error: $errorMessage", Toast.LENGTH_SHORT).show()
                }
                ACTION_ARCHIVE_PROGRESS -> {
                    // Handle extraction progress
                    val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    updateProgressBar(progress)
                    progressBarDialog.progress = progress
                    progressText.text = "Extracting... $progress%"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isLargeLayout = resources.getBoolean(R.bool.large_layout)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        // Get current path
        currentPath = intent.getStringExtra("path")
        searchHandler = Handler(Looper.getMainLooper())

        // Check for permissions
        if (!checkStoragePermissions()) {
            requestForStoragePermissions()
        } else {
            initRecyclerView()
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
        LocalBroadcastManager.getInstance(this).registerReceiver(extractionReceiver, filter)

        extractProgressDialog()
        archiveProgressDialog()

        binding.mainFab.setOnClickListener {
            if (areFabsVisible) {
                hideExtendedFabs()
            } else {
                showExtendedFabs()
            }
        }

        // Set click listeners for extended FABs
        binding.createZipFab.setOnClickListener {
            showZipOptionsDialog()
            hideExtendedFabs()
        }

        binding.create7zFab.setOnClickListener {
            show7zOptionsDialog()
            hideExtendedFabs()
        }
    }

    private fun showExtendedFabs() {
        binding.createZipFab.show()
        binding.create7zFab.show()
        areFabsVisible = true
    }

    private fun hideExtendedFabs() {
        binding.createZipFab.hide()
        binding.create7zFab.hide()
        areFabsVisible = false
    }

    private fun show7zOptionsDialog() {
        val fragmentManager = supportFragmentManager
        val newFragment = SevenZOptionDialogFragment.newInstance(adapter)
        if (isLargeLayout) {
            // Show the fragment as a dialog.
            newFragment.show(fragmentManager, "SevenZOptionDialogFragment")
        } else {
            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()
        }
        actionMode?.finish() // Destroy the action mode
    }

    private fun showZipOptionsDialog() {
        val fragmentManager = supportFragmentManager
        val newFragment = ZipOptionDialogFragment.newInstance(adapter)
        if (isLargeLayout) {
            // Show the fragment as a dialog.
            newFragment.show(fragmentManager, "ZipOptionDialogFragment")
        } else {
            // Show the fragment fullscreen.
            val transaction = fragmentManager.beginTransaction()
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            transaction.add(android.R.id.content, newFragment)
                .addToBackStack(null)
                .commit()
        }
        actionMode?.finish()
    }

    private fun archiveProgressDialog() {
        val aDialogView = layoutInflater.inflate(R.layout.progress_dialog_archive, null)
        progressBarDialog = aDialogView.findViewById(R.id.progressBar)
        progressText = aDialogView.findViewById(R.id.progressText)
        val cancelButton = aDialogView.findViewById<Button>(R.id.cancelButton)
        val backgroundButton = aDialogView.findViewById<Button>(R.id.backgroundButton)

        cancelButton.setOnClickListener {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            val isArchive7zServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == Archive7zService::class.java.name }

            if (isArchive7zServiceRunning) {
                val cancelExtractIntent = Intent(this, Archive7zService::class.java).apply {
                    action = ACTION_ARCHIVE_7Z_CANCEL
                }
                startService(cancelExtractIntent)
            }

            val isArchiveZipServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ArchiveZipService::class.java.name }

            if (isArchiveZipServiceRunning) {
                val cancelZipIntent = Intent(this, ArchiveZipService::class.java).apply {
                    action = ACTION_ARCHIVE_7Z_CANCEL
                }
                startService(cancelZipIntent)
            }

            val isArchiveSplitZipServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ArchiveSplitZipService::class.java.name }

            if (isArchiveSplitZipServiceRunning) {
                val cancelZipIntent = Intent(this, ArchiveSplitZipService::class.java).apply {
                    action = ACTION_ARCHIVE_SPLIT_ZIP_CANCEL
                }
                startService(cancelZipIntent)
            }

            progressDialog.dismiss()
        }

        backgroundButton.setOnClickListener {
            progressDialog.dismiss()
        }

        progressDialog = MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setView(aDialogView)
            .setCancelable(false)
            .create()
    }

    private fun extractProgressDialog() {
        val ePDialogView = layoutInflater.inflate(R.layout.progress_dialog_extract, null)
        progressBarDialog = ePDialogView.findViewById(R.id.progressBar)
        progressText = ePDialogView.findViewById(R.id.progressText)
        val cancelButton = ePDialogView.findViewById<Button>(R.id.cancelButton)
        val backgroundButton = ePDialogView.findViewById<Button>(R.id.backgroundButton)

        cancelButton.setOnClickListener {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            val isExtractArchiveServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractArchiveService::class.java.name }

            if (isExtractArchiveServiceRunning) {
                val cancelExtractIntent = Intent(this, ExtractArchiveService::class.java).apply {
                    action = ACTION_EXTRACT_CANCEL
                }
                startService(cancelExtractIntent)
            }

            val isExtractRarServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractRarService::class.java.name }

            if (isExtractRarServiceRunning) {
                val cancelRarIntent = Intent(this, ExtractRarService::class.java).apply {
                    action = ACTION_RAR_EXTRACTION_CANCEL
                }
                startService(cancelRarIntent)
            }

            val isExtractMultipart7zServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractMultipart7zService::class.java.name }

            if (isExtractMultipart7zServiceRunning) {
                val cancelRarIntent = Intent(this, ExtractMultipart7zService::class.java).apply {
                    action = ACTION_MULTI_7Z_EXTRACTION_CANCEL
                }
                startService(cancelRarIntent)
            }

            val isExtractMultipartZipServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == ExtractMultipart7zService::class.java.name }

            if (isExtractMultipartZipServiceRunning) {
                val cancelRarIntent = Intent(this, ExtractMultipartZipService::class.java).apply {
                    action = ACTION_MULTI_ZIP_EXTRACTION_CANCEL
                }
                startService(cancelRarIntent)
            }

            progressDialog.dismiss()
        }

        backgroundButton.setOnClickListener {
            progressDialog.dismiss()
        }

        progressDialog = MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
            .setView(ePDialogView)
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
                // If user clicked on a directory navigate into it
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("path", file.absolutePath)
                startActivity(intent)
            } else {
                // If user clicked on a file, show bottom sheet options
                showBottomSheetOptions(filePath, file)
            }
        }
    }

    fun startActionMode(position: Int) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    menuInflater.inflate(R.menu.menu_action, menu)
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
                            val fragmentManager = supportFragmentManager
                            val newFragment = SevenZOptionDialogFragment.newInstance(adapter)
                            if (isLargeLayout) {
                                // Show the fragment as a dialog.
                                newFragment.show(fragmentManager, "SevenZOptionDialogFragment")
                            } else {
                                // Show the fragment fullscreen.
                                val transaction = fragmentManager.beginTransaction()
                                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                                transaction.add(android.R.id.content, newFragment)
                                    .addToBackStack(null)
                                    .commit()
                            }
                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.m_archive_zip -> {
                            val fragmentManager = supportFragmentManager
                            val newFragment = ZipOptionDialogFragment.newInstance(adapter)
                            if (isLargeLayout) {
                                // Show the fragment as a dialog.
                                newFragment.show(fragmentManager, "ZipOptionDialogFragment")
                            } else {
                                // Show the fragment fullscreen.
                                val transaction = fragmentManager.beginTransaction()
                                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                                transaction.add(android.R.id.content, newFragment)
                                    .addToBackStack(null)
                                    .commit()
                            }
                            actionMode?.finish() // Destroy the action mode
                            true
                        }

                        R.id.m_extract_multipart -> {
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

    private fun clearSelection() {
        selectedFiles.clear()
//        adapter.clearSelection()
    }

    private fun updateProgressBar(progress: Int) {
        // Update the progress bar
        binding.progressBar.progress = progress
        if (progress == 100) {
            // Hide the progress bar when progress is complete
            binding.progressBar.visibility = View.GONE
        } else {
            // Show the progress bar if not already visible
            binding.progressBar.visibility = View.VISIBLE
        }
    }



    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (!checkStoragePermissions()) {
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                initRecyclerView()
                updateAdapterWithFullList()
            }
        }
    }

    private fun requestForStoragePermissions() {
        //Android is 11 (R) or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", this.packageName, null)
                intent.setData(uri)
                storageActivityResultLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storageActivityResultLauncher.launch(intent)
            }
        } else {
            //Below android 11
            ActivityCompat.requestPermissions(
                this, arrayOf<String>(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_CODE
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
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            if (result.resultCode == Activity.RESULT_OK) {
                // Permission granted
                initRecyclerView()
                updateAdapterWithFullList()
            } else {
                // Permission denied
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }


    private fun initRecyclerView() {
        // Initialize RecyclerView and adapter as before
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.isNestedScrollingEnabled = false
        adapter = FileAdapter(this, this, ArrayList())
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        binding.recyclerView.adapter = adapter
        // Update the adapter with the initial file list
        updateAdapterWithFullList()
    }


    // Checks whether all necessary permissions are granted
    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //Android is 11 (R) or above
            Environment.isExternalStorageManager()
        } else {
            //Below android 11
            val write =
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun startFileObserver() {
        if (!isObserving) {
            val directoryToObserve = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

            fileObserver = object : FileObserver(directoryToObserve.path, CREATE or DELETE or MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    // Handle file change event
                    if (event and (CREATE or DELETE) != 0 && path != null) {
                        val fullPath = "$directoryToObserve/$path"
                        val file = File(fullPath)

                        // Check if the path corresponds to a directory
                        if (file.isDirectory || event and CREATE != 0) {
                            // Update the adapter with the updated file list
                            updateAdapterWithFullList()
                        }
                    }
                }
            }

            // Start the file observer
            fileObserver?.startWatching()
            isObserving = true

            // Start continuous update job
            fileUpdateJob = CoroutineScope(Dispatchers.Main).launch {
                while (isObserving) {
                    updateAdapterWithFullList()
                    delay(1000) // Update every second
                }
            }
        }
    }

    private fun stopFileObserver() {
        // Stop the file observer when the activity is destroyed
        fileObserver?.stopWatching()
        isObserving = false

        // Cancel the continuous update job
        fileUpdateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop the file observer when the activity is destroyed
        stopFileObserver()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(extractionReceiver)
    }


    private fun showBottomSheetOptions(filePaths: String, file: File ) {
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_option, null)
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(bottomSheetView)

        val btnExtract = bottomSheetView.findViewById<Button>(R.id.btnExtract)
        val btnMultiExtract = bottomSheetView.findViewById<Button>(R.id.btnMultiExtract)
        val btnMulti7zExtract = bottomSheetView.findViewById<Button>(R.id.btnMulti7zExtract)
        val btnFileInfo = bottomSheetView.findViewById<Button>(R.id.btnFileInfo)
        val btnCompress7z = bottomSheetView.findViewById<Button>(R.id.btnCompress7z)
        val btnMultiZipExtract = bottomSheetView.findViewById<Button>(R.id.btnMultiZipExtract)


        val filePath = file.absolutePath

        btnExtract.setOnClickListener {
            val fileExtension = file.name.split('.').takeLast(2).joinToString(".").lowercase()
            Log.d("FileExtension", fileExtension)
            val supportedExtensions = listOf("tar.bz2", "tar.gz", "tar.lz4", "tar.lzma", "tar.sz", "tar.xz", "tar.z", "tar.zstd")

            if (supportedExtensions.any { fileExtension.endsWith(it) }) {
                startExtractionCsService(filePaths)
            } else {
                showPasswordInputDialog(filePaths)
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

        btnCompress7z.setOnClickListener {
            // Compression logic here
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }


    private fun showPasswordInputDialog(file: String) {
        val dialogView = layoutInflater.inflate(R.layout.password_input_dialog, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(this)
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

        MaterialAlertDialogBuilder(this)
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

        MaterialAlertDialogBuilder(this)
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

        MaterialAlertDialogBuilder(this)
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
        progressDialog.show()
        val intent = Intent(this, ExtractArchiveService::class.java).apply {
            putExtra(ExtractArchiveService.EXTRA_FILE_PATH, file)
            putExtra(ExtractArchiveService.EXTRA_PASSWORD, password)

        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startExtractionCsService(file: String) {
        progressDialog.show()
        val intent = Intent(this, ExtractCsArchiveService::class.java).apply {
            putExtra(ExtractArchiveService.EXTRA_FILE_PATH, file)

        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startRarExtractionService(file: String, password: String?) {
        progressDialog.show()
        val intent = Intent(this, ExtractRarService::class.java).apply {
            putExtra(ExtractRarService.EXTRA_FILE_PATH, file)
            putExtra(ExtractRarService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startMulti7zExtractionService(file: String, password: String?) {
        progressDialog.show()
        val intent = Intent(this, ExtractMultipart7zService::class.java).apply {
            putExtra(ExtractMultipart7zService.EXTRA_FILE_PATH, file)
            putExtra(ExtractMultipart7zService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startMultiZipExtractionService(file: String, password: String?) {
        progressDialog.show()
        val intent = Intent(this, ExtractMultipartZipService::class.java).apply {
            putExtra(ExtractMultipartZipService.EXTRA_FILE_PATH, file)
            putExtra(ExtractMultipartZipService.EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(this, intent)
    }


    private fun showFileInfo(file: File) {
        val fileName = file.name
        val filePath = file.absolutePath
        val fileSize = file.length()
        val lastModified = Date(file.lastModified())

        val fileInfo = """
        Name: $fileName
        Path: $filePath
        Size: ${fileSize / 1024} KB
        Last Modified: $lastModified
    """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("File Information")
            .setMessage(fileInfo)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun selectAllFiles() {
        val adapter = recyclerView.adapter as FileAdapter
        for (i in 0 until adapter.itemCount) {
            adapter.toggleSelection(i)
        }
        updateActionModeTitle()
    }

    fun startZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String> ) {
        progressDialog.show()
        val intent = Intent(this, ArchiveZipService::class.java).apply {
            putExtra(ArchiveZipService.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ArchiveZipService.EXTRA_PASSWORD, password)
            putExtra(ArchiveZipService.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ArchiveZipService.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ArchiveZipService.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ArchiveZipService.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ArchiveZipService.EXTRA_AES_STRENGTH, aesStrength)
            putExtra(ArchiveZipService.EXTRA_FILES_TO_ARCHIVE, ArrayList(filesToArchive))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun startSplitZipService(archiveName: String, password: String?, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, isEncrypted: Boolean, encryptionMethod: EncryptionMethod?, aesStrength: AesKeyStrength?, filesToArchive: List<String>, splitSize: Long?) {
        val intent = Intent(this, ArchiveSplitZipService::class.java).apply {
            putExtra(ArchiveSplitZipService.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(ArchiveSplitZipService.EXTRA_PASSWORD, password)
            putExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_METHOD, compressionMethod)
            putExtra(ArchiveSplitZipService.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(ArchiveSplitZipService.EXTRA_IS_ENCRYPTED, isEncrypted)
            putExtra(ArchiveSplitZipService.EXTRA_ENCRYPTION_METHOD, encryptionMethod)
            putExtra(ArchiveSplitZipService.EXTRA_AES_STRENGTH, aesStrength)
            putExtra(ArchiveSplitZipService.EXTRA_FILES_TO_ARCHIVE, ArrayList(filesToArchive))
            putExtra(ArchiveSplitZipService.EXTRA_SPLIT_SIZE, splitSize)

        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun convertToBytes(size: Long?, unit: String): Long? {
        return size?.times(when (unit) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024
            "GB" -> 1024L * 1024 * 1024
            else -> 1024L
        })
    }

    fun startSevenZService(password: String?, archiveName: String, compressionLevel: Int, solid: Boolean, threadCount: Int, filesToArchive: List<String>) {
        progressDialog.show()
        val intent = Intent(this, Archive7zService::class.java).apply {
            putExtra(Archive7zService.EXTRA_ARCHIVE_NAME, archiveName)
            putExtra(Archive7zService.EXTRA_PASSWORD, password)
            putExtra(Archive7zService.EXTRA_COMPRESSION_LEVEL, compressionLevel)
            putExtra(Archive7zService.EXTRA_SOLID, solid)
            putExtra(Archive7zService.EXTRA_THREAD_COUNT, threadCount)
            putExtra(Archive7zService.EXTRA_FILES_TO_ARCHIVE, ArrayList(filesToArchive))
        }
        ContextCompat.startForegroundService(this, intent)
    }


    private fun getMimeType(uri: Uri): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun getFiles(): ArrayList<File> {
        val files = ArrayList<File>()
        val directory = File(currentPath ?: Environment.getExternalStorageDirectory().absolutePath)

        val fileList = directory.listFiles()

        if (fileList != null) {
            for (file in fileList) {
                files.add(file)
            }
        }

        when (sortBy) {
            SortBy.SORT_BY_NAME -> files.sortBy { it.name }
            SortBy.SORT_BY_SIZE -> files.sortBy { if (it.isFile) it.length() else 0 }
            SortBy.SORT_BY_TIME_OF_CREATION -> files.sortBy { getFileTimeOfCreation(it) }
            SortBy.SORT_BY_EXTENSION -> files.sortBy { if (it.isFile) it.extension else "" }
        }
        if (!sortAscending) {
            files.reverse()
        }

        return files
    }

    private fun getFileTimeOfCreation(file: File): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            attr.lastModifiedTime().toMillis()
        } else {
            file.lastModified()
        }
    }

    private fun updateAdapterWithFullList() {
        if (!isSearchActive) {
            CoroutineScope(Dispatchers.Main).launch {
                val fullFileList = getFiles()
                adapter.updateFilesAndFilter(fullFileList)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu?.findItem(R.id.menu_search)
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

        return true
    }

    private fun searchFiles(query: String?) {
        isSearchActive = !query.isNullOrEmpty()
        binding.circularProgressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val result = query?.let { searchAllFiles(File(Environment.getExternalStorageDirectory().absolutePath), it) } ?: emptyList()

            withContext(Dispatchers.Main) {
                adapter.updateFilesAndFilter(ArrayList(result))
                binding.circularProgressBar.visibility = View.GONE
            }
        }
    }

    private fun searchAllFiles(directory: File, query: String): List<File> {
        val result = mutableListOf<File>()
        val files = directory.listFiles() ?: return result

        for (file in files) {
            if (file.isDirectory) {
                result.addAll(searchAllFiles(file, query))
            } else if (file.name.contains(query, true)) {
                result.add(file)
            }
        }
        return result
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_by_name -> {
                sortBy = SortBy.SORT_BY_NAME
                initRecyclerView()
            }

            R.id.menu_sort_by_size -> {
                sortBy = SortBy.SORT_BY_SIZE
                initRecyclerView()
            }

            R.id.menu_sort_by_time_of_creation -> {
                sortBy = SortBy.SORT_BY_TIME_OF_CREATION
                initRecyclerView()
            }

            R.id.menu_sort_by_extension -> {
                sortBy = SortBy.SORT_BY_EXTENSION
                initRecyclerView()
            }

            R.id.menu_sort_ascending -> {
                sortAscending = true
                initRecyclerView()
            }

            R.id.menu_sort_descending -> {
                sortAscending = false
                initRecyclerView()
            }
        }

        return true
    }
}
