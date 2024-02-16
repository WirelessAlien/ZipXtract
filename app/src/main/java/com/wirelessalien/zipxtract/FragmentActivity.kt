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

package com.wirelessalien.zipxtract

import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.wirelessalien.zipxtract.databinding.ActivityFragmentBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

class FragmentActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var binding: ActivityFragmentBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the UncaughtExceptionHandler
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val crashLog = StringWriter()
            throwable.printStackTrace(PrintWriter(crashLog))

            try {
                val outputDirectoryUri = getOutputDirectoryUriFromPreferences()
                val fileName = "ZipXtract_Crash_Log.txt"

                val fileOutputStream = if (outputDirectoryUri != null) {
                    // Use a ContentResolver to open the OutputStream
                    val crashLogFileUri = DocumentFile.fromTreeUri(this, outputDirectoryUri)?.createFile("text/plain", fileName)?.uri
                    contentResolver.openOutputStream(crashLogFileUri!!)
                } else {
                    // Use a FileOutputStream
                    val targetFile = File(filesDir, fileName)
                    FileOutputStream(targetFile)
                }

                fileOutputStream?.use {
                    it.write(crashLog.toString().toByteArray())
                }

            } catch (e: IOException) {
                e.printStackTrace()
            }

            android.os.Process.killProcess(android.os.Process.myPid())
        }

        binding = ActivityFragmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        bottomNav = binding.bottomNavI
        bottomNav.setupWithNavController(navController)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.home -> {
                    navController.navigate(R.id.ExtractFragment)
                    true
                }
                R.id.zipCreate -> {
                    navController.navigate(R.id.CreateZipFragment)
                    true
                }

                else -> false
            }
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.ExtractFragment) {
                    navController.navigate(R.id.ExtractFragment)
                } else {
                    finish()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun getOutputDirectoryUriFromPreferences(): Uri? {
        val sharedPreferences = getSharedPreferences("MyPrefs", MODE_PRIVATE)
        val outputDirectoryUriString = sharedPreferences.getString("outputDirectoryUri", null)
        return if (outputDirectoryUriString != null) {
            Uri.parse(outputDirectoryUriString)
        } else {
            null
        }
    }
}
