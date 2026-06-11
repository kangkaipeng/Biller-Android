package com.bjbyhd.screenreader_huawei.biller.settings

import android.content.Context

/**
 * 无障碍服务运行时设置管理器
 *
 * 模块: feature/biller/settings
 * 职责: 封装 SharedPreferences，提供 UI 和 Service 统一的设置读写入口。
 *
 * 设置项:
 *   - [packageFilterEnabled]: 是否启用包名过滤
 *   - [targetPackageNames]: 目标包名列表（逗号分隔字符串）
 *   - [paymentFilterEnabled]: 是否过滤支付相关信息
 *
 * 线程安全:
 *   SharedPreferences 的读取是线程安全的；写入通过 apply() 异步提交。
 *
 * 使用方式:
 *   - Service 中: SettingsManager.load(context).isPackageFilterEnabled()
 *   - UI 中: SettingsManager.load(context).save{...}
 */
class SettingsManager private constructor(private val prefs: android.content.SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "biller_settings"
        private const val KEY_TREE_OUTPUT_ENABLED = "tree_output_enabled"
        private const val KEY_EVENT_SUMMARY_ENABLED = "event_summary_enabled"

        // 预设应用独立开关 Key — 默认关闭 (用户需显式开启才开始监听)
        private const val KEY_APP_WECHAT_ENABLED = "app_wechat_enabled"
        private const val KEY_APP_ALIPAY_ENABLED = "app_alipay_enabled"
        private const val KEY_APP_MEITUAN_ENABLED = "app_meituan_enabled"

        /**
         * 从 Context 加载设置
         *
         * @param context 任意 Context（内部使用 applicationContext）
         * @return SettingsManager 实例
         */
        fun load(context: Context): SettingsManager {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SettingsManager(prefs)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 读取设置
    // ═══════════════════════════════════════════════════════════════

    /** 是否输出完整界面树（默认 false = 不输出，节省日志量） */
    fun isTreeOutputEnabled(): Boolean {
        return prefs.getBoolean(KEY_TREE_OUTPUT_ENABLED, false)
    }

    /** 是否输出事件摘要日志（默认 false = 不输出，仅调试时开启） */
    fun isEventSummaryEnabled(): Boolean {
        return prefs.getBoolean(KEY_EVENT_SUMMARY_ENABLED, false)
    }

    // ═══════════════════════════════════════════════════════════════
    // 预设应用独立开关 — 默认关闭 (用户需显式开启)
    // ═══════════════════════════════════════════════════════════════

    /** 微信监听开关 (com.tencent.mm) — 默认关闭 */
    fun isWechatEnabled(): Boolean = prefs.getBoolean(KEY_APP_WECHAT_ENABLED, false)

    /** 支付宝监听开关 (com.eg.android.AlipayGphone) — 默认关闭 */
    fun isAlipayEnabled(): Boolean = prefs.getBoolean(KEY_APP_ALIPAY_ENABLED, false)

    /** 美团监听开关 (com.sankuai.meituan) — 默认关闭，预留 */
    fun isMeituanEnabled(): Boolean = prefs.getBoolean(KEY_APP_MEITUAN_ENABLED, false)

    // ═══════════════════════════════════════════════════════════════
    // 写入设置
    // ═══════════════════════════════════════════════════════════════

    /**
     * 通过 Builder 模式批量保存设置
     *
     * 使用示例:
     * ```
     * settings.save {
     *     packageFilterEnabled(true)
     *     targetPackages("com.tencent.mm, com.eg.android.AlipayGphone")
     * }
     * ```
     *
     * @param block 设置变更的 lambda
     */
    fun save(block: SettingsBuilder.() -> Unit) {
        val builder = SettingsBuilder()
        builder.block()
        prefs.edit().apply {
            builder.treeOutputEnabled?.let { putBoolean(KEY_TREE_OUTPUT_ENABLED, it) }
            builder.eventSummaryEnabled?.let { putBoolean(KEY_EVENT_SUMMARY_ENABLED, it) }
            builder.wechatEnabled?.let { putBoolean(KEY_APP_WECHAT_ENABLED, it) }
            builder.alipayEnabled?.let { putBoolean(KEY_APP_ALIPAY_ENABLED, it) }
            builder.meituanEnabled?.let { putBoolean(KEY_APP_MEITUAN_ENABLED, it) }
            apply()
        }
    }

    /**
     * 设置构建器 — 仅设置传入的项，未设置的项不修改
     */
    class SettingsBuilder {
        internal var treeOutputEnabled: Boolean? = null
        internal var eventSummaryEnabled: Boolean? = null
        internal var wechatEnabled: Boolean? = null
        internal var alipayEnabled: Boolean? = null
        internal var meituanEnabled: Boolean? = null

        fun treeOutputEnabled(enabled: Boolean) { this.treeOutputEnabled = enabled }
        fun eventSummaryEnabled(enabled: Boolean) { this.eventSummaryEnabled = enabled }
        fun wechatEnabled(enabled: Boolean) { this.wechatEnabled = enabled }
        fun alipayEnabled(enabled: Boolean) { this.alipayEnabled = enabled }
        fun meituanEnabled(enabled: Boolean) { this.meituanEnabled = enabled }
    }
}
