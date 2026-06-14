package com.bjbyhd.screenreader_huawei.biller.overlay

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 悬浮窗控制器抽象基类 — 管理 WindowManager 生命周期。
 *
 * ## 职责
 *   - WindowManager.addView / removeView / updateViewLayout
 *   - 拖拽手势（长按 → 移动 → 松手吸附边缘）
 *   - 位置持久化到 SharedPreferences
 *   - 定义子类必须实现的 [createRootView] 和 [bindViewModel]
 *
 * ## 子类
 *   - [CheckmarkOverlay]: CHECKMARK 模式（支付成功 ✓）
 *   - PlusOverlay (P4): PLUS 模式（账单列表 +）
 *
 * ## Window 参数
 *   收缩态: TYPE_APPLICATION_OVERLAY, NOT_FOCUSABLE | WATCH_OUTSIDE_TOUCH
 *   展开态: TYPE_APPLICATION_OVERLAY, FLAG_DIM_BEHIND (dimAmount=0.4)
 */
abstract class OverlayController(
    protected val service: Service,
) {
    protected val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    protected var rootView: FrameLayout? = null
    protected var isShowing = false

    /** 当前收缩态屏幕坐标 — 子类展开/收缩时使用，拖拽后自动更新 */
    protected var collapsedX: Float = 0f
    protected var collapsedY: Float = 0f

    private val prefs: SharedPreferences =
        service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════════
    // 子类实现
    // ═══════════════════════════════════════════════════════════════

    /** 构建完整的 View 树（包含收缩态 + 展开态子 View）。调用时机: 首次 show() */
    abstract fun createRootView(): FrameLayout

    /**
     * 绑定 ViewModel，启动 StateFlow 收集循环。
     * 调用时机: show() 中 createRootView() 之后。
     */
    abstract fun bindViewModel(viewModel: OverlayViewModel)

    // ═══════════════════════════════════════════════════════════════
    // 公共方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 显示悬浮窗。
     * 幂等: 如果已在显示中，不重复 addView（由调用方先更新 ViewModel 状态）。
     *
     * @param viewModel MVI ViewModel 实例
     * @param position  初始位置 (屏幕坐标)，null = 使用持久化位置
     */
    open fun show(viewModel: OverlayViewModel, position: PointF?) {
        if (isShowing) return

        rootView = createRootView()
        bindViewModel(viewModel)

        val rawPos = position ?: loadPosition()
        // 坐标超出合理范围 → 使用右下角默认位置（首次启动或屏幕尺寸变化）
        val pos = if (rawPos.x < 0 || rawPos.y < 0 || rawPos.x > 10000 || rawPos.y > 10000) {
            defaultPosition()
        } else rawPos

        windowManager.addView(rootView, buildCollapsedParams(pos.x, pos.y))
        collapsedX = pos.x
        collapsedY = pos.y
        isShowing = true
        CLog.i(TAG) { "悬浮窗已显示: collapsed (${pos.x}, ${pos.y})" }
    }

    /** 移除悬浮窗。幂等——可安全重复调用。 */
    open fun dismiss() {
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
        isShowing = false
        CLog.i(TAG) { "悬浮窗已关闭" }
    }

    /**
     * 将当前 Window LayoutParams 中的坐标快照到 [collapsedX] / [collapsedY]。
     * 子类在展开前调用，以便收缩时恢复。
     */
    protected fun saveCurrentPosition() {
        val params = rootView?.layoutParams as? WindowManager.LayoutParams ?: return
        collapsedX = params.x.toFloat()
        collapsedY = params.y.toFloat()
    }

    // ═══════════════════════════════════════════════════════════════
    // Window LayoutParams
    // ═══════════════════════════════════════════════════════════════

    protected fun buildCollapsedParams(x: Float, y: Float): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.toInt()
            this.y = y.toInt()
        }
    }

    protected fun buildExpandedParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.4f
            gravity = Gravity.TOP or Gravity.START
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 拖拽
    // ═══════════════════════════════════════════════════════════════

    /**
     * 为目标 View 启用长按拖拽。
     * 长按 300ms 进入拖拽模式 → 移动更新 LayoutParams → 松手吸附边缘 + 持久化。
     */
    protected fun enableDrag(target: View) {
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        var isDragging = false
        var startX = 0f
        var startY = 0f
        var initialParamsX = 0
        var initialParamsY = 0

        target.setOnTouchListener { view, event ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialParamsX = params.x
                    initialParamsY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialParamsX + dx.toInt()
                        params.y = initialParamsY + dy.toInt()
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 吸附边缘
                        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
                        val centerX = params.x + view.width / 2
                        params.x = if (centerX < screenWidth / 2) 0 else screenWidth - view.width
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                        savePosition(params.x.toFloat(), params.y.toFloat())
                        collapsedX = params.x.toFloat()
                        collapsedY = params.y.toFloat()
                        isDragging = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 位置持久化
    // ═══════════════════════════════════════════════════════════════

    private fun loadPosition(): PointF {
        val x = prefs.getFloat(KEY_X, -1f)
        val y = prefs.getFloat(KEY_Y, -1f)
        return PointF(x, y)
    }

    private fun savePosition(x: Float, y: Float) {
        prefs.edit().putFloat(KEY_X, x).putFloat(KEY_Y, y).apply()
    }

    /**
     * 计算右下角默认位置。
     * 右侧留 16dp，底部留 100dp（避开导航栏 + 无障碍提示）。
     */
    private fun defaultPosition(): PointF {
        val metrics = windowManager.currentWindowMetrics
        val density = service.resources.displayMetrics.density
        val x = metrics.bounds.width() - (40 * density).toInt() - (16 * density).toInt()
        val y = metrics.bounds.height() - (40 * density).toInt() - (100 * density).toInt()
        return PointF(x.toFloat(), y.toFloat())
    }

    companion object {
        private const val TAG = "Biller/OverlayCtrl"
        private const val PREFS_NAME = "overlay_position"
        private const val KEY_X = "collapsed_x"
        private const val KEY_Y = "collapsed_y"
    }
}
