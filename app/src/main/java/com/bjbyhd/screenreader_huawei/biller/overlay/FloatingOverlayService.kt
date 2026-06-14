package com.bjbyhd.screenreader_huawei.biller.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 悬浮窗服务 — 管理 WindowManager 悬浮层 + MVI 状态。
 *
 * ## 为什么是独立 Service
 *   [AccessibilityService] 可能被系统随时重建，独立 Service 保证
 *   View 和点击回调的 Context 稳定。
 *
 * ## 生命周期
 *   ```
 *   show(context, bill) → startService(intent)
 *     → onCreate()           ← 初始化 WindowManager + ViewModel + Overlay
 *     → onStartCommand()     ← 解析 Intent → ViewModel.onEvent(ShowSingle) → overlay.show()
 *     → (用户交互)
 *     → onDestroy()          ← overlay.dismiss()
 *   ```
 *
 * ## 多次调用
 *   同一 Service 实例收到第二次 show() 时:
 *   - onStartCommand 再次调用
 *   - ViewModel 更新状态为新账单
 *   - overlay 已在显示 → OverlayController.show() 幂等跳过 addView
 *   - 用户看到新账单内容
 */
class FloatingOverlayService : Service() {

    private var viewModel: OverlayViewModel? = null
    private var overlay: CheckmarkOverlay? = null
    private var hasOverlayPermission: Boolean = false

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()

        hasOverlayPermission = Settings.canDrawOverlays(this)

        // ViewModel 始终初始化（纯内存对象，无权限依赖）
        viewModel = OverlayViewModel()

        if (hasOverlayPermission) {
            overlay = CheckmarkOverlay(this)
            CLog.i(TAG) { "悬浮窗服务已启动 (有悬浮窗权限)" }
        } else {
            // 无障碍服务理论上自动获得此权限，但部分 ROM（如华为）可能拦截。
            // 引导用户去设置页开启。
            CLog.w(TAG) { "缺少悬浮窗权限 (SYSTEM_ALERT_WINDOW)，悬浮窗功能不可用。请手动授予权限。" }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val bill = parseIntent(intent) ?: run {
            CLog.d(TAG) { "Intent 解析失败 — 缺少必要字段" }
            return START_NOT_STICKY
        }

        CLog.i(TAG) {
            "收到账单: amount=${bill.amount} merchant=${bill.merchant ?: "?"}"
        }

        if (!hasOverlayPermission) {
            CLog.w(TAG) { "无悬浮窗权限，跳过显示" }
            return START_NOT_STICKY
        }

        viewModel?.onEvent(OverlayEvent.ShowSingle(bill))
        overlay?.show(viewModel!!, null)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay?.dismiss()
        overlay = null
        viewModel = null
        CLog.i(TAG) { "悬浮窗服务已销毁" }
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // 权限引导
    // ═══════════════════════════════════════════════════════════════

    /**
     * 引导用户去「在其他应用上层显示内容」设置页。
     * 外部可调用此方法引导授权（如从"我的"页面触发）。
     */
    fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        startActivity(intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Intent 解析
    // ═══════════════════════════════════════════════════════════════

    private fun parseIntent(intent: Intent): ParsedBill? {
        val amount = if (intent.hasExtra(EXTRA_AMOUNT)) {
            intent.getDoubleExtra(EXTRA_AMOUNT, Double.NaN).takeIf { !it.isNaN() }
        } else null
        val merchant = intent.getStringExtra(EXTRA_MERCHANT)
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return null
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)

        return ParsedBill(
            packageName = "",
            rawTitle = "",
            rawText = "",
            amount = amount,
            merchant = merchant,
            paymentChannel = channel,
            timestamp = timestamp,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 外部触发
    // ═══════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "Biller/FloatSvc"

        private const val EXTRA_MODE = "overlay_mode"
        private const val EXTRA_AMOUNT = "amount"
        private const val EXTRA_MERCHANT = "merchant"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_TIMESTAMP = "timestamp"

        /**
         * 触发悬浮窗显示 — 通过 startService 传递账单数据。
         *
         * 线程安全: [Context.startService] 可在任意线程调用。
         *
         * @param context 调用方 Context（建议传 ApplicationContext）
         * @param record  关联的账单记录
         * @param mode    显示模式 (CHECKMARK / PLUS)
         */
        fun show(context: Context, record: BillRecord, mode: OverlayMode) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_AMOUNT, record.amount)
                putExtra(EXTRA_MERCHANT, record.merchant)
                putExtra(EXTRA_CHANNEL, record.paymentChannel)
                putExtra(EXTRA_TIMESTAMP, record.timestamp)
            }
            context.startService(intent)
        }
    }
}
