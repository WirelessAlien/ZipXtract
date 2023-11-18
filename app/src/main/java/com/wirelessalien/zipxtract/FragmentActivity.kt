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
