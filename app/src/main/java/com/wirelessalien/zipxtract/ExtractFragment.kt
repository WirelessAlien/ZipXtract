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
import android.system.ErrnoException
import android.system.OsConstants
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
import androidx.transition.TransitionInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wirelessalien.zipxtract.databinding.FragmentExtractBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.jar.JarInputStream


class ExtractFragment : Fragment() {

    private lateinit var binding: FragmentExtractBinding
    private var archiveFileUri: Uri? = null
    private var outputDirectory: DocumentFile? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var archiveFormat: ArchiveFormat? = null
    private var password: CharArray? = null
    private val tempFiles = mutableListOf<File>()

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            archiveFileUri = result.data?.data
            if (archiveFileUri != null) {
                showToast(getString(R.string.file_picked_success))
                binding.extractButton.isEnabled = true

                // Display the file name from the intent
                val fileName = getArchiveFileName(archiveFileUri)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true
            } else {
                showToast(getString(R.string.file_picked_fail))
            }
        }
    }

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (result.data != null) {
                val clipData = result.data!!.clipData

                if (clipData != null) {
                    tempFiles.clear()

                    binding.circularProgressBar.visibility = View.VISIBLE

                    lifecycleScope.launch(Dispatchers.IO) {
                        for (i in 0 until clipData.itemCount) {
                            val filesUri = clipData.getItemAt(i).uri

                            val cursor = requireContext().contentResolver.query(filesUri, null, null, null, null)
                            if (cursor != null && cursor.moveToFirst()) {
                                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val displayName = cursor.getString(displayNameIndex)
                                val tempFile = File(requireContext().cacheDir, displayName)

                                // Copy the content from the selected file URI to a temporary file
                                requireContext().contentResolver.openInputStream(filesUri)?.use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                tempFiles.add(tempFile)

                                // show picked files name
                                val selectedFilesText = getString(R.string.selected_files_text, tempFiles.size)
                                withContext(Dispatchers.Main) {
                                    binding.fileNameTextView.text = selectedFilesText
                                    binding.fileNameTextView.isSelected = true
                                }

                                cursor.close()
                            }
                        }

                        withContext(Dispatchers.Main) {
                            binding.circularProgressBar.visibility = View.GONE
                            binding.extractButton.isEnabled = true
                        }
                    }
                }
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
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentExtractBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val inflater = TransitionInflater.from(requireContext())
        exitTransition = inflater.inflateTransition(R.transition.slide_left)

        sharedPreferences = requireActivity().getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        binding.progressBar.visibility = View.GONE
        binding.circularProgressBar.visibility = View.GONE


        binding.pickFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            pickFileLauncher.launch(intent)

            val cacheDir = requireContext().cacheDir
            if (cacheDir.isDirectory) {
                val children: Array<String> = cacheDir.list()!!
                for (i in children.indices) {
                    File(cacheDir, children[i]).deleteRecursively()
                }
            }
        }

        binding.pickMFilesButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

            pickFilesLauncher.launch(intent)

            val cacheDir = requireContext().cacheDir
            if (cacheDir.isDirectory) {
                val children: Array<String> = cacheDir.list()!!
                for (i in children.indices) {
                    File(cacheDir, children[i]).deleteRecursively()
                }
            }
        }

        binding.extractButton.setOnClickListener {
            when {
                archiveFileUri != null -> {
                    binding.progressBar.visibility = View.VISIBLE
                    extractArchiveFile(archiveFileUri!!)
                }
                tempFiles.isNotEmpty() -> {
                    val file = tempFiles.first()
                    val fileName = file.name
                    val zipExtension = ".zip."
                    val zipIndex = fileName.lastIndexOf(zipExtension)

                    if (zipIndex != -1 && zipIndex + zipExtension.length < fileName.length) {
                        val afterZipDot = fileName.substring(zipIndex + zipExtension.length)
                        if (afterZipDot.toIntOrNull() != null) {
                            extractSplitZipEncNEnc(outputDirectory)
                        } else {
                            extractMultiPartRar(archiveFormat)
                        }
                    } else {
                        extractMultiPartRar(archiveFormat)
                    }
                }
                else -> showToast(getString(R.string.pick_file_extract))
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
            showToast(getString(R.string.output_directory_cleared))
        }

        binding.clearCacheBtnPF.setOnClickListener {

            //clear selected file uri
            archiveFileUri = null
            binding.fileNameTextView.text = getString(R.string.no_file_selected)
            binding.fileNameTextView.isSelected = false
            binding.extractButton.isEnabled = false
            showToast(getString(R.string.selected_file_cleared))
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
                showToast(getString(R.string.file_picked_fail))
            }
        }

        val intent = requireActivity().intent
        if (intent.action == Intent.ACTION_SEND) {
            val fileUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            archiveFileUri = fileUri
            binding.extractButton.isEnabled = true

            // Display the file name from the intent
            val fileName = getArchiveFileName(archiveFileUri)
            val selectedFileText = getString(R.string.selected_file_text, fileName)
            binding.fileNameTextView.text = selectedFileText
            binding.fileNameTextView.isSelected = true

        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val fileUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            fileUris?.forEach { fileUri ->
                val file = File(requireActivity().cacheDir, getArchiveFileName(fileUri))
                file.outputStream().use { outputStream ->
                    requireActivity().contentResolver.openInputStream(fileUri)?.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                tempFiles.add(file)
            }

            val selectedFilesText = getString(R.string.selected_files_text, tempFiles.size)
            binding.fileNameTextView.text = selectedFilesText
            binding.fileNameTextView.isSelected = true
            binding.extractButton.isEnabled = true
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
        builder.setTitle(getString(R.string.permission_denied))
        builder.setMessage(getString(R.string.permission_required))
        builder.setPositiveButton(getString(R.string.open_settings)) { _, _ ->
            openAppSettings()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
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
            showToast(getString(R.string.select_output_directory_extract))
            return
        }

        val archiveFileName = getArchiveFileName(archiveFileUri)

        archiveFileName.let { fileName ->
            val outputDirectory = outputDirectory?.let {
                if (fileName.substringAfterLast(".").lowercase() != "rar") {
                    it.createDirectory(fileName.substringBeforeLast("."))
                } else {
                    it
                }
            }
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
                "rar" -> extractRar(archiveFormat, archiveFileUri)
                else -> showToast("Unsupported archive format")
            }
        }
    }

    private fun extractSplitZipEncNEnc(outputDirectory: DocumentFile?) {

        toggleExtractButtonEnabled(false)

        lifecycleScope.launch {
                val passwordEditText = EditText(requireContext())
                passwordEditText.inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD

                val passwordDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.enter_password))
                    .setView(passwordEditText)
                    .setPositiveButton(getString(R.string.extract)) { _, _ ->
                        val password = passwordEditText.text.toString()

                        extractSplitZipFile(password, outputDirectory)
                    }.setNegativeButton(getString(R.string.no_password)) { _, _ ->

                        extractSplitZipFile(null, outputDirectory)

                    }
                passwordDialog.show()
        }
    }


    private fun isSplitZipFileEncrypted(): Boolean {
        val sortedTempFiles = tempFiles.sortedBy { file ->
            val fileName = file.name.lowercase()
            when {
                fileName.lastIndexOf(".zip.") != -1 -> fileName.substringAfterLast(".zip.").toIntOrNull() ?: Int.MAX_VALUE
                fileName.lastIndexOf(".z") != -1 -> fileName.substringAfterLast(".z").toIntOrNull() ?: Int.MAX_VALUE
                else -> Int.MAX_VALUE
            }
        }
        for (file in sortedTempFiles) {
            val zipFile = ZipFile(file)
            if (zipFile.isEncrypted) {
                return true
            }
        }
        return false
    }

    private fun extractSplitZipFile(password: String?, outputDirectory: DocumentFile?) {
        toggleExtractButtonEnabled(false)

        val sortedTempFiles = tempFiles.sortedBy { file ->
            val fileName = file.name.lowercase()
            when {
                fileName.lastIndexOf(".zip.") != -1 -> fileName.substringAfterLast(".zip.")
                    .toIntOrNull() ?: Int.MAX_VALUE

                fileName.lastIndexOf(".z") != -1 -> fileName.substringAfterLast(".z")
                    .toIntOrNull() ?: Int.MAX_VALUE

                else -> Int.MAX_VALUE
            }
        }

        val zipFile = ZipFile(sortedTempFiles.first())
        zipFile.isRunInThread = true

        if (!password.isNullOrEmpty()) {
            zipFile.setPassword(password.toCharArray())
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileHeaders = zipFile.fileHeaders
                val totalEntries = fileHeaders.size
                var extractedEntries = 0

                val directoryName = sortedTempFiles.first().name.substringBefore(".")
                val directory = outputDirectory?.createDirectory(directoryName)

                for (header in fileHeaders) {
                    val relativePath = header.fileName
                    val pathParts = relativePath.split("/")
                    var currentDirectory = directory

                    for (part in pathParts.dropLast(1)) {
                        currentDirectory =
                            currentDirectory?.findFile(part) ?: currentDirectory?.createDirectory(
                                part
                            )
                    }

                    if (!header.isDirectory) {
                        val outputFile = currentDirectory?.createFile(
                            "application/octet-stream",
                            pathParts.last()
                        )
                        val bufferedOutputStream = BufferedOutputStream(outputFile?.uri?.let {
                            requireActivity().contentResolver.openOutputStream(it)
                        })

                        zipFile.getInputStream(header).use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                bufferedOutputStream.write(buffer, 0, bytesRead)
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
                showToast("${getString(R.string.extraction_failed)} ${e.message}")
            } finally {
                toggleExtractButtonEnabled(true)
            }
        }
    }


    private fun extractMultiPartRar(archiveFormat: ArchiveFormat?) {
        showPasswordInputDialogRar { password ->
            this.password = password?.toCharArray()

            val sortedTempFiles = tempFiles.sortedBy { file ->
                val fileName = file.name.lowercase()
                when {
                    fileName.lastIndexOf(".z") != -1 -> fileName.substringAfterLast(".z")
                        .toIntOrNull() ?: Int.MAX_VALUE
                    else -> Int.MAX_VALUE
                }
            }

            if (tempFiles.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.VISIBLE
                            toggleExtractButtonEnabled(false)
                        }

                        for (tempFile in sortedTempFiles) {
                            val archiveOpenVolumeCallback = ArchiveOpenMultipartCallback()
                            val inStream: IInStream? = archiveOpenVolumeCallback.getStream(tempFile.absolutePath)
                            if (inStream != null) {
                                val inArchive: IInArchive = SevenZip.openInArchive(
                                    archiveFormat,
                                    inStream,
                                    archiveOpenVolumeCallback
                                )

                                try {
                                    val itemCount = inArchive.numberOfItems
                                    for (i in 0 until itemCount) {
                                        val path = inArchive.getProperty(i, PropID.PATH) as String
                                        val cacheDir = requireActivity().cacheDir
                                        val newFileName =
                                            tempFile.name.substring(
                                                0,
                                                tempFile.name.lastIndexOf('.')
                                            )
                                        val cacheDstDir = File(cacheDir, newFileName)

                                        cacheDstDir.mkdir()

                                        inArchive.extract(
                                            intArrayOf(i),
                                            false,
                                            ExtractCallback(inArchive, cacheDstDir)
                                        )
                                    }
                                } catch (e: SevenZipException) {
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        showToast("${getString(R.string.extraction_failed)} ${e.message}")
                                    }
                                } finally {
                                    inArchive.close()
                                    archiveOpenVolumeCallback.close()
                                    saveCacheFile(tempFile)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.progressTextView.visibility = View.GONE
                            toggleExtractButtonEnabled(true)
                        }
                    } catch (e: IOException) {
                        if (isNoStorageSpaceException(e)) {
                            withContext(Dispatchers.Main) {
                                binding.progressBar.visibility = View.GONE
                                showToast("No storage available")
                            }
                        } else {
                            showToast("${getString(R.string.extraction_failed)} ${e.message}")
                        }
                    }
                }
            }
        }
    }


    private fun isNoStorageSpaceException(e: IOException): Boolean {
        return e.message?.contains("ENOSPC") == true || e.cause is ErrnoException && (e.cause as ErrnoException).errno == OsConstants.ENOSPC
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
                        .setTitle(getString(R.string.enter_password))
                        .setView(passwordEditText)
                        .setPositiveButton(getString(R.string.extract)) { _, _ ->
                            val password = passwordEditText.text.toString()

                            zip4jExtractZipFile(tempFile, password, outputDirectory)
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .create()

                    passwordDialog.show()
                } else {

                    zip4jExtractZipFile(tempFile, null, outputDirectory)
                }
            }
        } else {
            showToast(getString(R.string.invalid_uri))
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

        if (!password.isNullOrEmpty()) {
            zipFile.setPassword(password.toCharArray())
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileHeaders = zipFile.fileHeaders
                val totalEntries = fileHeaders.size
                var extractedEntries = 0

                for (header in fileHeaders) {
                    val relativePath = header.fileName
                    val pathParts = relativePath.split("/")
                    var currentDirectory = outputDirectory

                    for (part in pathParts.dropLast(1)) {
                        currentDirectory = currentDirectory?.findFile(part) ?: currentDirectory?.createDirectory(part)
                    }

                    if (!header.isDirectory) {

                        val outputFile = currentDirectory?.createFile("application/octet-stream", pathParts.last())
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
                    tempFile.delete()
                }

            } catch (e: ZipException) {
                showToast("${getString(R.string.extraction_failed)} ${e.message}")
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
        binding.progressBarIdt.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
          val tarInputStream = TarArchiveInputStream(bufferedInputStream)

          var entry = tarInputStream.nextTarEntry
          while (entry != null) {
              val pathParts = entry.name.split("/")

              var currentDirectory = outputDirectory
              for (part in pathParts.dropLast(1)) {
                  currentDirectory =
                      currentDirectory?.findFile(part) ?: currentDirectory?.createDirectory(part)
              }

              if (!entry.isDirectory) {
                  val outputFile =
                      currentDirectory?.createFile("application/octet-stream", pathParts.last())

                  outputFile?.uri?.let { uri ->
                      requireActivity().contentResolver.openOutputStream(uri)
                          ?.use { outputStream ->
                              val buffer = ByteArray(1024)
                              var count: Int
                              try {
                                  while (tarInputStream.read(buffer).also { count = it } != -1) {
                                      outputStream.write(buffer, 0, count)
                                  }
                              } catch (e: Exception) {
                                  withContext(Dispatchers.Main) {
                                      showToast("${getString(R.string.extraction_failed)} ${e.message}")
                                      tarInputStream.close()
                                      toggleExtractButtonEnabled(true)
                                      binding.progressBarIdt.visibility = View.GONE
                                  }
                                  return@launch
                              }
                          }
                  }
              }
              entry = tarInputStream.nextTarEntry
          }

          tarInputStream.close()
          withContext(Dispatchers.Main) {
              showExtractionCompletedSnackbar(outputDirectory)
              toggleExtractButtonEnabled(true)
              binding.progressBarIdt.visibility = View.GONE
          }
        }
    }

    private fun extractBzip2(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
       toggleExtractButtonEnabled(false)
        binding.progressBarIdt.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
           val bzip2InputStream = BZip2CompressorInputStream(bufferedInputStream)
           val outputFile = outputDirectory?.createFile("application/octet-stream", getArchiveFileNameSB(archiveFileUri))

           outputFile?.uri?.let { uri ->
               requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                   val buffer = ByteArray(1024)
                   var count: Int
                   try {
                       while (bzip2InputStream.read(buffer).also { count = it } != -1) {
                           outputStream.write(buffer, 0, count)
                       }
                   } catch (e: Exception) {
                       withContext(Dispatchers.Main) {
                           showToast("${getString(R.string.extraction_failed)} ${e.message}")
                           bzip2InputStream.close()
                           toggleExtractButtonEnabled(true)
                           binding.progressBarIdt.visibility = View.GONE
                       }
                       return@launch
                   }
               }
           }

           bzip2InputStream.close()
           withContext(Dispatchers.Main) {
               showExtractionCompletedSnackbar(outputDirectory)
               toggleExtractButtonEnabled(true)
               binding.progressBarIdt.visibility = View.GONE
           }
       }
    }

    private fun extractGzip(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        toggleExtractButtonEnabled(false)

        binding.progressBarIdt.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val gzipInputStream = GzipCompressorInputStream(bufferedInputStream)
            val outputFile = outputDirectory?.createFile("application/octet-stream", getArchiveFileNameSB(archiveFileUri))

            outputFile?.uri?.let { uri ->
                requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val buffer = ByteArray(1024)
                    var count: Int
                    try {
                        while (gzipInputStream.read(buffer).also { count = it } != -1) {
                            outputStream.write(buffer, 0, count)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast("${getString(R.string.extraction_failed)} ${e.message}")
                            gzipInputStream.close()
                            toggleExtractButtonEnabled(true)
                            binding.progressBarIdt.visibility = View.GONE
                        }
                        return@launch
                    }
                }
            }

            gzipInputStream.close()
            withContext(Dispatchers.Main) {
                showExtractionCompletedSnackbar(outputDirectory)
                toggleExtractButtonEnabled(true)
                binding.progressBarIdt.visibility = View.GONE
            }
        }
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
                        val relativePath = entry.name

                        // Split the relative path into individual parts
                        val pathParts = relativePath.split("/")

                        // Initialize the root directory
                        var currentDirectory = outputDirectory

                        // Iterate through the path parts to create directories
                        for (part in pathParts.dropLast(1)) {
                            currentDirectory = currentDirectory?.findFile(part) ?: currentDirectory?.createDirectory(part)
                        }

                        // Only create a file if the entry is not a directory
                        if (!entry.isDirectory) {
                            // Create the output file within the last directory
                            val outputFile = currentDirectory?.createFile("application/octet-stream", pathParts.last())

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
                    showToast("${getString(R.string.extraction_failed)} ${throwable.message}")
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
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.password_input_dialog, null)
        val passwordInputView = dialogView.findViewById<EditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.extract)) { _, _ ->
                val password = passwordInputView.text.toString()
                callback(password.takeIf { it.isNotEmpty() })
            }.setNegativeButton(getString(R.string.no_password)) { _, _ ->
                callback(null)
            }.show()
    }

    private fun extractXz(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        toggleExtractButtonEnabled(false)

        binding.progressBarIdt.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
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
                        withContext(Dispatchers.Main) {
                            showToast("${getString(R.string.extraction_failed)} ${e.message}")
                            xzInputStream.close()
                            toggleExtractButtonEnabled(true)
                            binding.progressBarIdt.visibility = View.GONE
                        }
                        return@launch
                    }
                }
            }

            xzInputStream.close()
            withContext(Dispatchers.Main) {
                showExtractionCompletedSnackbar(outputDirectory)
                toggleExtractButtonEnabled(true)
                binding.progressBarIdt.visibility = View.GONE
            }
        }
    }

    private fun extractJar(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        toggleExtractButtonEnabled(false)

        binding.progressBarIdt.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val jarInputStream = JarInputStream(bufferedInputStream)
            var entry = jarInputStream.nextEntry

            while (entry != null) {
                val pathParts = entry.name.split("/")
                var currentDirectory = outputDirectory

                for (part in pathParts.dropLast(1)) {
                    currentDirectory =
                        currentDirectory?.findFile(part) ?: currentDirectory?.createDirectory(part)
                }

                if (!entry.isDirectory) {
                    val outputFile =
                        currentDirectory?.createFile("application/octet-stream", pathParts.last())
                    outputFile?.uri?.let { uri ->
                        requireActivity().contentResolver.openOutputStream(uri)
                            ?.use { outputStream ->
                                val buffer = ByteArray(1024)
                                var count: Int
                                try {
                                    while (jarInputStream.read(buffer).also { count = it } != -1) {
                                        outputStream.write(buffer, 0, count)
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        showToast("${getString(R.string.extraction_failed)} ${e.message}")
                                        toggleExtractButtonEnabled(true)
                                        binding.progressBarIdt.visibility = View.GONE
                                    }
                                    return@launch
                                }
                            }
                    }
                }
                entry = jarInputStream.nextEntry
            }

            jarInputStream.close()
            withContext(Dispatchers.Main) {
                showExtractionCompletedSnackbar(outputDirectory)
                toggleExtractButtonEnabled(true)
                binding.progressBarIdt.visibility = View.GONE
            }
        }
    }

    private fun extractZ(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        toggleExtractButtonEnabled(false)
        binding.progressBarIdt.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val zInputStream = ZCompressorInputStream(bufferedInputStream)

            val outputFile = outputDirectory?.createFile("application/octet-stream", getArchiveFileNameSB(archiveFileUri))

            outputFile?.uri?.let { uri ->
                requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val buffer = ByteArray(1024)
                    var count: Int
                    try {
                        while (zInputStream.read(buffer).also { count = it } != -1) {
                            outputStream.write(buffer, 0, count)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast("${getString(R.string.extraction_failed)} ${e.message}")
                            toggleExtractButtonEnabled(true)
                            binding.progressBarIdt.visibility = View.GONE
                        }
                        return@launch
                    }
                }
            }

            zInputStream.close()
            withContext(Dispatchers.Main) {
                showExtractionCompletedSnackbar(outputDirectory)
                toggleExtractButtonEnabled(true)
                binding.progressBarIdt.visibility = View.GONE
            }
        }
    }

    private fun showPasswordInputDialogRar(onPasswordEntered: (String?) -> Unit) {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.password_input_dialog, null)
        val passwordInputView = dialogView.findViewById<EditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.MaterialDialog)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = passwordInputView.text.toString()
                onPasswordEntered.invoke(password.ifBlank { null })
            }
            .setNegativeButton(getString(R.string.no_password)) { _, _ ->
                onPasswordEntered.invoke(null)
            }
            .show()
    }

    //Thanks for the sample code
    //https://github.com/omicronapps/7-Zip-JBinding-4Android/issues/5#issuecomment-744128480
    private fun extractRar(archiveFormat: ArchiveFormat?, archiveFileUri: Uri) {
        showPasswordInputDialogRar { password ->
            this.password = password?.toCharArray()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.VISIBLE
                        toggleExtractButtonEnabled(false)
                    }

                    val inputStream = context?.contentResolver?.openInputStream(archiveFileUri)
                    val cacheDir = requireActivity().cacheDir

                    inputStream?.use { input ->
                        val originalFileName = getArchiveFileName(archiveFileUri)
                        val tempRarFile = File(cacheDir, originalFileName)

                        tempRarFile.outputStream().use { output ->
                            input.copyTo(output)
                        }

                        val inStream = RandomAccessFileInStream(RandomAccessFile(tempRarFile, "r"))
                        val cacheDirr = requireActivity().cacheDir
                        val newFileName = tempRarFile.name.substring(0, tempRarFile.name.lastIndexOf('.'))

                        try {
                            val inArchive = SevenZip.openInArchive(archiveFormat, inStream, OpenCallback())
                            val cacheDstDir = File(cacheDirr, newFileName)
                            cacheDstDir.mkdir()

                            inArchive.extract(
                                null,
                                false,
                                ExtractCallback(inArchive, cacheDstDir)
                            )
                        } catch (e: SevenZipException) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                binding.progressBar.visibility = View.GONE
                                showToast("${getString(R.string.extraction_failed)} ${e.message}")
                                binding.progressTextView.visibility = View.GONE
                                toggleExtractButtonEnabled(true)
                            }
                        } finally {
                            saveCacheFile(tempRarFile)
                        }
                    }
                } catch (e: IOException) {
                    if (isNoStorageSpaceException(e)) {
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            showToast("No storage available")
                        }
                    } else {
                        showToast("${getString(R.string.extraction_failed)} ${e.message}")
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.progressTextView.visibility = View.GONE
                        toggleExtractButtonEnabled(true)
                    }
                }
            }
        }
    }

    private fun saveCacheFile(tempRarFile: File) {
        val cacheDir = requireActivity().cacheDir
        val newFileName = tempRarFile.name.substring(0, tempRarFile.name.lastIndexOf('.'))
        val cacheDstDir = File(cacheDir, newFileName)

        val files = cacheDstDir.listFiles()
        if (files != null) {
            val destinationDir = outputDirectory?.createDirectory(newFileName)
            saveFilesToOutputDirectory(files, destinationDir)
        }
    }

    private fun saveFilesToOutputDirectory(files: Array<File>, destinationDir: DocumentFile?) {
        for (file in files) {
            val outputFile = if (file.isDirectory) {
                destinationDir?.createDirectory(file.name)
            } else {
                destinationDir?.createFile("application/octet-stream", file.name)
            }

            if (file.isDirectory) {
                val nestedFiles = file.listFiles()
                if (nestedFiles != null && nestedFiles.isNotEmpty()) {
                    saveFilesToOutputDirectory(nestedFiles, outputFile)
                }
            } else {
                outputFile?.uri?.let { uri ->
                    requireActivity().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(4096)
                        var count: Int
                        try {
                            FileInputStream(file).use { inputStream ->
                                while (inputStream.read(buffer).also { count = it } != -1) {
                                    outputStream.write(buffer, 0, count)
                                }
                            }
                        } catch (e: IOException) {
                            showToast("${getString(R.string.extraction_failed)} ${e.message}")
                            return
                        }
                    }
                }
            }
        }
    }


    private inner class OpenCallback : IArchiveOpenCallback, ICryptoGetTextPassword {
        override fun setCompleted(p0: Long?, p1: Long?) { }

        override fun setTotal(p0: Long?, p1: Long?) { }

        override fun cryptoGetTextPassword(): String {
            return String(password ?: CharArray(0))
        }
    }

    private inner class ExtractCallback(
        private val inArchive: IInArchive,
        private val dstDir: File
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private lateinit var uos: OutputStream

        override fun setOperationResult(p0: ExtractOperationResult?) {
            if (p0 == ExtractOperationResult.OK ) {
                try {
                    uos.close()
                    showToast(getString(R.string.extraction_success))
                    CoroutineScope(Dispatchers.Main).launch {
                        binding.progressBar.max = 100
                        binding.progressBar.progress = 100
                    }
                } catch (e: SevenZipException) {
                    showToast("${getString(R.string.extraction_failed)} ${e.message}")
                }
            }
        }

        override fun getStream(p0: Int, p1: ExtractAskMode?): ISequentialOutStream {
            val path: String = inArchive.getStringProperty(p0, PropID.PATH)
            val isDir: Boolean = inArchive.getProperty(p0, PropID.IS_FOLDER) as Boolean
            val unpackedFile = File(dstDir.path, path)

            if (isDir) {
                unpackedFile.mkdir()
            } else {
                try {
                    val dir = unpackedFile.parent?.let { File(it) }
                    if (dir != null) {
                        if (!dir.isDirectory) {
                            dir.mkdirs()
                        }
                    }
                    unpackedFile.createNewFile()
                    uos = FileOutputStream(unpackedFile)
                } catch (e: IOException) {
                   showToast("${getString(R.string.extraction_failed)} ${e.message}")
                }
            }

            return ISequentialOutStream { data: ByteArray ->
                try {
                    if (!isDir) {
                        uos.write(data)
                    }
                } catch (e: SevenZipException) {
                    showToast("${getString(R.string.extraction_failed)} ${e.message}")
                }
                data.size
            }
        }


        override fun prepareOperation(p0: ExtractAskMode?) { }

        override fun setCompleted(p0: Long) {
            CoroutineScope(Dispatchers.Main).launch {
                binding.progressBar.progress = p0.toInt()
                binding.progressTextView.text = formatFileSize(p0)
            }
        }

        override fun setTotal(p0: Long) {
            CoroutineScope(Dispatchers.Main).launch {
                binding.progressBar.max = p0.toInt()
                binding.progressTextView.text = formatFileSize(p0)
            }
        }

        override fun cryptoGetTextPassword(): String {
            return String(password ?: CharArray(0))
        }
    }

    fun formatFileSize(bytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            bytes < kilobyte -> "$bytes B"
            bytes < megabyte -> String.format("%.2f KB", bytes.toFloat() / kilobyte)
            bytes < gigabyte -> String.format("%.2f MB", bytes.toFloat() / megabyte)
            else -> String.format("%.2f GB", bytes.toFloat() / gigabyte)
        }
    }

    private fun showExtractionCompletedSnackbar(outputDirectory: DocumentFile?) {
        binding.progressBar.visibility = View.GONE

        val snackbar = Snackbar.make(binding.root, getString(R.string.extraction_success), Snackbar.LENGTH_LONG)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            snackbar.setAction(getString(R.string.open_folder)) {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(outputDirectory?.uri, DocumentsContract.Document.MIME_TYPE_DIR)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(intent)
            }
        }

        snackbar.show()
    }

    private fun getArchiveFileName(archiveFileUri: Uri?): String {
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
        return  "archive_file"
    }

    private fun getArchiveFileNameSB(archiveFileUri: Uri?): String {
        if (archiveFileUri != null) {
            val cursor = requireActivity().contentResolver.query(archiveFileUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        val fileName = it.getString(displayNameIndex)
                        return fileName.substringBeforeLast('.')
                    }
                }
            }
        }
        return  "archive_file"
    }

    private fun showToast(message: String) {
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
