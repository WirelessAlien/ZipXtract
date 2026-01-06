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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SearchHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyKey = "history_list"
    private val maxHistorySize = 10

    fun getHistory(): MutableList<String> {
        val json = prefs.getString(historyKey, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<String>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addHistory(query: String) {
        if (query.isBlank()) return
        val history = getHistory()
        history.remove(query) // Remove if exists to move to top
        history.add(0, query)
        if (history.size > maxHistorySize) {
            history.removeAt(history.size - 1)
        }
        saveHistory(history)
    }

    fun removeHistory(query: String) {
        val history = getHistory()
        if (history.remove(query)) {
            saveHistory(history)
        }
    }

    fun clearHistory() {
        prefs.edit { remove(historyKey) }
    }

    private fun saveHistory(history: List<String>) {
        val json = gson.toJson(history)
        prefs.edit { putString(historyKey, json) }
    }
}
