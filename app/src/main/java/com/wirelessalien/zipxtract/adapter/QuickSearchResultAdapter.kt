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

package com.wirelessalien.zipxtract.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ItemSearchHistoryBinding
import com.wirelessalien.zipxtract.databinding.ItemSearchResultFileBinding
import com.wirelessalien.zipxtract.model.FileItem
import java.util.Locale

class QuickSearchResultAdapter(
    private var items: MutableList<SearchResultItem>,
    private val onItemClick: (SearchResultItem) -> Unit,
    private val onDeleteHistoryClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HISTORY = 0
        private const val VIEW_TYPE_FILE = 1
    }

    sealed class SearchResultItem {
        data class HistoryItem(val query: String) : SearchResultItem()
        data class FileResultItem(val fileItem: FileItem) : SearchResultItem()
    }

    class HistoryViewHolder(val binding: ItemSearchHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    class FileViewHolder(val binding: ItemSearchResultFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SearchResultItem.HistoryItem -> VIEW_TYPE_HISTORY
            is SearchResultItem.FileResultItem -> VIEW_TYPE_FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HISTORY) {
            val binding = ItemSearchHistoryBinding.inflate(inflater, parent, false)
            HistoryViewHolder(binding)
        } else {
            val binding = ItemSearchResultFileBinding.inflate(inflater, parent, false)
            FileViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchResultItem.HistoryItem -> {
                val historyHolder = holder as HistoryViewHolder
                historyHolder.binding.historyText.text = item.query
                historyHolder.itemView.setOnClickListener { onItemClick(item) }
                historyHolder.binding.deleteButton.setOnClickListener { onDeleteHistoryClick(item.query) }
            }
            is SearchResultItem.FileResultItem -> {
                val fileHolder = holder as FileViewHolder
                val file = item.fileItem.file
                fileHolder.binding.resultName.text = file.name
                fileHolder.binding.resultPath.text = file.parent

                if (item.fileItem.isDirectory) {
                    fileHolder.binding.resultIcon.setImageResource(R.drawable.ic_folder)
                    fileHolder.binding.resultIcon.visibility = View.VISIBLE
                    fileHolder.binding.resultExtension.visibility = View.GONE
                } else {
                    val imageLoadingOptions = RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(48, 48)
                        .format(DecodeFormat.PREFER_RGB_565)

                    when (file.extension.lowercase(Locale.getDefault())) {
                        "png", "jpg", "bmp", "jpeg", "gif", "webp" -> {
                            val requestBuilder = Glide.with(fileHolder.itemView.context)
                                .asDrawable()
                                .apply(imageLoadingOptions)
                                .sizeMultiplier(0.25f)

                            Glide.with(fileHolder.itemView.context)
                                .load(file)
                                .apply(imageLoadingOptions)
                                .thumbnail(requestBuilder)
                                .into(fileHolder.binding.resultIcon)

                            fileHolder.binding.resultIcon.visibility = View.VISIBLE
                            fileHolder.binding.resultExtension.visibility = View.GONE
                        }
                        else -> {
                            fileHolder.binding.resultIcon.visibility = View.GONE
                            fileHolder.binding.resultExtension.visibility = View.VISIBLE
                            fileHolder.binding.resultExtension.text = if (file.extension.isNotEmpty()) {
                                if (file.extension.length > 4) {
                                    "FILE"
                                } else {
                                    if (file.extension.length == 4) {
                                        fileHolder.binding.resultExtension.textSize = 16f
                                    } else {
                                        fileHolder.binding.resultExtension.textSize = 18f
                                    }
                                    file.extension.uppercase(Locale.getDefault())
                                }
                            } else {
                                "..."
                            }
                        }
                    }
                }

                fileHolder.itemView.setOnClickListener { onItemClick(item) }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newItems: List<SearchResultItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
