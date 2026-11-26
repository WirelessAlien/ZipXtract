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

package com.wirelessalien.zipxtract.fragment

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.adapter.MultiRenamePreviewAdapter
import com.wirelessalien.zipxtract.databinding.BottomSheetMultiRenameBinding
import com.wirelessalien.zipxtract.helper.FileUtils
import com.wirelessalien.zipxtract.helper.RenameHelper
import com.wirelessalien.zipxtract.helper.RenameHelper.CaseTransform
import com.wirelessalien.zipxtract.helper.RenameHelper.MultiRenameConfig
import com.wirelessalien.zipxtract.helper.RenameHelper.MultiRenameMode
import com.wirelessalien.zipxtract.helper.RenameHelper.PreviewStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MultiRenameBottomSheetDialogFragment : BottomSheetDialogFragment() {

    fun interface OnBatchRenameListener {
        fun onBatchRenamed()
    }

    private var _binding: BottomSheetMultiRenameBinding? = null
    private val binding get() = _binding!!

    private var listener: OnBatchRenameListener? = null
    private lateinit var files: List<File>
    private lateinit var previewAdapter: MultiRenamePreviewAdapter
    private var currentPreviews: List<RenameHelper.RenamePreviewItem> = emptyList()
    private var currentMode: MultiRenameMode = MultiRenameMode.PATTERN

    fun setOnBatchRenameListener(listener: OnBatchRenameListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMultiRenameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED

        val paths = arguments?.getStringArrayList(ARG_FILE_PATHS).orEmpty()
        files = paths.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) {
            dismiss()
            return
        }

        binding.sheetTitle.text = getString(R.string.multi_rename_title, files.size)
        binding.switchKeepExtension.visibility = if (files.any { !it.isDirectory }) View.VISIBLE else View.GONE

        previewAdapter = MultiRenamePreviewAdapter()
        binding.previewRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.previewRecyclerView.adapter = previewAdapter

        setupModeToggle()
        setupPatternChips()
        setupListeners()

        binding.modeToggleGroup.check(R.id.btnModePattern)
        updatePreview()
    }

    private fun setupModeToggle() {
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentMode = when (checkedId) {
                R.id.btnModeReplace -> MultiRenameMode.FIND_REPLACE
                R.id.btnModeAddCase -> MultiRenameMode.ADD_CASE
                else -> MultiRenameMode.PATTERN
            }
            binding.sectionPattern.visibility = if (currentMode == MultiRenameMode.PATTERN) View.VISIBLE else View.GONE
            binding.sectionReplace.visibility = if (currentMode == MultiRenameMode.FIND_REPLACE) View.VISIBLE else View.GONE
            binding.sectionAddCase.visibility = if (currentMode == MultiRenameMode.ADD_CASE) View.VISIBLE else View.GONE
            updatePreview()
        }
    }

    private fun setupPatternChips() {
        val insertToken = { token: String ->
            val edit = binding.patternInput
            val start = edit.selectionStart.coerceAtLeast(0)
            val end = edit.selectionEnd.coerceAtLeast(0)
            edit.text?.replace(minOf(start, end), maxOf(start, end), token)
        }
        binding.chipTokenName.setOnClickListener { insertToken("{name}") }
        binding.chipTokenCounter.setOnClickListener { insertToken("{n:02}") }
        binding.chipTokenDate.setOnClickListener { insertToken("{date}") }
        binding.chipTokenExt.setOnClickListener { insertToken("{ext}") }
    }

    private fun setupListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
            }
        }

        binding.patternInput.addTextChangedListener(watcher)
        binding.startNumberInput.addTextChangedListener(watcher)
        binding.stepNumberInput.addTextChangedListener(watcher)
        binding.findInput.addTextChangedListener(watcher)
        binding.replaceInput.addTextChangedListener(watcher)
        binding.prefixInput.addTextChangedListener(watcher)
        binding.suffixInput.addTextChangedListener(watcher)

        binding.checkboxCaseSensitive.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.checkboxUseRegex.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.switchKeepExtension.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.caseChipGroup.setOnCheckedStateChangeListener { _, _ -> updatePreview() }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnApplyRename.setOnClickListener { applyRename() }
    }

    private fun buildConfig(): MultiRenameConfig {
        val caseTransform = when (binding.caseChipGroup.checkedChipId) {
            R.id.chipCaseLower -> CaseTransform.LOWERCASE
            R.id.chipCaseUpper -> CaseTransform.UPPERCASE
            R.id.chipCaseTitle -> CaseTransform.TITLE_CASE
            else -> CaseTransform.UNCHANGED
        }

        return MultiRenameConfig(
            mode = currentMode,
            keepExtension = binding.switchKeepExtension.isChecked,
            pattern = binding.patternInput.text?.toString().orEmpty(),
            startNumber = binding.startNumberInput.text?.toString()?.toIntOrNull() ?: 1,
            step = (binding.stepNumberInput.text?.toString()?.toIntOrNull() ?: 1).coerceAtLeast(1),
            findText = binding.findInput.text?.toString().orEmpty(),
            replaceText = binding.replaceInput.text?.toString().orEmpty(),
            caseSensitive = binding.checkboxCaseSensitive.isChecked,
            useRegex = binding.checkboxUseRegex.isChecked,
            prefix = binding.prefixInput.text?.toString().orEmpty(),
            suffix = binding.suffixInput.text?.toString().orEmpty(),
            caseTransform = caseTransform
        )
    }

    private fun updatePreview() {
        currentPreviews = RenameHelper.previewBatchRename(files, buildConfig())
        previewAdapter.submitList(currentPreviews)

        val validCount = currentPreviews.count { it.status == PreviewStatus.VALID }
        val hasErrors = currentPreviews.any { it.isError }
        binding.btnApplyRename.text = getString(R.string.multi_rename_action, validCount)
        binding.btnApplyRename.isEnabled = validCount > 0 && !hasErrors
    }

    private fun applyRename() {
        val previewsToRun = currentPreviews
        if (previewsToRun.none { it.status == PreviewStatus.VALID } || previewsToRun.any { it.isError }) {
            return
        }

        binding.btnApplyRename.isEnabled = false
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val summary = RenameHelper.executeBatchRename(previewsToRun)
            if (summary.affectedPaths.isNotEmpty()) {
                FileUtils.scanFiles(appContext, summary.affectedPaths)
            }
            withContext(Dispatchers.Main) {
                val ctx = context ?: return@withContext
                val msg = if (summary.failedCount > 0) {
                    getString(R.string.multi_rename_result_partial, summary.renamedCount, summary.failedCount)
                } else {
                    getString(R.string.multi_rename_result_summary, summary.renamedCount)
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                listener?.onBatchRenamed()
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MultiRenameBottomSheet"
        private const val ARG_FILE_PATHS = "arg_file_paths"

        fun newInstance(files: List<File>): MultiRenameBottomSheetDialogFragment {
            return MultiRenameBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_FILE_PATHS, ArrayList(files.map { it.absolutePath }))
                }
            }
        }
    }
}
