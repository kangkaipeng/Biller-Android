package com.bjbyhd.screenreader_huawei.biller.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 自动化记账 — 无障碍监听服务
 *
 * 职责: 接收系统无障碍回调，过滤系统包后传递给 [BillEventProcessor]。
 *       不做解析、去重、持久化。
 *
 * 配置: res/xml/accessibility_config.xml
 */
class BillerAccessibilityService : AccessibilityService() {

    companion object {
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in SYSTEM_PACKAGES) return
        BillEventProcessor.onAccessibilityEvent(pkg, event, rootInActiveWindow)
    }

    override fun onInterrupt() {}
}
