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

package com.wirelessalien.zipxtract.constant

object BroadcastConstants {
    const val ACTION_EXTRACT_CANCEL = "ACTION_ARCHIVE_CANCEL"
    const val ACTION_RAR_EXTRACTION_CANCEL = "ACTION_RAR_EXTRACTION_CANCEL"
    const val ACTION_MULTI_7Z_EXTRACTION_CANCEL = "ACTION_7Z_EXTRACTION_CANCEL"
    const val ACTION_MULTI_ZIP_EXTRACTION_CANCEL = "ACTION_ZIP_EXTRACTION_CANCEL"
    const val ACTION_ARCHIVE_7Z_CANCEL = "ACTION_7Z_ARCHIVE_CANCEL"
    const val ACTION_ARCHIVE_ZIP_CANCEL = "ACTION_ZIP_ARCHIVE_CANCEL"
    const val ACTION_ARCHIVE_SPLIT_ZIP_CANCEL = "ACTION_SPLIT_ZIP_ARCHIVE_CANCEL"
    const val ACTION_ARCHIVE_COMPLETE = "ACTION_ARCHIVE_COMPLETE"
    const val ACTION_ARCHIVE_ERROR = "ACTION_ARCHIVE_ERROR"
    const val ACTION_EXTRACTION_COMPLETE = "ACTION_EXTRACTION_COMPLETE"
    const val ACTION_EXTRACTION_ERROR = "ACTION_EXTRACTION_ERROR"
    const val ARCHIVE_NOTIFICATION_CHANNEL_ID = "archive_notification_channel"
    const val EXTRACTION_NOTIFICATION_CHANNEL_ID = "extraction_notification_channel"
    const val ACTION_EXTRACTION_PROGRESS = "ACTION_EXTRACTION_PROGRESS"
    const val ACTION_ARCHIVE_PROGRESS = "ACTION_ARCHIVE_PROGRESS"
    const val EXTRA_PROGRESS = "progress"
    const val EXTRA_ERROR_MESSAGE = "error_message"
}

