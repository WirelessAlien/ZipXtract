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

package com.wirelessalien.zipxtract.helper

import android.content.Context
import android.text.Editable
import android.view.View
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.DialogFileInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ChecksumUtils {

    fun calculateChecksums(
        file: File,
        binding: DialogFileInfoBinding,
        lifecycleScope: LifecycleCoroutineScope,
        context: Context
    ) {
        lifecycleScope.launch {
            calculateChecksums(file, binding.md5Progress, binding.sha1Progress, binding.sha256Progress)
                .flowOn(Dispatchers.IO)
                .collect { result ->
                    when (result) {
                        is ChecksumResult.Progress -> {
                            binding.md5Progress.progress = result.progress
                            binding.sha1Progress.progress = result.progress
                            binding.sha256Progress.progress = result.progress
                        }
                        is ChecksumResult.Success -> {
                            binding.md5Checksum.text = Editable.Factory.getInstance().newEditable(result.md5)
                            binding.sha1Checksum.text = Editable.Factory.getInstance().newEditable(result.sha1)
                            binding.sha256Checksum.text = Editable.Factory.getInstance().newEditable(result.sha256)
                        }
                    }
                }
        }

        binding.compareChecksum.doOnTextChanged { text, _, _, _ ->
            val checksumToCompare = text.toString().trim()
            val md5 = binding.md5Checksum.text.toString()
            val sha1 = binding.sha1Checksum.text.toString()
            val sha256 = binding.sha256Checksum.text.toString()

            if (checksumToCompare.isNotEmpty()) {
                val isMatch = checksumToCompare.equals(md5, ignoreCase = true) ||
                        checksumToCompare.equals(sha1, ignoreCase = true) ||
                        checksumToCompare.equals(sha256, ignoreCase = true)

                if (isMatch) {
                    binding.compareResult.visibility = View.VISIBLE
                    binding.compareChecksumLayout.error = null
                    binding.compareResult.text =
                        when {
                            checksumToCompare.equals(md5, ignoreCase = true) -> context.getString(R.string.md5)
                            checksumToCompare.equals(sha1, ignoreCase = true) -> context.getString(R.string.sha1)
                            else -> context.getString(R.string.sha256)
                        }
                } else {
                    binding.compareResult.visibility = View.GONE
                    binding.compareChecksumLayout.error = context.getString(R.string.no_match)
                }
            } else {
                binding.compareResult.visibility = View.GONE
                binding.compareChecksumLayout.error = null
            }
        }
    }

    private fun calculateChecksums(
        file: File,
        md5Progress: LinearProgressIndicator,
        sha1Progress: LinearProgressIndicator,
        sha256Progress: LinearProgressIndicator
    ): Flow<ChecksumResult> = flow {
        val md5Digest = MessageDigest.getInstance("MD5")
        val sha1Digest = MessageDigest.getInstance("SHA-1")
        val sha256Digest = MessageDigest.getInstance("SHA-256")

        val fileInputStream = FileInputStream(file)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead: Int
        val fileSize = file.length()
        var totalBytesRead = 0L

        withContext(Dispatchers.Main) {
            md5Progress.visibility = View.VISIBLE
            sha1Progress.visibility = View.VISIBLE
            sha256Progress.visibility = View.VISIBLE
            md5Progress.isIndeterminate = false
            sha1Progress.isIndeterminate = false
            sha256Progress.isIndeterminate = false
        }

        while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
            md5Digest.update(buffer, 0, bytesRead)
            sha1Digest.update(buffer, 0, bytesRead)
            sha256Digest.update(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            val progress = ((totalBytesRead * 100) / fileSize).toInt()
            emit(ChecksumResult.Progress(progress))
        }

        fileInputStream.close()

        val md5 = md5Digest.digest().toHexString()
        val sha1 = sha1Digest.digest().toHexString()
        val sha256 = sha256Digest.digest().toHexString()

        withContext(Dispatchers.Main) {
            md5Progress.visibility = View.INVISIBLE
            sha1Progress.visibility = View.INVISIBLE
            sha256Progress.visibility = View.INVISIBLE
        }

        emit(ChecksumResult.Success(md5, sha1, sha256))
    }
        .flowOn(Dispatchers.IO)

    private fun ByteArray.toHexString(): String {
        val hexString = StringBuilder()
        for (byte in this) {
            hexString.append(String.format("%02x", byte))
        }
        return hexString.toString()
    }

    sealed class ChecksumResult {
        data class Progress(val progress: Int) : ChecksumResult()
        data class Success(val md5: String, val sha1: String, val sha256: String) : ChecksumResult()
    }
}
