package com.bjbyhd.screenreader_huawei.biller.callback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord

/**
 * 悬浮窗显示模式
 *
 * - [CHECKMARK]: 支付成功页面 → 显示绿色 ✓ 对号（可展开编辑）
 * - [PLUS]: 账单小程序页面 → 显示 + 按钮（预留，待开发）
 */
enum class OverlayMode { CHECKMARK, PLUS }

/**
 * 悬浮窗服务 — 管理 WindowManager 悬浮层
 *
 * P0（当前）: 仅类骨架 + Manifest 注册，不实现实际悬浮窗逻辑。
 *            [show] 方法预留给 [CaptureNotifier] 等 caller 调用。
 * P2: 实现收缩态 ✓ 圆形动画 + 展开态编辑卡片。
 *
 * 为什么是独立 Service: AccessibilityService 可能被系统随时重建，
 *                      独立 Service 保证 View 和点击回调的 Context 稳定。
 */
class FloatingOverlayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // P2: 初始化 WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // P2: 解析 intent 中的 BillRecord 和 OverlayMode，显示对应悬浮窗
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // P2: 移除所有悬浮窗 View
        super.onDestroy()
    }

    companion object {
        /**
         * 触发悬浮窗显示（P2 实现）
         *
         * @param context 调用方 Context
         * @param record  关联的账单记录
         * @param mode    显示模式
         */
        fun show(context: Context, record: BillRecord, mode: OverlayMode) {
            // P2: startService 并传入 record + mode
        }
    }
}
