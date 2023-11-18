package com.wirelessalien.zipxtract

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirelessalien.zipxtract.databinding.FragmentAboutBinding


class AboutFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = FragmentAboutBinding.inflate(layoutInflater)
        val dialogView = binding.root

        binding.versionNumberText.text = BuildConfig.VERSION_NAME

        binding.githubIcon.setOnClickListener {
            openUrl("https://github.com/WirelessAlien/ZipXtract")
        }

        binding.githubIssueButton.setOnClickListener {
            openUrl("https://github.com/WirelessAlien/ZipXtract/issues")
        }

        binding.licenseText.setOnClickListener {
            openUrl("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }

        binding.donate.setOnClickListener {
            val donateFragment = DonationFragment()
            donateFragment.show(requireActivity().supportFragmentManager, "donationFragment")
        }



        binding.shareIcon.setOnClickListener {
            val githubUrl = "https://github.com/WirelessAlien/ZipXtract"
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_TEXT, githubUrl)
            startActivity(Intent.createChooser(shareIntent, "Share App Link"))
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}