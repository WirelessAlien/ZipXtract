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
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import com.wirelessalien.zipxtract.constant.ServiceConstants
import com.wirelessalien.zipxtract.repository.FileRepository
import com.wirelessalien.zipxtract.service.ExtractArchiveService
import com.wirelessalien.zipxtract.service.ExtractCsArchiveService
import com.wirelessalien.zipxtract.service.ExtractMultipart7zService
import com.wirelessalien.zipxtract.service.ExtractMultipartZipService
import com.wirelessalien.zipxtract.service.ExtractRarService

class OpenWithViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val repository = FileRepository(application, sharedPreferences)

    fun addFilesForJob(files: List<String>): String {
        return repository.addFilesForJob(files)
    }

    fun startExtractionService(file: String, password: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startExtractionCsService(file: String) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractCsArchiveService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startRarExtractionService(file: String, password: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractRarService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startMulti7zExtractionService(file: String, password: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractMultipart7zService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startMultiZipExtractionService(file: String, password: String?) {
        val jobId = repository.addFilesForJob(listOf(file))
        val intent = Intent(getApplication(), ExtractMultipartZipService::class.java).apply {
            putExtra(ServiceConstants.EXTRA_JOB_ID, jobId)
            putExtra(ServiceConstants.EXTRA_PASSWORD, password)
            putExtra(ServiceConstants.EXTRA_USE_APP_NAME_DIR, true)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }
}
