package com.bjbyhd.screenreader_huawei.biller.callback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 通知渠道注册中心 — App 启动时统一注册所有 NotificationChannel
 *
 * 职责:
 *   1. 集中管理所有 NotificationChannel ID 常量
 *   2. [init] 中统一注册，重复调用安全（已存在则跳过）
 *
 * 扩展方式: 新增通知类型时在此添加 channel 常量 + [init] 中注册。
 */
object NotificationChannels {

    /** 账单捕获通知 — 无障碍服务捕获到新交易记录时触发 */
    const val BILL_CAPTURE = "bill_capture"

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    BILL_CAPTURE,
                    "账单捕获通知",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "无障碍服务捕获到新交易记录时发送通知"
                }
            )
        }

        initialized = true
    }
}
