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

package com.wirelessalien.zipxtract.activity


import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.search.SearchView
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.QuickSearchResultAdapter
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.ActivityMainBinding
import com.wirelessalien.zipxtract.databinding.DialogCrashLogBinding
import com.wirelessalien.zipxtract.fragment.ArchiveFragment
import com.wirelessalien.zipxtract.fragment.MainFragment
import com.wirelessalien.zipxtract.helper.SearchHistoryManager
import com.wirelessalien.zipxtract.helper.Searchable
import com.wirelessalien.zipxtract.model.FileItem
import com.wirelessalien.zipxtract.helper.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var searchHistoryManager: SearchHistoryManager
    private lateinit var quickSearchResultAdapter: QuickSearchResultAdapter
    private var isSearchSubmitted = false
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        searchHistoryManager = SearchHistoryManager(this)
        setupSearchView()

        val menuHost: MenuHost = this
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // Menu inflated by fragments
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return if (menuItem.itemId == R.id.menu_search) {
                    binding.searchView.show()
                    true
                } else {
                    false
                }
            }
        }, this, Lifecycle.State.RESUMED)

        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.searchView.isShowing) {
                    binding.searchView.hide()
                } else {
                    val fragment = supportFragmentManager.findFragmentById(R.id.container)
                    if (fragment is Searchable && !fragment.getCurrentSearchQuery().isNullOrEmpty()) {
                        fragment.onSearch("")
                        isEnabled = false
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)

        binding.searchView.addTransitionListener { _, _, newState ->
            if (newState === SearchView.TransitionState.SHOWING) {
                callback.isEnabled = true
            } else if (newState === SearchView.TransitionState.HIDING) {
                if (isSearchSubmitted) {
                    callback.isEnabled = true
                } else {
                    callback.isEnabled = false
                    val fragment = supportFragmentManager.findFragmentById(R.id.container)
                    if (fragment is Searchable) {
                        fragment.onSearch("")
                    }
                }
            }
        }

        val fileName = "Crash_Log.txt"
        val crashLogFile = File(cacheDir, fileName)
        if (crashLogFile.exists()) {
            val crashLog = StringBuilder()
            try {
                val reader = BufferedReader(FileReader(crashLogFile))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    crashLog.append(line)
                    crashLog.append('\n')
                }
                reader.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            val dialogBinding = DialogCrashLogBinding.inflate(layoutInflater)
            dialogBinding.crashLogText.text = crashLog.toString()

            MaterialAlertDialogBuilder(this, R.style.MaterialDialog)
                .setTitle(getString(R.string.crash_log))
                .setView(dialogBinding.root)
                .setPositiveButton(getString(R.string.copy_text)) { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("ZipXtract Crash Log", crashLog.toString())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.close), null)
                .show()
            crashLogFile.delete()
        }

        setSupportActionBar(binding.toolbar)

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.home))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.archive))

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> loadFragment(MainFragment())
                    1 -> loadFragment(ArchiveFragment())
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        if (savedInstanceState == null) {
            loadFragment(MainFragment())
        }

        handleIntent()
    }

    private fun setupSearchView() {
        binding.searchView.setupWithSearchBar(null)

        binding.searchHistoryRecyclerView.layoutManager = LinearLayoutManager(this)
        quickSearchResultAdapter = QuickSearchResultAdapter(
            mutableListOf(),
            onItemClick = { item ->
                when (item) {
                    is QuickSearchResultAdapter.SearchResultItem.HistoryItem -> {
                        binding.searchView.setText(item.query)
                        performSearch(item.query)
                    }
                    is QuickSearchResultAdapter.SearchResultItem.FileResultItem -> {
                        navigateToFile(item.fileItem)
                    }
                }
            },
            onDeleteHistoryClick = { query ->
                searchHistoryManager.removeHistory(query)
                refreshHistory()
            }
        )
        binding.searchHistoryRecyclerView.adapter = quickSearchResultAdapter

        refreshHistory()

        binding.searchView
            .editText
            .setOnEditorActionListener { v, _, _ ->
                val query = v.text.toString()
                performSearch(query)
                true
            }

        binding.searchView.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                if (query.isNotEmpty()) {
                    performFastSearch(query)
                } else {
                    searchJob?.cancel()
                    refreshHistory()
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.searchView.addTransitionListener { _, _, newState ->
            if (newState == SearchView.TransitionState.SHOWN) {
                val fragment = supportFragmentManager.findFragmentById(R.id.container)
                if (fragment is Searchable) {
                    val currentQuery = fragment.getCurrentSearchQuery()
                    if (!currentQuery.isNullOrEmpty()) {
                        binding.searchView.setText(currentQuery)
                    } else {
                        refreshHistory()
                    }
                } else {
                    refreshHistory()
                }
            } else if (newState == SearchView.TransitionState.HIDDEN) {
                binding.searchView.setText("")
            }
        }
    }

    private fun refreshHistory() {
        val historyItems = searchHistoryManager.getHistory().map {
            QuickSearchResultAdapter.SearchResultItem.HistoryItem(it)
        }
        quickSearchResultAdapter.updateList(historyItems)
    }

    private fun performFastSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<FileItem>()
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

            val queryUri = MediaStore.Files.getContentUri("external")

            try {
                contentResolver.query(
                    queryUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                    while (cursor.moveToNext() && isActive) {
                        val filePath = cursor.getString(dataColumn)

                        if (filePath == null || StorageHelper.isAndroidDataDir(filePath, this@MainActivity)) {
                            continue
                        }

                        val file = File(filePath)
                        if (file.exists() && !file.name.startsWith(".")) {
                            results.add(FileItem.fromFile(file))
                            // To keep it responsive, limit to 50.
                            if (results.size >= 50) break
                        }
                    }
                }
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                val resultItems = results.map { QuickSearchResultAdapter.SearchResultItem.FileResultItem(it) }
                quickSearchResultAdapter.updateList(resultItems)
            }
        }
    }

    private fun navigateToFile(fileItem: FileItem) {
        binding.searchView.hide()
        val parentPath = fileItem.file.parent ?: return

        val fragment = supportFragmentManager.findFragmentById(R.id.container)
        if (fragment is MainFragment) {
            fragment.navigateToPathAndHighlight(parentPath, fileItem.file.absolutePath)
        } else {
            val mainFragment = MainFragment().apply {
                arguments = Bundle().apply {
                    putString(MainFragment.ARG_DIRECTORY_PATH, parentPath)
                    putString(MainFragment.ARG_HIGHLIGHT_FILE_PATH, fileItem.file.absolutePath)
                }
            }
            loadFragment(mainFragment)
            binding.tabLayout.getTabAt(0)?.select()
        }
    }

    private fun performSearch(query: String) {
        if (query.isNotBlank()) {
            searchHistoryManager.addHistory(query)
            isSearchSubmitted = true
            binding.searchView.hide()

            val fragment = supportFragmentManager.findFragmentById(R.id.container)
            if (fragment is Searchable) {
                fragment.onSearch(query)
            }
            isSearchSubmitted = false
        }
    }

    private fun handleIntent() {
        when (intent.action) {
            ACTION_CREATE_ARCHIVE -> {
                val jobId = intent.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
                val archiveType = intent.getStringExtra(EXTRA_ARCHIVE_TYPE)
                if (jobId != null && archiveType != null) {
                    val mainFragment = MainFragment().apply {
                        arguments = Bundle().apply {
                            putString(MainFragment.ARG_JOB_ID, jobId)
                            putString(MainFragment.ARG_ARCHIVE_TYPE, archiveType)
                        }
                    }
                    loadFragment(mainFragment)
                    binding.tabLayout.getTabAt(0)?.select()
                }
            }
            ACTION_OPEN_DIRECTORY -> {
                val directoryPath = intent.getStringExtra(EXTRA_DIRECTORY_PATH)
                if (directoryPath != null) {
                    val mainFragment = MainFragment().apply {
                        arguments = Bundle().apply {
                            putString(MainFragment.ARG_DIRECTORY_PATH, directoryPath)
                        }
                    }
                    loadFragment(mainFragment)
                }
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.container, fragment)
        }
    }

    companion object {
        const val ACTION_CREATE_ARCHIVE = "com.wirelessalien.zipxtract.ACTION_CREATE_ARCHIVE"
        const val EXTRA_ARCHIVE_TYPE = "com.wirelessalien.zipxtract.EXTRA_ARCHIVE_TYPE"
        const val ACTION_OPEN_DIRECTORY = "com.wirelessalien.zipxtract.ACTION_OPEN_DIRECTORY"
        const val EXTRA_DIRECTORY_PATH = "com.wirelessalien.zipxtract.EXTRA_DIRECTORY_PATH"
    }
}