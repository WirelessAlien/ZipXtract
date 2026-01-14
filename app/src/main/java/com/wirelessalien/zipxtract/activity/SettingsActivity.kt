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

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ActivitySettingsBinding
import com.wirelessalien.zipxtract.fragment.SettingsFragment


class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the toolbar
        binding.toolbar.title = getString(R.string.action_settings)
        setSupportActionBar(binding.toolbar)

        // Display the fragment as the main content.
        supportFragmentManager.beginTransaction()
            .replace(binding.fragmentContainer.id, SettingsFragment())
            .commit()

        // Add back button to the activity
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeButtonEnabled(true)
        }

        // Handle back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishActivity()
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishActivity()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun finishActivity() {
        finish()
    }
}