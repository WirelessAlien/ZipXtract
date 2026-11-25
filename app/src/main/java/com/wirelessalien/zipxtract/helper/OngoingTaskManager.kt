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
import java.util.concurrent.atomic.AtomicLong

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
    val errorMessage: String? = null,
    val id: Long = 0L
)

object OngoingTaskManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val nextTaskId = AtomicLong(0L)
    private val _currentTask = MutableStateFlow<OngoingTask?>(null)
    val currentTask: StateFlow<OngoingTask?> = _currentTask.asStateFlow()

    init {
        scope.launch {
            EventBus.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    internal fun handleEvent(event: AppEvent) {
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
                errorTask(TaskType.EXTRACTION, event.errorMessage)
            }
            is AppEvent.ArchiveError -> {
                errorTask(TaskType.ARCHIVE, event.errorMessage)
            }
        }
    }

    fun startTask(type: TaskType, name: String, outputDir: String? = null): Boolean {
        val current = _currentTask.value
        if (current != null && current.status == TaskStatus.RUNNING) {
            return false
        }
        _currentTask.value = OngoingTask(
            type = type,
            name = name,
            progress = 0,
            status = TaskStatus.RUNNING,
            outputDir = outputDir,
            id = nextTaskId.incrementAndGet()
        )
        return true
    }

    private fun updateProgress(type: TaskType, progress: Int) {
        val current = _currentTask.value ?: return
        if (current.status == TaskStatus.RUNNING && current.type == type) {
            _currentTask.value = current.copy(progress = progress)
        }
    }

    private fun completeTask(type: TaskType, outputDir: String?) {
        val current = _currentTask.value ?: return
        if (current.status == TaskStatus.RUNNING && current.type == type) {
            _currentTask.value = current.copy(
                progress = 100,
                status = TaskStatus.COMPLETED,
                outputDir = outputDir ?: current.outputDir
            )
        }
    }

    private fun errorTask(type: TaskType, errorMessage: String?) {
        val current = _currentTask.value ?: return
        if (current.status == TaskStatus.RUNNING && current.type == type) {
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
