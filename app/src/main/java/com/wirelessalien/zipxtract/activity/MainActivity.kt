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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.databinding.ActivityMainBinding
import com.wirelessalien.zipxtract.databinding.DialogCrashLogBinding
import com.wirelessalien.zipxtract.fragment.ArchiveFragment
import com.wirelessalien.zipxtract.fragment.MainFragment
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

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

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.home -> {
                    loadFragment(MainFragment())
                    true
                }
                R.id.archive -> {
                    loadFragment(ArchiveFragment())
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.home
        }

        handleIntent()
    }

    private fun handleIntent() {
        if (intent.action == ACTION_CREATE_ARCHIVE) {
            val jobId = intent.getStringExtra(ServiceConstants.EXTRA_JOB_ID)
            val archiveType = intent.getStringExtra(EXTRA_ARCHIVE_TYPE)
            if (jobId != null && archiveType != null) {
                // Navigate to MainFragment and pass data for archive creation
                val mainFragment = MainFragment().apply {
                    arguments = Bundle().apply {
                        putString(MainFragment.ARG_JOB_ID, jobId)
                        putString(MainFragment.ARG_ARCHIVE_TYPE, archiveType)
                    }
                }
                loadFragment(mainFragment)
                binding.bottomNav.selectedItemId = R.id.home
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
    }
}