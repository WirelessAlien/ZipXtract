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

package com.wirelessalien.zipxtract.viewmodel

import android.app.Application
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.color.MaterialColors
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.helper.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ArchiveCreationViewModel(application: Application) : AndroidViewModel(application) {

    private val _totalSize = MutableStateFlow<Long>(0)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()

    private val _storageWarning = MutableStateFlow<String?>(null)
    val storageWarning: StateFlow<String?> = _storageWarning.asStateFlow()

    private var storageCheckJob: Job? = null

    fun calculateTotalSize(filePaths: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val size = filePaths.sumOf { File(it).length() }
            _totalSize.value = size
        }
    }

    fun checkStorageForArchive(path: String, requiredSize: Long) {
        storageCheckJob?.cancel()
        storageCheckJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stat = StatFs(path)
                val availableSize = stat.availableBytes
                val safeRequiredSize = (requiredSize * 1.1).toLong()

                if (availableSize < safeRequiredSize) {
                    val availableSizeStr = Formatter.formatFileSize(getApplication(), availableSize)
                    val requiredSizeStr = Formatter.formatFileSize(getApplication(), requiredSize)
                    val warningText = getApplication<Application>().getString(
                        R.string.low_storage_warning_dynamic,
                        availableSizeStr,
                        requiredSizeStr
                    )
                    _storageWarning.value = warningText
                } else {
                    _storageWarning.value = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _storageWarning.value = null
            }
        }
    }
}
