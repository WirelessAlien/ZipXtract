package com.wirelessalien.zipxtract

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wirelessalien.zipxtract.databinding.FragmentCreateZipBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream


class CreateZipFragment : Fragment() {

    private lateinit var binding: FragmentCreateZipBinding
    private var outputDirectory: DocumentFile? = null
    private var pickedDirectory: DocumentFile? = null
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var prefs: SharedPreferences
    private val tempFiles = mutableListOf<File>()
    private var selectedFileUri: Uri? = null
    private val cachedFiles = mutableListOf<File>()


    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            if (result.data != null) {
                val clipData = result.data!!.clipData

                if (clipData != null) {
                    tempFiles.clear()

                    binding.circularProgressBar.visibility = View.VISIBLE

                    CoroutineScope(Dispatchers.IO).launch {
                        for (i in 0 until clipData.itemCount) {
                            val filesUri = clipData.getItemAt(i).uri

                            val cursor = requireActivity().contentResolver.query(filesUri, null, null, null, null)
                            if (cursor != null && cursor.moveToFirst()) {
                                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val displayName = cursor.getString(displayNameIndex)
                                val tempFile = File(requireContext().cacheDir, displayName)

                                // Copy the content from the selected file URI to a temporary file
                                requireActivity().contentResolver.openInputStream(filesUri)?.use { input ->
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

                        // Hide the progress bar on the main thread
                        withContext(Dispatchers.Main) {
                            binding.circularProgressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedFileUri = result.data?.data
            if (selectedFileUri != null) {
                showToast("File Picked Successfully")
                binding.createZipMBtn.isEnabled = true

                // Display the file name from the intent
                val fileName = getZipFileName(selectedFileUri!!)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true
            } else {
                showToast("No file selected")
            }
        }
    }

    private val directoryFilesPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            requireActivity().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            pickedDirectory = DocumentFile.fromTreeUri(requireContext(), uri)

            copyFilesToCache(uri)

            val directoryName = pickedDirectory?.name
            binding.fileNameTextView.text = directoryName
            binding.fileNameTextView.isSelected = true
        }
    }

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            requireActivity().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputDirectory = DocumentFile.fromTreeUri(requireContext(), uri)

            val fullPath = outputDirectory?.uri?.path
            val displayedPath = fullPath?.replace("/tree/primary", "")

            if (displayedPath != null) {

                val directoryText = getString(R.string.directory_path, displayedPath)
                binding.directoryTextView.text = directoryText
            }

            // Save the output directory URI in SharedPreferences
            val editor = sharedPreferences.edit()
            editor.putString("outputDirectoryUriZip", uri.toString())
            editor.apply()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?): View {
        binding = FragmentCreateZipBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = requireActivity().getSharedPreferences("prefs", AppCompatActivity.MODE_PRIVATE)

        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


        binding.progressBar.visibility = View.GONE

        binding.circularProgressBar.visibility = View.GONE

        binding.pickFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            pickFileLauncher.launch(intent)
        }

        binding.pickFilesButton.setOnClickListener {
           openFile()
        }

        binding.changeDirectoryButton.setOnClickListener {
            chooseOutputDirectory()
        }

        binding.pickFolderButton.setOnClickListener {
            changeDirectoryFilesPicker()
        }

        binding.zipSettingsBtn.setOnClickListener {
            showCompressionSettingsDialog()
        }

        binding.settingsInfo.setOnClickListener {
            //show alert dialog with info
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("Encrypting files with the AES encryption method is time-consuming. If you have a low end device, it is recommended to use the default method (ZIP_STANDARD).")
                .setPositiveButton("Ok") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()

        }

        binding.clearCacheBtnDP.setOnClickListener {

            val editor = sharedPreferences.edit()
            editor.putString("outputDirectoryUriZip", null)
            editor.apply()

            // clear output directory
            outputDirectory = null
            binding.directoryTextView.text = getString(R.string.no_directory_selected)
            binding.directoryTextView.isSelected = false
            showToast("Output directory cleared")
        }

        binding.clearCacheBtnPF.setOnClickListener {

            tempFiles.clear()
            cachedFiles.clear()
            selectedFileUri = null
            binding.fileNameTextView.text = getString(R.string.no_file_selected)
            binding.fileNameTextView.isSelected = false
            binding.createZipMBtn.isEnabled = false
            showToast("Selected File Cleared")

        }

        binding.createZipMBtn.setOnClickListener {

            when {
                selectedFileUri != null -> {
                    createZip()
                }
                tempFiles.isNotEmpty() -> {
                    createZipFile()
                }
                cachedFiles.isNotEmpty() -> {
                    createZipFile()
                }
                else -> {
                    showToast("No file selected")
                }
            }

        }

        if (requireActivity().intent?.action == Intent.ACTION_VIEW) {
            val uri = requireActivity().intent.data

            if (uri != null) {
                selectedFileUri = uri
                binding.createZipMBtn.isEnabled = true

                // Display the file name from the intent
                val fileName = getZipFileName(selectedFileUri)
                val selectedFileText = getString(R.string.selected_file_text, fileName)
                binding.fileNameTextView.text = selectedFileText
                binding.fileNameTextView.isSelected = true

            } else {
                showToast("No file selected")
            }
        }

        val savedDirectoryUri = sharedPreferences.getString("outputDirectoryUriZip", null)
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
    }

    private fun changeDirectoryFilesPicker() {

        directoryFilesPicker.launch(null)

    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        // Set the request code
        pickFilesLauncher.launch(intent)
    }

    private fun chooseOutputDirectory() {
        directoryPicker.launch(null)
    }

    private fun copyFilesToCache(directoryUri: Uri) {
        val contentResolver = requireContext().contentResolver

        // Create a DocumentFile from the directoryUri
        val directory = DocumentFile.fromTreeUri(requireContext(), directoryUri)

        // Get all files recursively
        val allFiles = mutableListOf<DocumentFile>()
        getAllFilesInDirectory(directory, allFiles)

        // Copy each file to the cache directory
        for (file in allFiles) {
            val inputStream = contentResolver.openInputStream(file.uri)
            val cachedFile = File(requireContext().cacheDir, file.name!!)
            val outputStream = FileOutputStream(cachedFile)

            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            cachedFiles.add(cachedFile)

        }
    }

    private fun getAllFilesInDirectory(directory: DocumentFile?, fileList: MutableList<DocumentFile>) {
        if (directory != null && directory.isDirectory) {
            val files = directory.listFiles()
            for (file in files) {
                if (file.isFile) {
                    fileList.add(file)
                } else if (file.isDirectory) {
                    // Recursively get files in child directories
                    getAllFilesInDirectory(file, fileList)
                }
            }
        }
    }

    private fun createZipFile() {

        val selectedCompressionMethod = getSavedCompressionMethod()
        val selectedCompressionLevel = getSavedCompressionLevel()
        val selectedEncryptionMethod = getSavedEncryptionMethod()

        val alertDialogBuilder = MaterialAlertDialogBuilder(requireContext())
        alertDialogBuilder.setTitle("Enter Password")

        // Set up the input for password
        val passwordInput = EditText(context)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordInput.hint = "Password"

        // Set up the input for output file name
        val outputFileNameInput = EditText(context)
        outputFileNameInput.hint = "Zip Name (without .zip)"

        // Set up the layout for both inputs
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(passwordInput)
        layout.addView(outputFileNameInput)
        alertDialogBuilder.setView(layout)

        // Set up the buttons
        alertDialogBuilder.setPositiveButton("Encrypt") { _, _ ->
            val password = passwordInput.text.toString()
            var outputFileName = outputFileNameInput.text.toString()

            if (outputFileName.isEmpty()) {
                // Use default name if empty
                outputFileName = "outputZip.zip"
            } else if (!outputFileName.endsWith(".zip", ignoreCase = true)) {
                // Automatically add .zip extension
                outputFileName += ".zip"
            }

            if (password.isNotEmpty()) {
                // User entered a password, proceed to create the password-protected zip
                CoroutineScope(Dispatchers.Main).launch {
                    showProgressBar(true)
                    createEncryptedZipMFiles(password, outputFileName, selectedCompressionMethod, selectedCompressionLevel, selectedEncryptionMethod)
                    showProgressBar(false)
                }
            } else {
                // Password is empty, proceed to create a non-encrypted zip
                CoroutineScope(Dispatchers.Main).launch {
                    showProgressBar(true)
                    createNonEncryptedZipMFiles(outputFileName, selectedCompressionMethod, selectedCompressionLevel)
                    showProgressBar(false)
                }
            }
        }

        alertDialogBuilder.setNegativeButton("Non Encrypted") { _, _ ->
            var outputFileName = outputFileNameInput.text.toString()

            if (outputFileName.isEmpty()) {
                // Use default name if empty
                outputFileName = "outputZip.zip"
            } else if (!outputFileName.endsWith(".zip", ignoreCase = true)) {
                // Automatically add .zip extension
                outputFileName += ".zip"
            }

            // User clicked cancel, proceed to create a non-encrypted zip
            CoroutineScope(Dispatchers.Main).launch {
                showProgressBar(true)
                createNonEncryptedZipMFiles(outputFileName, selectedCompressionMethod, selectedCompressionLevel)
                showProgressBar(false)
            }
        }

        alertDialogBuilder.show()
    }

    private suspend fun createNonEncryptedZipMFiles(outputFileName: String, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel) {
        withContext(Dispatchers.IO) {

            val zipParameters = ZipParameters()
            zipParameters.compressionMethod = compressionMethod
            zipParameters.compressionLevel = compressionLevel
            zipParameters.isEncryptFiles = false

            val tempZipFile = File.createTempFile("tempZip", ".zip")
            val zipFile = ZipFile(tempZipFile)

            try {
                when {
                    cachedFiles.isNotEmpty() -> {
                        for (cachedFile in cachedFiles) {
                            // Add the file to the zip
                            zipFile.addFile(cachedFile, zipParameters)
                        }
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            // Add the file to the zip
                            zipFile.addFile(tempFile, zipParameters)
                        }
                    }
                    else -> {
                        showToast("Please select files")
                    }
                }

                showToast("Zip file created successfully")
                showExtractionCompletedSnackbar(outputDirectory)

            } catch (e: Exception) {
                showToast("Error creating zip file: ${e.message}")

            } finally {

                when {
                    cachedFiles.isNotEmpty() -> {
                        for (cachedFile in cachedFiles) {
                            cachedFile.delete()
                        }
                        cachedFiles.clear()
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            tempFile.delete()
                        }
                        tempFiles.clear()
                    }
                }
            }

            if (outputDirectory != null && tempZipFile.exists()) {
                val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                    outputDirectory!!.uri,
                    DocumentsContract.getTreeDocumentId(outputDirectory!!.uri))

                val outputZipUri = DocumentsContract.createDocument(
                    requireActivity().contentResolver, outputUri, "application/zip", outputFileName)

                requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w").use { outputStream ->
                    FileInputStream(tempZipFile).use { tempInputStream ->
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (tempInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream!!.write(buffer, 0, bytesRead)
                        }
                    }
                }

