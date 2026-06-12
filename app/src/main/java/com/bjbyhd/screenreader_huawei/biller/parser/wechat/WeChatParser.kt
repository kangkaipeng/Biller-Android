package com.bjbyhd.screenreader_huawei.biller.parser.wechat

import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 微信解析器 — 统一对外入口
 *
 * 职责: 接收监听层的原始事件，内部按来源分发给子提取器:
 *   - 无障碍事件 → [WeChatScreenExtractor]
 *   - 通知事件   → [WeChatNotificationExtractor]
 *
 * 数据流日志:
 *   入口 → 分发 → 子提取器 → 结果汇总
 */
object WeChatParser {

    private const val TAG = "Biller/WeChat"

    /**
     * 解析无障碍事件（微信支付成功页）
     *
     * @return 解析成功返回 [ParsedBill]，页面不匹配或金额缺失返回 null
     */
    fun parseAccessibility(rootNode: AccessibilityNodeInfo, receivedAt: Long): ParsedBill? {
        CLog.i(TAG) { "[WeChat] parseAccessibility: 开始 → rootNode.className=${rootNode.className}" }
//        AccessibilityTreeDumper.dump(rootNode, TAG)
        val result = WeChatScreenExtractor.parse(rootNode, receivedAt)
        CLog.i(TAG) { "[WeChat] parseAccessibility: 完成 → amount=${result?.amount} merchant=${result?.merchant}" }
        return result
    }

    /**
     * 解析通知栏事件（微信支付通知）
     *
     * @return 解析成功返回 [ParsedBill]，非支付通知或金额缺失返回 null
     */
    fun parseNotification(sbn: StatusBarNotification): ParsedBill? {
        val pkg = sbn.packageName
        val title = sbn.notification.extras?.getString("android.title") ?: ""
        val text = sbn.notification.extras?.getString("android.text") ?: ""
        val timestamp = sbn.postTime

        CLog.i(TAG) { "[WeChat] parseNotification: 开始 → title=${title.take(20)} text=${text.take(30)}" }

        if (!WeChatNotificationExtractor.isTargetData(pkg, title, text)) {
            CLog.d(TAG) { "[WeChat] parseNotification: isTargetData=false — 非支付通知" }
            return null
        }

        val result = WeChatNotificationExtractor.parse(pkg, title, text, timestamp)
        CLog.i(TAG) { "[WeChat] parseNotification: 完成 → amount=${result?.amount} merchant=${result?.merchant}" }
        return result
    }
}
