package com.bjbyhd.screenreader_huawei.biller.callback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.ui.main.BillDashboardActivity
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 账单捕获通知器 — 无障碍源捕获到新交易记录时发送通知栏通知
 *
 * 职责:
 *   1. 构建并发送通知（Channel: bill_capture）
 *   2. 10 秒后自动取消通知
 *
 * 通知策略: 仅无障碍源 + 非 Skipped 时调用（由 [BillEventProcessor] 决策）。
 *
 * 初始化: [BillerApplication.onCreate] 中调用 [init]
 */
object CaptureNotifier {

    private const val TAG = "CaptureNotify"

    @Suppress("StaticFieldLeak")
    private var appContext: Context? = null    // 始终持有ApplicationContext，可安全持有
    private var nm: NotificationManager? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        nm = appContext!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * 有新账单记录保存 — 发送通知栏通知
     *
     * @param record 完整的 BillRecord（已含 id）
     */
    fun onBillSaved(record: BillRecord) {
        val context = appContext ?: return
        val notifMgr = nm ?: return

        val channelDisplay = when (record.paymentChannel) {
            "WEIXIN" -> "微信"
            "ALIPAY" -> "支付宝"
            else -> record.paymentChannel
        }
        val amountStr = formatAmount(record.amount)
        val merchantStr = record.merchant ?: "未知商户"

        val intent = Intent(context, BillDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, record.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.BILL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💳 新账单已记录")
            .setContentText("$amountStr $merchantStr — $channelDisplay")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$amountStr $merchantStr — ${channelDisplay}扫码支付")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notifMgr.notify(record.id.toInt(), notification)
        CLog.i(TAG) { "[通知] id=${record.id} amount=$amountStr merchant=$merchantStr" }

    }

    private fun formatAmount(amount: Double?): String {
        return if (amount == null || amount == 0.0) "¥0.00"
        else String.format("¥%.2f", amount)
    }
}
