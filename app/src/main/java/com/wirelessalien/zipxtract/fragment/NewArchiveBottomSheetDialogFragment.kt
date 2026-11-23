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

package com.wirelessalien.zipxtract.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.BottomSheetNewArchiveBinding
import com.wirelessalien.zipxtract.helper.OngoingTask
import com.wirelessalien.zipxtract.helper.OngoingTaskManager
import com.wirelessalien.zipxtract.helper.TaskStatus
import com.wirelessalien.zipxtract.helper.TaskType
import kotlinx.coroutines.launch

class NewArchiveBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface NewArchiveListener {
        fun onCreateZip()
        fun onCreate7z()
        fun onCreateTar()
        fun onCancelOngoing()
        fun onExpandOngoing(task: OngoingTask)
    }

    private var _binding: BottomSheetNewArchiveBinding? = null
    private val binding get() = _binding!!
    private var listener: NewArchiveListener? = null

    fun setListener(listener: NewArchiveListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNewArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = insets.bottom)
            windowInsets
        }

        binding.cardCreateZip.setOnClickListener {
            dismiss()
            listener?.onCreateZip()
        }

        binding.cardCreate7z.setOnClickListener {
            dismiss()
            listener?.onCreate7z()
        }

        binding.cardCreateTar.setOnClickListener {
            dismiss()
            listener?.onCreateTar()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                OngoingTaskManager.currentTask.collect { task ->
                    updateOngoingProcessUI(task)
                }
            }
        }
    }

    private fun updateOngoingProcessUI(task: OngoingTask?) {
        if (task == null || task.status == TaskStatus.ERROR) {
            binding.cardOngoingProcess.visibility = View.GONE
            return
        }

        binding.cardOngoingProcess.visibility = View.VISIBLE
        val title = when (task.type) {
            TaskType.EXTRACTION -> getString(R.string.task_extracting, task.name)
            TaskType.ARCHIVE -> getString(R.string.task_archiving, task.name)
        }

        when (task.status) {
            TaskStatus.RUNNING -> {
                binding.ongoingTaskName.text = title
                if (task.progress in 1..99) {
                    binding.ongoingCircularProgress.isIndeterminate = false
                    binding.ongoingCircularProgress.progress = task.progress
                    binding.ongoingTaskProgress.text = "${task.progress}%"
                } else {
                    binding.ongoingCircularProgress.isIndeterminate = true
                    binding.ongoingTaskProgress.text = if (task.progress == 100) "100%" else getString(R.string.in_progress)
                }

                binding.btnOngoingCancel.visibility = View.VISIBLE
                binding.btnOngoingCancel.text = getString(R.string.cancel)
                binding.btnOngoingCancel.setOnClickListener {
                    listener?.onCancelOngoing()
                }

                binding.btnOngoingExpand.visibility = View.VISIBLE
                binding.btnOngoingExpand.text = getString(R.string.expand)
                binding.btnOngoingExpand.setOnClickListener {
                    dismiss()
                    listener?.onExpandOngoing(task)
                }

                binding.cardOngoingProcess.setOnClickListener {
                    dismiss()
                    listener?.onExpandOngoing(task)
                }
            }
            TaskStatus.COMPLETED -> {
                binding.ongoingTaskName.text = getString(R.string.task_completed, task.name)
                binding.ongoingCircularProgress.isIndeterminate = false
                binding.ongoingCircularProgress.progress = 100
                binding.ongoingTaskProgress.text = getString(R.string.tap_to_open_folder)

                binding.btnOngoingCancel.visibility = View.VISIBLE
                binding.btnOngoingCancel.text = getString(R.string.btn_close)
                binding.btnOngoingCancel.setOnClickListener {
                    OngoingTaskManager.clearTask()
                }

                binding.btnOngoingExpand.visibility = View.VISIBLE
                binding.btnOngoingExpand.text = getString(R.string.btn_open)
                binding.btnOngoingExpand.setOnClickListener {
                    dismiss()
                    listener?.onExpandOngoing(task)
                }

                binding.cardOngoingProcess.setOnClickListener {
                    dismiss()
                    listener?.onExpandOngoing(task)
                }
            }
            TaskStatus.ERROR -> {
                binding.cardOngoingProcess.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): NewArchiveBottomSheetDialogFragment {
            return NewArchiveBottomSheetDialogFragment()
        }
    }
}
