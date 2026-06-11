package com.bjbyhd.screenreader_huawei.biller.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 自动化记账 — 通知栏监听服务
 *
 * 职责: 接收系统通知回调，传递给 [BillEventProcessor]。
 *       不做解析、去重、持久化。
 */
class BillerNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        BillEventProcessor.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
