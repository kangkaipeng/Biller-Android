package com.bjbyhd.screenreader_huawei.biller.ui.profile

import android.content.Intent
import android.os.Bundle
import com.bjbyhd.screenreader_huawei.biller.BuildConfig
import android.view.LayoutInflater
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bjbyhd.screenreader_huawei.biller.R
import com.bjbyhd.screenreader_huawei.biller.databinding.FragmentProfileBinding
import com.bjbyhd.screenreader_huawei.biller.ui.about.AboutBottomSheet
import com.bjbyhd.screenreader_huawei.biller.ui.common.ViewModelFactory
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 我的 Tab Fragment — MVI 模式 View 层 (Phase 3)
 *
 * 模块: feature/biller/ui/profile
 * 职责:
 *   - 展示双服务（通知监听 + 无障碍）连接状态
 *   - 提供系统设置跳转入口
 *   - 管理关于 BottomSheet 的弹出/关闭
 *   - 收集 [ProfileViewModel.uiState] StateFlow 驱动 UI 渲染
 */
class ProfileFragment : Fragment() {

    companion object {
        private const val TAG = "Biller/ProfileFrag"
    }

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ProfileViewModel

    /**
     * 系统文件选择器 — 仅选择 CSV 文件
     *
     * 使用 [ActivityResultContracts.OpenDocument] 而非旧的 startActivityForResult，
     * 这是 AndroidX 推荐的做法，生命周期自动管理，避免手动 requestCode 碰撞。
     */
    private val csvFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onEvent(ProfileEvent.ImportCsvFromUri(it)) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = ViewModelFactory(requireContext())
        viewModel = ViewModelProvider(this, factory)[ProfileViewModel::class.java]

