package com.wirelessalien.zipxtract

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var extractButton: Button
    private lateinit var directoryTextView: TextView
    private var archiveFileUri: Uri? = null
    private var outputDirectory: DocumentFile? = null
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var progressBar: ProgressBar

    private val requestPermissionCode = 1

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            archiveFileUri = result.data?.data
            if (archiveFileUri != null) {
                Toast.makeText(this, "File picked successfully", Toast.LENGTH_SHORT).show()
                extractButton.isEnabled = true
            }
        }
    }

    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                outputDirectory = DocumentFile.fromTreeUri(this, uri)
                directoryTextView.text = outputDirectory?.name

                // Save the output directory URI in SharedPreferences
                val editor = sharedPreferences.edit()
                editor.putString("outputDirectoryUri", uri.toString())
                editor.apply()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pickFileButton = findViewById<Button>(R.id.pickFileButton)
        extractButton = findViewById(R.id.extractButton)
        progressBar = findViewById(R.id.progressBar)
        directoryTextView = findViewById(R.id.directoryTextView) // Assign the TextView from the layout
        sharedPreferences = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        progressBar.visibility = View.GONE

        pickFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            pickFileLauncher.launch(intent)
        }

        extractButton.setOnClickListener {
            if (archiveFileUri != null) {
                progressBar.visibility = View.VISIBLE
                extractArchiveFile(archiveFileUri!!)
            } else {
                Toast.makeText(this, "Please pick a file to extract", Toast.LENGTH_SHORT).show()
            }
        }


        val savedDirectoryUri = sharedPreferences.getString("outputDirectoryUri", null)
        if (savedDirectoryUri != null) {
            outputDirectory = DocumentFile.fromTreeUri(this, Uri.parse(savedDirectoryUri))
            directoryTextView.text = outputDirectory?.name // Set the directory name in the TextView
        }


        val changeDirectoryButton = findViewById<Button>(R.id.changeDirectoryButton)
        changeDirectoryButton.setOnClickListener {
            chooseOutputDirectory()
        }

        requestPermissions()
    }


    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val requestCode = requestPermissionCode

        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            requestPermissionCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permissions granted, nothing to do
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        showPermissionDeniedDialog()
                    }
                }
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("You have denied the necessary permissions. Please grant them in the app settings to continue.")
            .setPositiveButton("OK") { _, _ ->
                // Open app settings
                openAppSettings()
            }
            .setNegativeButton("Exit") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        val packageName = packageName
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private fun chooseOutputDirectory() {
        directoryPicker.launch(null)
    }

    private fun extractArchiveFile(archiveFileUri: Uri) {
        if (outputDirectory == null) {
            showToast("Please select where to extract the files")
            return
        }

        val archiveFileName = getArchiveFileName(archiveFileUri)

        archiveFileName?.let { fileName ->
            val outputDirectory = outputDirectory?.createDirectory(fileName.substringBeforeLast("."))

            val inputStream = contentResolver.openInputStream(archiveFileUri)
            val bufferedInputStream = BufferedInputStream(inputStream)

            when (fileName.substringAfterLast(".").lowercase()) {
                "zip" -> extractZip(bufferedInputStream, outputDirectory)
                "tar" -> extractTar(bufferedInputStream, outputDirectory)
                "bz2" -> extractBzip2(bufferedInputStream, outputDirectory)
                "gz" -> extractGzip(bufferedInputStream, outputDirectory)
                "7z" -> extract7z(bufferedInputStream, outputDirectory)
                "xz" -> extractXz(bufferedInputStream, outputDirectory)
                else -> showToast("Unsupported archive format")
            }
        } ?: showToast("Failed to get the archive file name")
    }


    private fun extractZip(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        val zipInputStream = ZipInputStream(bufferedInputStream)

        var entry = zipInputStream.nextEntry
        while (entry != null) {
            val outputFile = outputDirectory?.createFile("application/octet-stream", entry.name)
            if (entry.isDirectory) {
                outputFile!!.createDirectory("UnZip")
            } else {
                outputFile?.uri?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(1024)
                        var count: Int
                        try {
                            while (zipInputStream.read(buffer).also { count = it } != -1) {
                                outputStream.write(buffer, 0, count)
                            }
                        } catch (e: Exception) {
                            showToast("Extraction failed: ${e.message}")
                            zipInputStream.closeEntry()
                            zipInputStream.close()
                            return
                        }
                    }
                }
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }

        zipInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)
    }

    private fun extractTar(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        val tarInputStream = TarArchiveInputStream(bufferedInputStream)

        var entry = tarInputStream.nextTarEntry
        while (entry != null) {
            val outputFile = outputDirectory?.createFile("application/octet-stream", entry.name)
            if (entry.isDirectory) {
                outputFile!!.createDirectory("UnTar")
            } else {
                outputFile?.uri?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(1024)
                        var count: Int
                        try {
                            while (tarInputStream.read(buffer).also { count = it } != -1) {
                                outputStream.write(buffer, 0, count)
                            }
                        } catch (e: Exception) {
                            showToast("Extraction failed: ${e.message}")
                            tarInputStream.close()
                            return
                        }
                    }
                }
            }
            entry = tarInputStream.nextTarEntry
        }

        tarInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)
    }
    private fun extractBzip2(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        val bzip2InputStream = BZip2CompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (bzip2InputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    bzip2InputStream.close()
                    return
                }
            }
        }

        bzip2InputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)
    }

    private fun extractGzip(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        val gzipInputStream = GzipCompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (gzipInputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    gzipInputStream.close()
                    return
                }
            }
        }

        gzipInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)
    }
    private suspend fun createTemp7zFileInBackground(bufferedInputStream: BufferedInputStream): File = withContext(Dispatchers.IO) {
        return@withContext File.createTempFile("temp_", ".7z", cacheDir).apply {
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
        val tempFileJob = CoroutineScope(Dispatchers.Default).launch {
            val tempFile = createTemp7zFileInBackground(bufferedInputStream)

            // Continue with the rest of your extraction logic using the tempFile
            SevenZFile(tempFile).use { sevenZFile ->
                var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
                while (entry != null) {
                    val outputFile = outputDirectory?.createFile("application/octet-stream", entry.name)
                    if (entry.isDirectory) {
                        outputFile?.createDirectory("Un7z")
                    } else {
                        outputFile?.uri?.let { uri ->
                            contentResolver.openOutputStream(uri)?.use { outputStream ->
                                val buffer = ByteArray(4096)
                                try {
                                    var count: Int
                                    while (sevenZFile.read(buffer).also { count = it } != -1) {
                                        outputStream.write(buffer, 0, count)
                                    }
                                } catch (e: Exception) {
                                    showToast("Extraction failed: ${e.message}")
                                    sevenZFile.close()
                                }
                            }
                        }
                    }
                    entry = sevenZFile.nextEntry
                }
            }
            // Show a completion message after extraction is done
            withContext(Dispatchers.Main) {
                showExtractionCompletedSnackbar(outputDirectory)
            }
        }

        tempFileJob.invokeOnCompletion { throwable ->
            if (throwable != null) {
                showToast("Temp file creation failed: ${throwable.message}")
            }
        }
    }

    private fun extractXz(bufferedInputStream: BufferedInputStream, outputDirectory: DocumentFile?) {
        val xzInputStream = XZCompressorInputStream(bufferedInputStream)

        val outputFile = outputDirectory?.createFile("application/octet-stream", "output")

        outputFile?.uri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(1024)
                var count: Int
                try {
                    while (xzInputStream.read(buffer).also { count = it } != -1) {
                        outputStream.write(buffer, 0, count)
                    }
                } catch (e: Exception) {
                    showToast("Extraction failed: ${e.message}")
                    xzInputStream.close()
                    return
                }
            }
        }

        xzInputStream.close()
        showExtractionCompletedSnackbar(outputDirectory)
    }


    private fun showExtractionCompletedSnackbar(outputDirectory: DocumentFile?) {
        progressBar.visibility = View.GONE
        val rootView = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, "Extraction completed successfully", Snackbar.LENGTH_LONG)

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
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = contentResolver.query(archiveFileUri!!, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                return it.getString(nameIndex)
            }
        }
        return null
    }


    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

