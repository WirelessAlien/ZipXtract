package com.wirelessalien.zipxtract.helper

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.BottomSheetConfirmDeleteBinding
import java.io.File
import java.util.Locale

object DeletionSafetyHelper {

    fun showDeleteConfirmationSheet(
        context: Context,
        files: List<File>,
        onConfirmed: () -> Unit
    ) {
        val binding = BottomSheetConfirmDeleteBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        dialog.setContentView(binding.root)

        val count = files.size
        val totalBytes = files.sumOf { if (it.isFile) it.length() else 0L }
        val sizeString = bytesToString(totalBytes)
        val countLabel = if (count == 1) context.getString(R.string.item) else context.getString(R.string.items)

        binding.textDeleteTitle.text = context.getString(R.string.delete_confirmation_title, count, countLabel)
        binding.textDeleteSummary.text = if (count == 1 && files.isNotEmpty()) {
            "${files[0].name} \u2022 $sizeString"
        } else {
            context.getString(R.string.selection_status_format, count, countLabel, sizeString)
        }

        binding.btnCancelDelete.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            onConfirmed()
        }

        dialog.show()
    }

    fun showArchiveDeleteConfirmationSheet(
        context: Context,
        count: Int,
        totalBytes: Long,
        onConfirmed: () -> Unit
    ) {
        val binding = BottomSheetConfirmDeleteBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        dialog.setContentView(binding.root)

        val sizeString = bytesToString(totalBytes)
        val countLabel = if (count == 1) context.getString(R.string.item) else context.getString(R.string.items)

        binding.textDeleteTitle.text = context.getString(R.string.delete_confirmation_title, count, countLabel)
        binding.textDeleteSummary.text = context.getString(R.string.selection_status_format, count, countLabel, sizeString)

        binding.btnCancelDelete.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            onConfirmed()
        }

        dialog.show()
    }

    private fun bytesToString(bytes: Long): String {
        val kilobyte = 1000
        val megabyte = kilobyte * 1000
        val gigabyte = megabyte * 1000

        return when {
            bytes < kilobyte -> "$bytes B"
            bytes < megabyte -> String.format(Locale.US, "%.2f KB", bytes.toFloat() / kilobyte)
            bytes < gigabyte -> String.format(Locale.US, "%.2f MB", bytes.toFloat() / megabyte)
            else -> String.format(Locale.US, "%.2f GB", bytes.toFloat() / gigabyte)
        }
    }
}
