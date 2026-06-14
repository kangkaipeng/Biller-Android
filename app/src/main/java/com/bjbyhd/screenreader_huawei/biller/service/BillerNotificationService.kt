package com.bjbyhd.screenreader_huawei.biller.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 自动化记账 — 通知栏监听服务
 *
 * 职责: 接收系统通知回调，传递给 [BillEventProcessor]。
 *       不做解析、去重、持久化。
 */
class BillerNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "Biller/NotifySvc"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        CLog.i(TAG) {
            buildString {
                appendLine("通知栏监听服务已连接")
                appendLine("  监听目标(${TargetConfig.TARGET_PACKAGES.size}): ${TargetConfig.TARGET_PACKAGES.joinToString(", ")}")
                val pkgKeywords = TargetConfig.TARGET_PACKAGES.associateWith { pkg ->
                    TargetConfig.PAYMENT_KEYWORDS[pkg]?.joinToString(", ") ?: "无"
                }
                appendLine("  支付关键字:")
                pkgKeywords.forEach { (pkg, keywords) ->
                    appendLine("    $pkg → [$keywords]")
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        BillEventProcessor.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
