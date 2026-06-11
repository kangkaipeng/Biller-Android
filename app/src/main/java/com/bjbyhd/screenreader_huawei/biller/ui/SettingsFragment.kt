package com.bjbyhd.screenreader_huawei.biller.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bjbyhd.screenreader_huawei.biller.databinding.FragmentSettingsBinding
import com.bjbyhd.screenreader_huawei.biller.settings.SettingsManager
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 无障碍服务设置页面 — 运行时过滤配置
 *
 * 模块: feature/biller/ui
 * 职责:
 *   - 提供支付信息过滤开关 + 界面树输出开关 + 预设应用独立开关
 *   - 保存到 SharedPreferences，Service 实时读取
 *
 * ## 与宿主 Activity 的通信
 *
 *   通过 [OnSettingsInteractionListener] 回调接口与宿主 Activity 通信。
 *   Fragment 不直接操作 Activity 的 View 层级，
 *   宿主 Activity 自行决定保存设置后的 UI 行为（如关闭本 Fragment、恢复主界面等）。
 *   这遵循迪米特法则（最少知识原则）。
 *
 * @see OnSettingsInteractionListener 宿主 Activity 需实现的回调接口
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG = "Biller/Settings"
    }

    /**
     * 宿主 Activity 需实现的回调接口
     *
     * 替代此前 Fragment 直接通过 [requireActivity().findViewById] 操作
     * Activity 布局的紧耦合方式。
     */
    interface OnSettingsInteractionListener {
        /** 设置保存完成后的回调 — 宿主决定后续 UI 行为 */
        fun onSettingsSaved()
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsManager
    private var listener: OnSettingsInteractionListener? = null

    // ═══════════ 生命周期 ═══════════

    /**
     * 在 onAttach 中获取宿主 Activity 的回调接口引用
     *
     * 使用 [Context] 参数而非 [requireActivity] 是因为 onAttach 阶段
     * Activity 尚未完全就绪。若宿主未实现接口，listener 为 null——
     * 这是允许的，Fragment 可独立工作（如嵌入测试 Activity）。
     */
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnSettingsInteractionListener) {
            listener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = SettingsManager.load(requireContext())

        loadCurrentSettings()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    // ═══════════ UI 逻辑 ═══════════

    /**
     * 从 SharedPreferences 加载当前设置到 UI 控件
     */
    private fun loadCurrentSettings() {
        binding.switchTreeOutput.isChecked = settings.isTreeOutputEnabled()
        binding.switchWechatEnabled.isChecked = settings.isWechatEnabled()
        binding.switchAlipayEnabled.isChecked = settings.isAlipayEnabled()
        binding.switchMeituanEnabled.isChecked = settings.isMeituanEnabled()
    }

    /**
     * 注册保存按钮的点击事件
     */
    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    /**
     * 保存设置到 SharedPreferences
     *
     * 保存完成后:
     *   1. 通过 [OnSettingsInteractionListener.onSettingsSaved] 通知宿主
     *   2. 若宿主未实现接口（listener == null），仅弹出当前 Fragment，
     *      不做布局容器操作（由宿主自行管理）
     */
    private fun saveSettings() {
        val treeOutputEnabled = binding.switchTreeOutput.isChecked
        val wechatEnabled = binding.switchWechatEnabled.isChecked
        val alipayEnabled = binding.switchAlipayEnabled.isChecked
        val meituanEnabled = binding.switchMeituanEnabled.isChecked

        settings.save {
            treeOutputEnabled(treeOutputEnabled)
            wechatEnabled(wechatEnabled)
            alipayEnabled(alipayEnabled)
            meituanEnabled(meituanEnabled)
        }

        CLog.i(TAG) {
            "设置已保存: treeOutput=$treeOutputEnabled " +
            "wechat=$wechatEnabled alipay=$alipayEnabled meituan=$meituanEnabled"
        }

        Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show()

        // 通过回调通知宿主，宿主决定 UI 行为（包括容器可见性切换）
        if (listener != null) {
            listener!!.onSettingsSaved()
        }
        parentFragmentManager.popBackStack()
    }
}