        setupClickListeners()
        observeUiState()
    }

    /** 注册按钮点击事件 → 发射 ProfileEvent */
    private fun setupClickListeners() {
        with(binding) {
            btnRefreshStatus.setOnClickListener {
                viewModel.onEvent(ProfileEvent.CheckServiceStatus)
            }
            icNotificationChevron.setOnClickListener {
                viewModel.onEvent(ProfileEvent.OpenNotificationSettings)
            }
            icAccessibilityChevron.setOnClickListener {
                viewModel.onEvent(ProfileEvent.OpenAccessibilitySettings)
            }
            btnAbout.setOnClickListener {
                viewModel.onEvent(ProfileEvent.ShowAboutSheet)
            }
            btnExportCsv.setOnClickListener {
                viewModel.onEvent(ProfileEvent.ExportCsv)
            }
            btnImportCsv.setOnClickListener {
                viewModel.onEvent(ProfileEvent.ImportCsv)
            }
            btnExportLogs.setOnClickListener {
                viewModel.onEvent(ProfileEvent.ExportLogs)
            }
            btnClearLogs.setOnClickListener {
                viewModel.onEvent(ProfileEvent.ClearLogs)
            }
            btnExportDiagnostic.setOnClickListener {
                viewModel.onEvent(ProfileEvent.ExportDiagnostic)
            }
        }
    }

    /** 收集 StateFlow 和 Effect Channel 驱动 UI 更新 */
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 并行: State 渲染
                launch {
                    viewModel.uiState.collect { state ->
                        // ── 服务状态指示 ──
                        updateServiceStatus(
                            binding.dotNotification, binding.tvNotificationStatus,
                            state.notificationConnected
                        )
                        updateServiceStatus(
                            binding.dotAccessibility, binding.tvAccessibilityStatus,
                            state.accessibilityConnected
                        )

                        // ── 导出按钮状态 ──
                        if (state.isExporting) {
                            binding.btnExportCsv.text = "导出中..."
                            binding.btnExportCsv.isEnabled = false
                        } else {
                            binding.btnExportCsv.text = "📥 导出 CSV（全量）"
                            binding.btnExportCsv.isEnabled = true
                        }
                        // ── 导入按钮状态 ──
                        if (state.isImporting) {
                            binding.btnImportCsv.text = "导入中..."
                            binding.btnImportCsv.isEnabled = false
                        } else {
                            binding.btnImportCsv.text = "📤 导入 CSV"
                            binding.btnImportCsv.isEnabled = true
                        }
                        // 日志导出按钮加载态
                        if (state.isExportingLogs) {
                            binding.btnExportLogs.text = "打包中..."
                            binding.btnExportLogs.isEnabled = false
                        } else {
                            binding.btnExportLogs.text = "📋 导出日志"
                            binding.btnExportLogs.isEnabled = true
                        }
                        // 日志清除按钮加载态
                        if (state.isClearingLogs) {
                            binding.btnClearLogs.text = "清除中..."
                            binding.btnClearLogs.isEnabled = false
                        } else {
                            binding.btnClearLogs.text = "🗑️ 清除日志"
                            binding.btnClearLogs.isEnabled = true
                        }

                        binding.tvVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)

                        // ── 最后检查时间 ──
                        state.lastCheckTime?.let { timeMs ->
                            val minutes = (System.currentTimeMillis() - timeMs) / 60_000
                            val display = when {
                                minutes < 1 -> "刚刚"
                                minutes < 60 -> "${minutes}分钟前"
                                else -> "${minutes / 60}小时前"
                            }
                            binding.tvLastCheckTime.text = "上次检查：$display"
                        }

                        // ── 错误消息 ──
                        state.errorMessage?.let { error ->
                            if (error.isNotEmpty()) {
                                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                                viewModel.onEvent(ProfileEvent.ClearError)
                            }
                        }

                        // 导入结果提示
                        state.importResult?.let { result ->
                            Toast.makeText(
                                requireContext(),
                                result.toSummary(),
                                Toast.LENGTH_LONG,
                            ).show()
                            viewModel.onEvent(ProfileEvent.DismissImportResult)
                        }

                        // ── 关于 BottomSheet ──
                        if (state.showAboutSheet) {
                            AboutBottomSheet().show(parentFragmentManager, "AboutBottomSheet")
                            viewModel.onEvent(ProfileEvent.DismissAboutSheet)
                        }
                    }
                }
                // 并行: Effect 消费
                launch {
                    viewModel.effect.collect { effect ->
                        when (effect) {
                            // 启动系统文件选择器选择 CSV 文件
                            is ProfileEffect.LaunchFilePicker -> {
                                csvFilePicker.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                            }
                            is ProfileEffect.ShareCsv -> {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, effect.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(
                                    Intent.createChooser(shareIntent, "分享 ${effect.fileName}")
                                )
                            }
                            // 分享日志 ZIP 文件
                            is ProfileEffect.ShareLogs -> {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, effect.uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "应用日志导出 - ${effect.fileName}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(
                                    Intent.createChooser(shareIntent, "分享日志文件")
                                )
                            }
                            // 分享诊断日志文件
                            is ProfileEffect.ShareDiagnostic -> {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, effect.uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "诊断日志导出 - ${effect.fileName}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(
                                    Intent.createChooser(shareIntent, "分享诊断日志")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 更新单个服务状态指示器（圆点颜色 + 文本）
     *
     * 使用 [ContextCompat.getColor] 从颜色资源中获取颜色值，
     * 确保在浅色/深色主题下自动适配，遵循 Material 3 色彩体系。
     *
     * @param dot       状态指示圆点 View
     * @param textView  状态描述文本 View
     * @param connected 服务是否已连接
     */
    private fun updateServiceStatus(
        dot: View,
        textView: android.widget.TextView,
        connected: Boolean
    ) {
        val colorRes = if (connected) R.color.status_connected else R.color.status_disconnected
        val colorInt = ContextCompat.getColor(requireContext(), colorRes)
        dot.background.setTint(colorInt)
        textView.text = if (connected) "已连接" else "未授权"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
