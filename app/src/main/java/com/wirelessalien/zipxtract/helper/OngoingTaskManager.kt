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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TaskType {
    EXTRACTION, ARCHIVE
}

enum class TaskStatus {
    RUNNING, COMPLETED, ERROR
}

data class OngoingTask(
    val type: TaskType,
    val name: String,
    val progress: Int = 0,
    val status: TaskStatus = TaskStatus.RUNNING,
    val outputDir: String? = null,
    val errorMessage: String? = null
)

object OngoingTaskManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _currentTask = MutableStateFlow<OngoingTask?>(null)
    val currentTask: StateFlow<OngoingTask?> = _currentTask.asStateFlow()

    init {
        scope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ExtractionProgress -> {
                        updateProgress(TaskType.EXTRACTION, event.progress)
                    }
                    is AppEvent.ArchiveProgress -> {
                        updateProgress(TaskType.ARCHIVE, event.progress)
                    }
                    is AppEvent.ExtractionComplete -> {
                        completeTask(TaskType.EXTRACTION, event.dirPath)
                    }
                    is AppEvent.ArchiveComplete -> {
                        completeTask(TaskType.ARCHIVE, event.dirPath)
                    }
                    is AppEvent.ExtractionError -> {
                        errorTask(event.errorMessage)
                    }
                    is AppEvent.ArchiveError -> {
                        errorTask(event.errorMessage)
                    }
                }
            }
        }
    }

    fun startTask(type: TaskType, name: String, outputDir: String? = null) {
        _currentTask.value = OngoingTask(
            type = type,
            name = name,
            progress = 0,
            status = TaskStatus.RUNNING,
            outputDir = outputDir
        )
    }

    private fun updateProgress(type: TaskType, progress: Int) {
        val current = _currentTask.value
        if (current == null) {
            _currentTask.value = OngoingTask(
                type = type,
                name = if (type == TaskType.EXTRACTION) "Extraction" else "Archive",
                progress = progress,
                status = TaskStatus.RUNNING
            )
        } else if (current.status == TaskStatus.RUNNING) {
            _currentTask.value = current.copy(progress = progress)
        }
    }

    private fun completeTask(type: TaskType, outputDir: String?) {
        val current = _currentTask.value
        if (current != null) {
            _currentTask.value = current.copy(
                progress = 100,
                status = TaskStatus.COMPLETED,
                outputDir = outputDir ?: current.outputDir
            )
        } else {
            _currentTask.value = OngoingTask(
                type = type,
                name = if (type == TaskType.EXTRACTION) "Extraction" else "Archive",
                progress = 100,
                status = TaskStatus.COMPLETED,
                outputDir = outputDir
            )
        }
    }

    private fun errorTask(errorMessage: String?) {
        val current = _currentTask.value
        if (current != null) {
            _currentTask.value = current.copy(
                status = TaskStatus.ERROR,
                errorMessage = errorMessage
            )
        }
    }

    fun clearTask() {
        _currentTask.value = null
    }

    val isTaskRunning: Boolean
        get() = _currentTask.value?.status == TaskStatus.RUNNING

    val hasTask: Boolean
        get() = _currentTask.value != null
}
