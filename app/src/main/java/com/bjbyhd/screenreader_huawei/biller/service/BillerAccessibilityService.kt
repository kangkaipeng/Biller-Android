package com.bjbyhd.screenreader_huawei.biller.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 自动化记账 — 无障碍监听服务
 *
 * 职责: 接收系统无障碍回调，过滤系统包后传递给 [BillEventProcessor]。
 *       不做解析、去重、持久化。
 *
 * 配置: res/xml/accessibility_config.xml
 *   - 事件类型: typeWindowContentChanged | typeWindowStateChanged
 *   - 监听范围: 所有应用（包名过滤在 Kotlin 层）
 */
class BillerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Biller/A11ySvc"

        /** 系统包 — 不产生支付事件，直接丢弃 */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.huawei.android.launcher",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.android.phone",
            "android",
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CLog.i(TAG) {
            buildString {
                appendLine("无障碍服务已连接")
                appendLine("  事件类型: typeWindowContentChanged | typeWindowStateChanged")
                appendLine("  监听目标(${TargetConfig.TARGET_PACKAGES.size}): ${TargetConfig.TARGET_PACKAGES.joinToString(", ")}")
                appendLine("  系统包过滤(${SYSTEM_PACKAGES.size}): ${SYSTEM_PACKAGES.joinToString(", ")}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in SYSTEM_PACKAGES) return
        BillEventProcessor.onAccessibilityEvent(pkg, event, rootInActiveWindow)
    }

    override fun onInterrupt() {}

    /*
     * 已知 LeakCanary 报告:
     *   AccessibilityService$IAccessibilityServiceClientWrapper (native 层) → mContext
     *   → BillerAccessibilityService 实例泄漏 (~1.8 kB)。
     *
     * 这是 Android 无障碍框架在华为 EMUI (API 31) 上的框架层泄漏:
     *   - GC Root 是 native code 的全局变量
     *   - Service onDestroy() 后系统未及时清除 IAccessibilityServiceClientWrapper 引用
     *   - 泄漏量固定 1.8 kB/18 objects，不累积
     *   - 进程重启后自动回收
     *
     * 不建议调用 disableSelf(): 会永久禁用无障碍服务，用户需手动重新开启。
     * 此泄漏为已知框架问题，可安全忽略。若需消除 LeakCanary 告警，
     * 在 LeakCanary 配置中添加对此引用链的 exclusion rule。
     */
}
