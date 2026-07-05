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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow

sealed class AppEvent {
    data class ExtractionProgress(val progress: Int) : AppEvent()
    data class ExtractionComplete(val dirPath: String) : AppEvent()
    data class ExtractionError(val errorMessage: String) : AppEvent()
    data class ArchiveProgress(val progress: Int) : AppEvent()
    data class ArchiveComplete(val dirPath: String?) : AppEvent()
    data class ArchiveError(val errorMessage: String?) : AppEvent()
}

object EventBus {
    private val _events = Channel<AppEvent>(Channel.BUFFERED)
    val events: Flow<AppEvent> = _events.receiveAsFlow()

    suspend fun emit(event: AppEvent) {
        _events.send(event)
    }
}
