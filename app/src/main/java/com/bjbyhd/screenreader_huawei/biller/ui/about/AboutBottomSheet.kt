package com.bjbyhd.screenreader_huawei.biller.ui.about

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bjbyhd.screenreader_huawei.biller.databinding.DialogAboutBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 关于 BottomSheet — 替代旧版 AboutFragment (Phase 4)
 *
 * 模块: feature/biller/ui/about
 * 职责: 以 BottomSheet 形式展示应用信息、隐私权限说明、保活指南入口。
 *
 * ## 设计理由
 *
 *   关于页面包含约 1500 字的权限说明文本。如果作为"我的"Tab 的内嵌内容，
 *   会导致页面过度滚动。BottomSheet 分层展示——主干操作在"我的"页面，
 *   长文本信息按需从底部滑出。
 *
 * ## 使用方式
 *
 * ```kotlin
 * AboutBottomSheet().show(parentFragmentManager, "AboutBottomSheet")
 * ```
 */
class AboutBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setOnShowListener { dialog ->
                val bottomSheet = (dialog as? BottomSheetDialog)
                    ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                bottomSheet?.let {
                    BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
