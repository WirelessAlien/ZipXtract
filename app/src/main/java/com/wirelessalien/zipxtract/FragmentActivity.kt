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

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.wirelessalien.zipxtract.databinding.ActivityFragmentBinding

class FragmentActivity : AppCompatActivity() {

  private  lateinit var bottomNav : BottomNavigationView
  private lateinit var binding: ActivityFragmentBinding

  override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      binding = ActivityFragmentBinding.inflate(layoutInflater)
      setContentView(binding.root)

      loadFragment(ExtractFragment())
      bottomNav = binding.bottomNavI
      bottomNav.setOnItemSelectedListener {
          when (it.itemId) {
              R.id.home -> {
                  loadFragment(ExtractFragment())
                  true
              }
              R.id.zipCreate -> {
                  loadFragment(CreateZipFragment())
                  true
              }
              else -> {
                  false
              }
          }
      }
  }
    private fun loadFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()

        transaction.setCustomAnimations(
            R.anim.slide_in_from_right,
            R.anim.slide_out_to_left,
            R.anim.slide_in_from_left,
            R.anim.slide_out_to_right
        )

        transaction.replace(R.id.container, fragment)
        transaction.commit()
    }

}
