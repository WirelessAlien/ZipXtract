/*
 *  Copyright (C) 2026  WirelessAlien <https://github.com/WirelessAlien>
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

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.ItemMultiRenamePreviewBinding
import com.wirelessalien.zipxtract.helper.RenameHelper
import com.wirelessalien.zipxtract.helper.RenameHelper.PreviewStatus

class MultiRenamePreviewAdapter : RecyclerView.Adapter<MultiRenamePreviewAdapter.ViewHolder>() {

    private var items: List<RenameHelper.RenamePreviewItem> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newItems: List<RenameHelper.RenamePreviewItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemMultiRenamePreviewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMultiRenamePreviewBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val binding = holder.binding

        binding.oldNameText.text = item.sourceFile.name
        binding.newNameText.text = "\u2192 ${item.newName}"

        val errorColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError)
        val normalColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val mutedColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        when (item.status) {
            PreviewStatus.VALID -> {
                binding.newNameText.setTextColor(normalColor)
                binding.statusErrorText.visibility = View.GONE
            }
            PreviewStatus.UNCHANGED -> {
                binding.newNameText.setTextColor(mutedColor)
                binding.statusErrorText.visibility = View.GONE
            }
            PreviewStatus.INVALID_NAME -> {
                binding.newNameText.setTextColor(errorColor)
                binding.statusErrorText.visibility = View.VISIBLE
                binding.statusErrorText.setText(R.string.rename_error_invalid_chars)
            }
            PreviewStatus.INVALID_REGEX -> {
                binding.newNameText.setTextColor(errorColor)
                binding.statusErrorText.visibility = View.VISIBLE
                binding.statusErrorText.setText(R.string.multi_rename_error_regex)
            }
            PreviewStatus.CONFLICT_DUPLICATE -> {
                binding.newNameText.setTextColor(errorColor)
                binding.statusErrorText.visibility = View.VISIBLE
                binding.statusErrorText.setText(R.string.multi_rename_error_duplicate)
            }
            PreviewStatus.CONFLICT_EXISTS -> {
                binding.newNameText.setTextColor(errorColor)
                binding.statusErrorText.visibility = View.VISIBLE
                binding.statusErrorText.setText(R.string.rename_error_exists)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
