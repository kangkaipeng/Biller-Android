package com.bjbyhd.screenreader_huawei.biller.parser.alipay

import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 支付宝解析器 — 统一对外入口
 *
 * 职责: 接收监听层的原始事件，内部按来源分发给子提取器:
 *   - 无障碍事件 → [AlipayScreenExtractor]
 *   - 通知事件   → [AlipayNotificationExtractor]
 */
object AlipayParser {

    private const val TAG = "Biller/Alipay"

    fun parseAccessibility(rootNode: AccessibilityNodeInfo, receivedAt: Long): ParsedBill? {
        CLog.i(TAG) { "[Alipay] parseAccessibility: 开始 → rootNode.className=${rootNode.className}" }
        val result = AlipayScreenExtractor.parse(rootNode, receivedAt)
        CLog.i(TAG) { "[Alipay] parseAccessibility: 完成 → amount=${result?.amount} merchant=${result?.merchant}" }
        return result
    }

    fun parseNotification(sbn: StatusBarNotification): ParsedBill? {
        val pkg = sbn.packageName
        val title = sbn.notification.extras?.getString("android.title") ?: ""
        val text = sbn.notification.extras?.getString("android.text") ?: ""
        val timestamp = sbn.postTime

        CLog.i(TAG) { "[Alipay] parseNotification: 开始 → title=${title.take(20)} text=${text.take(30)}" }

        if (!AlipayNotificationExtractor.isTargetData(pkg, title, text)) {
            CLog.d(TAG) { "[Alipay] parseNotification: isTargetData=false — 非支付通知" }
            return null
        }

        val result = AlipayNotificationExtractor.parse(pkg, title, text, timestamp)
        CLog.i(TAG) { "[Alipay] parseNotification: 完成 → amount=${result?.amount} merchant=${result?.merchant}" }
        return result
    }
}
