package com.wirelessalien.zipxtract

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var extractButton: Button
    private lateinit var directoryTextView: TextView // New TextView to display the selected output directory
    private var zipFileUri: Uri? = null
    private var outputDirectory: DocumentFile? = null
    private lateinit var sharedPreferences: SharedPreferences

    private val requestPermissionCode = 1

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            zipFileUri = result.data?.data
            if (zipFileUri != null) {
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
                directoryTextView.text = outputDirectory?.name // Update the directory name in the TextView

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
        directoryTextView = findViewById(R.id.directoryTextView) // Assign the TextView from the layout
        sharedPreferences = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)


        pickFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/zip"
            pickFileLauncher.launch(intent)
        }

        extractButton.setOnClickListener {
            if (zipFileUri != null) {
                extractZipFile(zipFileUri!!)
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
        ActivityCompat.requestPermissions(this, permissions, requestPermissionCode)
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
                    // Permissions denied, show a dialog with a message and provide the functionality to go to app info
                    showPermissionDeniedDialog()
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

    private fun extractZipFile(zipFileUri: Uri) {
        if (outputDirectory == null) {
            showToast("Please select where to extract the files")
            return
        }

        val zipFileName = getZipFileName(zipFileUri)
        val outputDirectory = outputDirectory?.createDirectory(zipFileName!!)

        val inputStream = contentResolver.openInputStream(zipFileUri)
        val zipInputStream = ZipInputStream(BufferedInputStream(inputStream))

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

        // Show a Snackbar with the output directory path and an action button
        val rootView = findViewById<View>(android.R.id.content)
        Snackbar.make(rootView, "Extraction completed successfully", Snackbar.LENGTH_LONG)
            .setAction("Open Folder") {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(outputDirectory?.uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                Log.d("OutputDirectory", "Path: ${outputDirectory?.uri}")
                startActivity(intent)
            }

            .show()
    }



    private fun getZipFileName(zipFileUri: Uri?): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = contentResolver.query(zipFileUri!!, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val fileNameWithExtension = it.getString(nameIndex)
                return fileNameWithExtension.substringBeforeLast(".")
            }
        }
        return null
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