                showExtractionCompletedSnackbar(outputDirectory)
                showToast("Zip file created successfully")

            } else {
                showToast("Please select output directory")
            }

            if (tempZipFile.exists())
                tempZipFile.delete()
        }
    }

    private suspend fun createEncryptedZipMFiles(password: String, outputFileName: String, compressionMethod: CompressionMethod, compressionLevel: CompressionLevel, encryptionMethod: EncryptionMethod) {
        withContext(Dispatchers.IO) {

            val zipParameters = ZipParameters()
            zipParameters.compressionMethod = compressionMethod
            zipParameters.compressionLevel = compressionLevel
            zipParameters.isEncryptFiles = true
            zipParameters.encryptionMethod = encryptionMethod

            val tempZipFile = File.createTempFile("tempZip", ".zip")
            val zipFile = ZipFile(tempZipFile)

            try {
                // Set the password for the entire zip file
                zipFile.setPassword(password.toCharArray())

                when {
                    cachedFiles.isNotEmpty() -> {
                        for (cachedFile in cachedFiles) {
                            // Add the file to the zip
                            zipFile.addFile(cachedFile, zipParameters)
                        }
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            // Add the file to the zip
                            zipFile.addFile(tempFile, zipParameters)
                        }
                    }
                    else -> {
                        showToast("Please select files")
                    }
                }

                showExtractionCompletedSnackbar(outputDirectory)

            } catch (e: ZipException) {

                showToast("Error creating zip file ${e.message}")

            } finally {

                when {
                    cachedFiles.isNotEmpty() -> {
                        for (cachedFile in cachedFiles) {
                            cachedFile.delete()
                        }
                        cachedFiles.clear()
                    }
                    tempFiles.isNotEmpty() -> {
                        for (tempFile in tempFiles) {
                            tempFile.delete()
                        }
                        tempFiles.clear()
                    }
                }
            }

            if (outputDirectory != null && tempZipFile.exists()) {
                val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                    outputDirectory!!.uri,
                    DocumentsContract.getTreeDocumentId(outputDirectory!!.uri))

                val outputZipUri = DocumentsContract.createDocument(
                    requireActivity().contentResolver, outputUri, "application/zip", outputFileName)

                requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w").use { outputStream ->
                    FileInputStream(tempZipFile).use { tempInputStream ->
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (tempInputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream!!.write(buffer, 0, bytesRead)
                        }
                    }
                }

            } else {
                showToast("Please select output directory")
            }

            if (tempZipFile.exists())
                tempZipFile.delete()
        }
    }

    private suspend fun showProgressBar(show: Boolean) {
        withContext(Dispatchers.Main) {
            binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun createZip() {
        if (selectedFileUri != null) {
            val selectedCompressionMethod = getSavedCompressionMethod()
            val selectedCompressionLevel = getSavedCompressionLevel()
            val selectedEncryptionMethod = getSavedEncryptionMethod()
            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle("Enter Password")
            val input = EditText(requireContext())
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            builder.setView(input)

            builder.setPositiveButton("Encrypt") { _, _ ->
                val password = input.text.toString()
                if (password.isNotEmpty()) {
                    // Show a progress dialog or other UI indication here if desired
                    lifecycleScope.launch(Dispatchers.IO) {
                        // Perform the work in a background coroutine
                        requireActivity().contentResolver.openInputStream(selectedFileUri!!)?.use { inputStream ->
                            //progressbar visible
                            showProgressBar(true)
                            val zipFileName = getZipFileName(selectedFileUri)
                            if (zipFileName != null) {
                                val parameters = ZipParameters()
                                parameters.isEncryptFiles = true
                                parameters.encryptionMethod = selectedEncryptionMethod
                                parameters.compressionMethod = selectedCompressionMethod
                                parameters.compressionLevel = selectedCompressionLevel
                                parameters.fileNameInZip = getZipFileName(selectedFileUri)

                                val tempZipFile = File.createTempFile("tempZipP", ".zip")

                                // Create a password-protected ZIP file using zip4j
                                val zipFile = ZipFile(tempZipFile)
                                zipFile.setPassword(password.toCharArray())
                                zipFile.addStream(inputStream, parameters)

                                if (outputDirectory != null) {
                                    val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                                        outputDirectory!!.uri,
                                        DocumentsContract.getTreeDocumentId(outputDirectory!!.uri)
                                    )
                                    val outputZipUri = DocumentsContract.createDocument(
                                        requireActivity().contentResolver, outputUri, "application/zip", zipFileName
                                    )

                                    requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w")
                                        .use { outputStream ->
                                            FileInputStream(tempZipFile).use { tempInputStream ->
                                                val buffer = ByteArray(1024)
                                                var bytesRead: Int
                                                while (tempInputStream.read(buffer)
                                                        .also { bytesRead = it } != -1
                                                ) {
                                                    outputStream!!.write(buffer, 0, bytesRead)
                                                }
                                            }
                                        }

                                    // Notify the MediaScanner about the new file
                                    MediaScannerConnection.scanFile(requireContext(), arrayOf(outputUri.path), null, null)
                                }

                                // Delete the temporary ZIP file
                                tempZipFile.delete()
                                showProgressBar(false)
                            }
                        }
                        showToast("ZIP file created successfully.")
                        showExtractionCompletedSnackbar(outputDirectory)
                        selectedFileUri = null

                    }
                } else {
                    showToast("Failed to create ZIP file.")
                    binding.progressBar.visibility = View.GONE

                }
            }

            builder.setNegativeButton("Non-Encrypted") { _, _ ->

                if (selectedFileUri != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        requireActivity().contentResolver.openInputStream(selectedFileUri!!)?.use { inputStream ->

                            showProgressBar(true)
                            val zipFileName = getZipFileName(selectedFileUri)
                            if (zipFileName != null) {
                                val parameters = ZipParameters()
                                parameters.isEncryptFiles = false
                                parameters.compressionMethod = selectedCompressionMethod
                                parameters.compressionLevel = selectedCompressionLevel
                                parameters.fileNameInZip = getZipFileName(selectedFileUri)

                                val tempZipFile = File.createTempFile("tempZipnP", ".zip")

                                // Create a password-protected ZIP file using zip4j
                                val zipFile = ZipFile(tempZipFile)
                                zipFile.addStream(inputStream, parameters)

                                if (outputDirectory != null) {
                                    val outputUri = DocumentsContract.buildDocumentUriUsingTree(
                                        outputDirectory!!.uri,
                                        DocumentsContract.getTreeDocumentId(outputDirectory!!.uri)
                                    )
                                    val outputZipUri = DocumentsContract.createDocument(
                                        requireActivity().contentResolver, outputUri, "application/zip", zipFileName
                                    )

                                    requireActivity().contentResolver.openOutputStream(outputZipUri!!, "w")
                                        .use { outputStream ->
                                            FileInputStream(tempZipFile).use { tempInputStream ->
                                                val buffer = ByteArray(1024)
                                                var bytesRead: Int
                                                while (tempInputStream.read(buffer)
                                                        .also { bytesRead = it } != -1
                                                ) {
                                                    outputStream!!.write(buffer, 0, bytesRead)
                                                }
                                            }
                                        }

                                    // Notify the MediaScanner about the new file
                                    MediaScannerConnection.scanFile(requireContext(), arrayOf(outputUri.path), null, null)
                                }

                                // Delete the temporary ZIP file
                                tempZipFile.delete()
                                showProgressBar(false)
                            }
                        }
                        showToast("ZIP file created successfully.")
                        showExtractionCompletedSnackbar(outputDirectory)
                        selectedFileUri = null
                    }
                }
                else {
                    showToast("Failed to create ZIP file.")
                    binding.progressBar.visibility = View.GONE
                }
            }

            builder.show()
        } else {
            showToast("No file selected")
        }
    }

    private suspend fun showExtractionCompletedSnackbar(outputDirectory: DocumentFile?) {
        withContext(Dispatchers.Main) {

            binding.progressBar.visibility = View.GONE

            // Show a snackbar with a button to open the ZIP file
            val snackbar = Snackbar.make(binding.root, "ZIP file created successfully.", Snackbar.LENGTH_LONG)

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
    }

    private fun getZipFileName(selectedFileUri: Uri?): String? {
        if (selectedFileUri != null) {
            val cursor = requireActivity().contentResolver.query(selectedFileUri, null, null, null, null)
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

    private fun showCompressionSettingsDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.zip_settings_dialog, null)
        builder.setView(view)

        val title = view.findViewById<TextView>(R.id.dialog_title)
        title.text = "Compression Settings"

        val compressionMethodInput = view.findViewById<Spinner>(R.id.compression_method_input)
        val compressionLevelInput = view.findViewById<Spinner>(R.id.compression_level_input)
        val encryptionMethodInput = view.findViewById<Spinner>(R.id.encryption_method_input)

        val compressionMethods = CompressionMethod.values().map { it.name }
        val compressionMethodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, compressionMethods)
        compressionMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        compressionMethodInput.adapter = compressionMethodAdapter

        val savedCompressionMethod = getSavedCompressionMethod()
        val defaultCompressionMethodIndex = compressionMethods.indexOf(savedCompressionMethod.name)
        compressionMethodInput.setSelection(if (defaultCompressionMethodIndex != -1) defaultCompressionMethodIndex else 0)

        val compressionLevels = CompressionLevel.values().map { it.name }
        val compressionLevelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, compressionLevels)
        compressionLevelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        compressionLevelInput.adapter = compressionLevelAdapter

        val savedCompressionLevel = getSavedCompressionLevel()
        val defaultCompressionLevelIndex = compressionLevels.indexOf(savedCompressionLevel.name)
        compressionLevelInput.setSelection(if (defaultCompressionLevelIndex != -1) defaultCompressionLevelIndex else 2)

        val encryptionMethods = EncryptionMethod.values().map { it.name }
        val encryptionMethodAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, encryptionMethods)
        encryptionMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        encryptionMethodInput.adapter = encryptionMethodAdapter

        val savedEncryptionMethod = getSavedEncryptionMethod()
        val defaultEncryptionMethodIndex = encryptionMethods.indexOf(savedEncryptionMethod.name)
        encryptionMethodInput.setSelection(if (defaultEncryptionMethodIndex != -1) defaultEncryptionMethodIndex else 0)

        builder.setPositiveButton("Save") { _, _ ->
            val selectedCompressionMethod =
                CompressionMethod.valueOf(compressionMethods[compressionMethodInput.selectedItemPosition])
            val selectedCompressionLevel =
                CompressionLevel.valueOf(compressionLevels[compressionLevelInput.selectedItemPosition])
            val selectedEncryptionMethod =
                EncryptionMethod.valueOf(encryptionMethods[encryptionMethodInput.selectedItemPosition])
            saveCompressionMethod(selectedCompressionMethod)
            saveCompressionLevel(selectedCompressionLevel)
            saveEncryptionMethod(selectedEncryptionMethod)
        }

        builder.show()
    }

    private fun saveCompressionMethod(compressionMethod: CompressionMethod) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPRESSION_METHOD, compressionMethod.name).apply()
    }

    private fun saveCompressionLevel(compressionLevel: CompressionLevel) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPRESSION_LEVEL, compressionLevel.name).apply()
    }

    private fun saveEncryptionMethod(encryptionMethod: EncryptionMethod) {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ENCRYPTION_METHOD, encryptionMethod.name).apply()
    }

    private fun getSavedCompressionMethod(): CompressionMethod {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_COMPRESSION_METHOD, "DEFLATE") ?: "DEFLATE"
        return try {
            CompressionMethod.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            CompressionMethod.DEFLATE // Default value if the saved string is not a valid enum constant
        }
    }

    private fun getSavedCompressionLevel(): CompressionLevel {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_COMPRESSION_LEVEL, "NORMAL") ?: "NORMAL"
        return try {
            CompressionLevel.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            CompressionLevel.NORMAL // Default value if the saved string is not a valid enum constant
        }
    }

    private fun getSavedEncryptionMethod(): EncryptionMethod {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedValue = prefs.getString(KEY_ENCRYPTION_METHOD, "ZIP_STANDARD") ?: "ZIP_STANDARD"
        return try {
            EncryptionMethod.valueOf(savedValue)
        } catch (e: IllegalArgumentException) {
            EncryptionMethod.ZIP_STANDARD // Default value if the saved string is not a valid enum constant
        }
    }

    private fun showToast(message: String) {
        // Show toast on the main thread
       requireActivity().runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREFS_NAME = "ZipPrefs"
        private const val KEY_COMPRESSION_METHOD = "compressionMethod"
        private const val KEY_COMPRESSION_LEVEL = "compressionLevel"
        private const val KEY_ENCRYPTION_METHOD = "encryptionMethod"
    }
}

