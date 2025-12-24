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

import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentTransaction
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.R

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preference, rootKey)

        val aboutPreference = findPreference<Preference>("key_about")
        if (aboutPreference != null) {
            aboutPreference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    val fragmentManager = parentFragmentManager
                    val newFragment = AboutFragment()

                    // Show the fragment fullscreen.
                    val transaction = fragmentManager.beginTransaction()
                    transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    transaction.add(android.R.id.content, newFragment)
                        .addToBackStack(null)
                        .commit()

                    true
                }
        }

        val privacyKey = findPreference<Preference>("key_privacy_policy")
        if (privacyKey != null) {
            privacyKey.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    openUrl("https://sites.google.com/view/privacy-policy-zipxtract/home")
                    true
                }
        }

//        val keyRootAccess = findPreference<SwitchPreferenceCompat>("root_enabled") as SwitchPreferenceCompat
//        keyRootAccess.setOnPreferenceChangeListener { _, newValue ->
//            if (newValue as Boolean) {
//                grantRootAccess(keyRootAccess)
//            }
//            true
//        }

        val extractPathPreference = findPreference<Preference>("key_extract_path")
        extractPathPreference?.let { pref ->
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val savedPath = sharedPreferences.getString("key_extract_path", null)
            if (savedPath != null) {
                pref.summary = savedPath
            } else {
                pref.setSummary(R.string.extract_path_summary)
            }

            pref.setOnPreferenceClickListener {
                val pathPicker = PathPickerFragment.newInstance()
                pathPicker.setPathPickerListener(object : PathPickerFragment.PathPickerListener {
                    override fun onPathSelected(path: String) {
                        sharedPreferences.edit { putString("key_extract_path", path) }
                        pref.summary = path
                    }
                })
                pathPicker.show(parentFragmentManager, "PathPicker")
                true
            }
        }

        val archivePathPreference = findPreference<Preference>("key_archive_path")
        archivePathPreference?.let { pref ->
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val savedPath = sharedPreferences.getString("key_archive_path", null)
            if (savedPath != null) {
                pref.summary = savedPath
            } else {
                pref.setSummary(R.string.archive_path_summary)
            }

            pref.setOnPreferenceClickListener {
                val pathPicker = PathPickerFragment.newInstance()
                pathPicker.setPathPickerListener(object : PathPickerFragment.PathPickerListener {
                    override fun onPathSelected(path: String) {
                        sharedPreferences.edit { putString("key_archive_path", path) }
                        pref.summary = path
                    }
                })
                pathPicker.show(parentFragmentManager, "PathPicker")
                true
            }
        }

        val compressionPref = findPreference<EditTextPreference>("zstd_compression_level")
        compressionPref?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val level = (newValue as String).toInt()
                if (level in 0..22) {
                    true
                } else {
                    Toast.makeText(context,
                        getString(R.string.please_enter_value_between_0_and_22), Toast.LENGTH_SHORT).show()
                    false
                }
            } catch (e: NumberFormatException) {
                Toast.makeText(context, getString(R.string.invalid_value), Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

//    private fun grantRootAccess(keyRootAccess: SwitchPreferenceCompat) {
//        var isRootGranted = false
//        try {
//            val process = Runtime.getRuntime().exec("su")
//            val scanner = Scanner(process.inputStream).useDelimiter("\\A")
//            if (scanner.hasNext()) isRootGranted = true
//            scanner.close()
//            process.waitFor()
//            process.inputStream.close()
//            process.destroy()
//            if (isRootGranted) {
//                Toast.makeText(requireContext(), "Root access granted", Toast.LENGTH_SHORT).show()
//            } else {
//                requireActivity().runOnUiThread {
//                    Toast.makeText(requireContext(), "Root access not granted", Toast.LENGTH_SHORT).show()
//                    keyRootAccess.isChecked = false
//                }
//            }
//        } catch (e: Exception) {
//            requireActivity().runOnUiThread {
//                Toast.makeText(requireContext(), "Error: SU authorization\nDevice is not rooted", Toast.LENGTH_SHORT).show()
//                keyRootAccess.isChecked = false
//            }
//        }
//    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {

    }
}