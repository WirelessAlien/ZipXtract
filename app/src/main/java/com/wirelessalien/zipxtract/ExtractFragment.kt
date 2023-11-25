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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wirelessalien.zipxtract.databinding.FragmentExtractBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.*
import java.util.jar.JarInputStream

class ExtractFragment : Fragment() {

    private lateinit var binding: FragmentExtractBinding
    private var archiveFileUri: Uri? = null
    private var outputDirectory: DocumentFile? = null
    private lateinit var sharedPreferences: SharedPreferences

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            archiveFileUri = result.data?.data
            if (archiveFileUri != null) {
                showToast("File Picked Successfully")
                binding.extractButton.isEnabled = true

                // Display the file name from the intent
                val fileName = getArchiveFileName(archiveFileUri)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true
            } else {
                showToast("No file selected")
            }
        }
    }

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            requireActivity().contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputDirectory = DocumentFile.fromTreeUri(requireContext(), uri)
            val fullPath = outputDirectory?.uri?.path
            val displayedPath = fullPath?.replace("/tree/primary", "")

            if (displayedPath != null) {

                val directoryText = getString(R.string.directory_path, displayedPath)
                binding.directoryTextView.text = directoryText
            }

            // Save the output directory URI in SharedPreferences
            val editor = sharedPreferences.edit()
            editor.putString("outputDirectoryUri", uri.toString())
            editor.apply()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?): View {
        binding = FragmentExtractBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = requireActivity().getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        binding.progressBar.visibility = View.GONE


        binding.pickFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            pickFileLauncher.launch(intent)
        }

        binding.extractButton.setOnClickListener {
            if (archiveFileUri != null) {
                binding.progressBar.visibility = View.VISIBLE
                extractArchiveFile(archiveFileUri!!)
            } else {
                Toast.makeText(requireContext(), "Please pick a file to extract", Toast.LENGTH_SHORT).show()
            }
        }

        binding.clearCacheBtnDP.setOnClickListener {

            //clear selected directory uri
            val editor = sharedPreferences.edit()
            editor.remove("outputDirectoryUri")
            editor.apply()
            //clear selected directory
            outputDirectory = null
            binding.directoryTextView.text = getString(R.string.no_directory_selected)
            binding.directoryTextView.isSelected = false
            showToast("Output directory cleared")
        }

        binding.clearCacheBtnPF.setOnClickListener {

            //clear selected file uri
            archiveFileUri = null
            binding.fileNameTextView.text = getString(R.string.no_file_selected)
            binding.fileNameTextView.isSelected = false
            binding.extractButton.isEnabled = false
            showToast("Selected File Cleared")
        }

        binding.infoButton.setOnClickListener {

            val infoDialog = AboutFragment()
            infoDialog.show(requireActivity().supportFragmentManager, "infoDialog")
        }

        if (requireActivity().intent?.action == Intent.ACTION_VIEW) {
            val uri = requireActivity().intent.data

            if (uri != null) {
                archiveFileUri = uri
                binding.extractButton.isEnabled = true

                // Display the file name from the intent
                val fileName = getArchiveFileName(archiveFileUri)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true

            } else {
                showToast("No file selected")
            }
        }

        val savedDirectoryUri = sharedPreferences.getString("outputDirectoryUri", null)
        if (savedDirectoryUri != null) {
            outputDirectory = DocumentFile.fromTreeUri(requireContext(), Uri.parse(savedDirectoryUri))
            val fullPath = outputDirectory?.uri?.path

            val displayedPath = fullPath?.replace("/tree/primary:", "")

            if (displayedPath != null) {
                val directoryText = getString(R.string.directory_path, displayedPath)
                binding.directoryTextView.text = directoryText
            }
        } else {
            //do nothing
        }

        binding.changeDirectoryButton.setOnClickListener {
            chooseOutputDirectory()
        }

        requestPermissions()
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
            // Permissions granted, nothing to do
        } else {
            //show permission dialog under android R
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                showPermissionDeniedDialog()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        requestPermissionLauncher.launch(permissions)
    }

    private fun showPermissionDeniedDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("Permission Denied")
        builder.setMessage("Storage permission is required to extract files")
        builder.setPositiveButton("Open Settings") { _, _ ->
            openAppSettings()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", requireContext().packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun chooseOutputDirectory() {
        directoryPicker.launch(null)
    }

    private fun toggleExtractButtonEnabled(isEnabled: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.extractButton.isEnabled = isEnabled
        }
    }

    private fun extractArchiveFile(archiveFileUri: Uri) {
        if (outputDirectory == null) {
            showToast("Please select where to extract the files")
            return
        }

        val archiveFileName = getArchiveFileName(archiveFileUri)

        archiveFileName?.let { fileName ->
            val outputDirectory = outputDirectory?.createDirectory(fileName.substringBeforeLast("."))

            val inputStream = requireActivity().contentResolver.openInputStream(archiveFileUri)
            val bufferedInputStream = BufferedInputStream(inputStream)

            when (fileName.substringAfterLast(".").lowercase()) {
                "zip" -> extractPasswordProtectedZipOrRegularZip(bufferedInputStream, outputDirectory)
                "tar" -> extractTar(bufferedInputStream, outputDirectory)
                "bz2" -> extractBzip2(bufferedInputStream, outputDirectory)
                "gz" -> extractGzip(bufferedInputStream, outputDirectory)
                "7z" -> extract7z(bufferedInputStream, outputDirectory)
                "xz" -> extractXz(bufferedInputStream, outputDirectory)
                "jar" -> extractJar(bufferedInputStream, outputDirectory)
                "z" -> extractZ(bufferedInputStream, outputDirectory)
                else -> showToast("Unsupported archive format")
            }
        } ?: showToast("Failed to get the archive file name")
    }

    private fun extractPasswordProtectedZipOrRegularZip(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        val archiveFilePath = archiveFileUri?.path

        if (archiveFilePath != null) {

            toggleExtractButtonEnabled(false)

            lifecycleScope.launch {
                val tempFile = createTempFileFromInputStreamAsync(bufferedInputStream)

                if (isZipFileEncrypted(tempFile)) {

                    // Ask for password
                    val passwordEditText = EditText(requireContext())
                    passwordEditText.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD

                    val passwordDialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Enter Password")
                        .setView(passwordEditText)
                        .setPositiveButton("Extract") { _, _ ->
                            val password = passwordEditText.text.toString()

                            zip4jExtractZipFile(tempFile, password, outputDirectory)
                        }
                        .setNegativeButton("Cancel", null)
                        .create()

                    passwordDialog.show()
                } else {

                    zip4jExtractZipFile(tempFile, null, outputDirectory)
                }
            }
        } else {
            showToast("Invalid archive file URI")
        }
    }

    private suspend fun createTempFileFromInputStreamAsync(inputStream: InputStream): File = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("temp_", ".zip", requireActivity().cacheDir)
        FileOutputStream(tempFile).use { outputStream ->
            val buffer = ByteArray(4096)
            var count: Int
            while (inputStream.read(buffer).also { count = it } != -1) {
                outputStream.write(buffer, 0, count)
            }
        }
        return@withContext tempFile
    }

    private fun isZipFileEncrypted(tempFile: File): Boolean {
        val zipFile = ZipFile(tempFile)
        return zipFile.isEncrypted
    }

    private fun zip4jExtractZipFile(tempFile: File, password: String?, outputDirectory: DocumentFile?) {
        val zipFile = ZipFile(tempFile)

        zipFile.isRunInThread = true

        if (password != null && password.isNotEmpty()) {
            zipFile.setPassword(password.toCharArray())
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileHeaders = zipFile.fileHeaders
                val totalEntries = fileHeaders.size
                var extractedEntries = 0

                for (header in fileHeaders) {
                    val outputFile = outputDirectory?.createFile("application/octet-stream", header.fileName)

                    if (header.isDirectory) {
                        outputFile?.createDirectory("UnZip")
                    } else {
                        val bufferedOutputStream = BufferedOutputStream(outputFile?.uri?.let { requireActivity().contentResolver.openOutputStream(it) })

                        zipFile.getInputStream(header).use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            val fileSize = header.uncompressedSize

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                bufferedOutputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                // Calculate and update the progress
                                val progress = (totalBytesRead * 100 / fileSize).toInt()
                                updateProgress(progress)

                            }

                            bufferedOutputStream.close()
                        }
                    }

                    extractedEntries++

                    val progress = (extractedEntries * 100 / totalEntries)
                    updateProgress(progress)
                }
                // Show the extraction completed snackbar
                lifecycleScope.launch(Dispatchers.Main) {
                    showExtractionCompletedSnackbar(outputDirectory)
                }

            } catch (e: ZipException) {
                showToast("Extraction failed: ${e.message}")
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.progressTextView.visibility = View.GONE
                }
            } finally {

                toggleExtractButtonEnabled(true)
            }
        }
    }

    private fun updateProgress(progress: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.progressBar.progress = progress
            binding.progressTextView.text = "$progress%"
        }
    }

    private fun extractTar(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        val tarInputStream = TarArchiveInputStream(bufferedInputStream)

        var entry = tarInputStream.nextTarEntry
        while (entry != null) {
            val outputFile = outputDirectory?.createFile("application/octet-stream", entry.name)
            if (entry.isDirectory) {
                outputFile!!.createDirectory("UnTar")
            } else {
                outputFile?.uri?.let { uri ->
                    requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(1024)
                        var count: Int
                        try {
                            while (tarInputStream.read(buffer).also { count = it } != -1) {
                                outputStream.write(buffer, 0, count)
                            }
                        } catch (e: Exception) {
                            showToast("Extraction failed: ${e.message}")
                            tarInputStream.close()
                            toggleExtractButtonEnabled(true)
                            return
                        }
                    }
                }
            }
            entry = tarInputStream.nextTarEntry
        }

        tarInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)

        toggleExtractButtonEnabled(true)
    }

    private fun extractBzip2(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        val bzip2InputStream = BZip2CompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (bzip2InputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    bzip2InputStream.close()
                    toggleExtractButtonEnabled(true)
                    return
                }
            }
        }

        bzip2InputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)

        toggleExtractButtonEnabled(true)
    }

    private fun extractGzip(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        val gzipInputStream = GzipCompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (gzipInputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    gzipInputStream.close()
                    toggleExtractButtonEnabled(true)
                    return
                }
            }
        }

        gzipInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)

        toggleExtractButtonEnabled(true)
    }

    private suspend fun createTemp7zFileInBackground(bufferedInputStream: BufferedInputStream): File = withContext(Dispatchers.IO) {
        return@withContext File.createTempFile("temp_", ".7z", requireActivity().cacheDir).apply {
            FileOutputStream(this).use { outputStream ->
                val buffer = ByteArray(4096)
                var count: Int
                while (bufferedInputStream.read(buffer).also { count = it } != -1) {
                    outputStream.write(buffer, 0, count)
                }
            }
        }
    }

    private fun extract7z(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        showPasswordInputDialog { password ->
            val tempFileJob = CoroutineScope(Dispatchers.Default).launch {
                val tempFile = createTemp7zFileInBackground(bufferedInputStream)

                try {
                    val sevenZFile: SevenZFile = if (password != null) {
                        val passwordCharArray = password.toCharArray()
                        SevenZFile(tempFile, passwordCharArray)
                    } else {
                        SevenZFile(tempFile)
                    }

                    val totalBytes = sevenZFile.entries.sumOf { it.size.toInt() }
                    var bytesRead = 0

                    var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
                    while (entry != null) {
                        val outputFile =
                            outputDirectory?.createFile("application/octet-stream", entry.name)
                        if (entry.isDirectory) {
                            outputFile?.createDirectory("Un7z")
                        } else {
                            outputFile?.uri?.let { uri ->
                                requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                                    val buffer = ByteArray(4096)
                                    try {
                                        var count: Int
                                        while (sevenZFile.read(buffer).also { count = it } != -1) {
                                            outputStream.write(buffer, 0, count)
                                            bytesRead += count

                                            val progress = (bytesRead.toFloat() / totalBytes) * 100
                                            updateProgressBar(progress)
                                        }
                                    } catch (e: Exception) {
                                        sevenZFile.close()
                                    }
                                }
                            }
                        }
                        entry = sevenZFile.nextEntry
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempFile.delete()
                }

                // Show a completion message after extraction is done
                withContext(Dispatchers.Main) {
                    toggleExtractButtonEnabled(true)
                    showExtractionCompletedSnackbar(outputDirectory)
                }
            }

            tempFileJob.invokeOnCompletion { throwable ->
                if (throwable != null) {

                    showToast("Extraction failed: ${throwable.message}")
                }
            }
        }
    }

    private fun updateProgressBar(progress: Float) {

        requireActivity().runOnUiThread {
            binding.progressBar.progress = progress.toInt()
            binding.progressTextView.text = "${progress.toInt()}%"

        }
    }

    private fun showPasswordInputDialog(callback: (String?) -> Unit) {
        val passwordInputView = EditText(requireContext())
        passwordInputView.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Enter Password")
            .setView(passwordInputView)
            .setPositiveButton("Extract") { _, _ ->
                val password = passwordInputView.text.toString()
                callback(password.takeIf { it.isNotEmpty() })
            }.setNegativeButton("No Password") { _, _ ->
                callback(null)
            }.show()
    }

    private fun extractXz(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        val xzInputStream = XZCompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (xzInputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    xzInputStream.close()

                    toggleExtractButtonEnabled(true)
                    return
                }
            }
        }

        xzInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)

        toggleExtractButtonEnabled(true)
    }

    private fun extractJar(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        val jarInputStream = JarInputStream(bufferedInputStream)
        var entry = jarInputStream.nextEntry

        while (entry != null) {
            val outputFile = outputDirectory?.createFile("application/octet-stream", entry.name)
            if (entry.isDirectory) {
                outputFile?.createDirectory("Unjar")
            } else {
                outputFile?.uri?.let { uri ->
                    requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(1024)
                        var count: Int
                        try {
                            while (jarInputStream.read(buffer).also { count = it } != -1) {
                                outputStream.write(buffer, 0, count)
                            }
                        } catch (e: Exception) {
                            showToast("Extraction failed: ${e.message}")
                            jarInputStream.close()

                            toggleExtractButtonEnabled(true)
                            return
                        }
                    }
                }
            }
            entry = jarInputStream.nextEntry
        }

        jarInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)

        toggleExtractButtonEnabled(true)
    }

    private fun extractZ(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        val zInputStream = ZCompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (zInputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    zInputStream.close()

                    toggleExtractButtonEnabled(true)
                    return
                }
            }
        }

        zInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)

        toggleExtractButtonEnabled(true)
    }

    private fun showExtractionCompletedSnackbar(outputDirectory: DocumentFile?) {
        binding.progressBar.visibility = View.GONE

        val snackbar = Snackbar.make(binding.root, "File extracted successfully.", Snackbar.LENGTH_LONG)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            snackbar.setAction("Open Folder") {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(outputDirectory?.uri, DocumentsContract.Document.MIME_TYPE_DIR)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(intent)
            }
        }

        snackbar.show()
    }

    private fun getArchiveFileName(archiveFileUri: Uri?): String? {
        if (archiveFileUri != null) {
            val cursor = requireActivity().contentResolver.query(archiveFileUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        return it.getString(displayNameIndex)
                    }
                }
            }
        }
        return null
    }

    private fun showToast(message: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
