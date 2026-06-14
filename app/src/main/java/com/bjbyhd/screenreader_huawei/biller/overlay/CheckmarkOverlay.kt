package com.bjbyhd.screenreader_huawei.biller.overlay

import android.app.Service
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.bjbyhd.screenreader_huawei.biller.R
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * CHECKMARK 悬浮窗 — 支付成功 ✓ 引导。
 *
 * ## 形态
 *   - **收缩态**: 40dp 绿色圆形 FAB，显示 "✓"，可拖拽
 *   - **展开态**: 280dp 白色卡片 (inflate [R.layout.overlay_checkmark_expanded])，
 *     显示金额/商户/分类/别名，半透明 dim 背景
 *
 * ## MVI 绑定
 *   观察 [OverlayViewModel.uiState]，根据 [OverlayUiState.phase] 切换形态。
 *   用户交互通过 [OverlayEvent] 回传给 ViewModel。
 *
 * ## 生命周期
 *   由 [OverlayController.show] 创建 View 树并绑定 ViewModel，
 *   由 [OverlayController.dismiss] 移除 View。
 */
class CheckmarkOverlay(
    service: Service,
) : OverlayController(service) {

    // ═══════════════════════════════════════════════════════════════
    // View 引用
    // ═══════════════════════════════════════════════════════════════

    private var dimBackground: View? = null
    private var collapsedView: FrameLayout? = null
    private var expandedView: MaterialCardView? = null

    // 展开态子 View
    private var tvHeaderTitle: TextView? = null
    private var tvAmount: TextView? = null
    private var tvMerchant: TextView? = null
    private var tvPaymentMethod: TextView? = null
    private var llPaymentMethod: ViewGroup? = null
    private var tvDiscount: TextView? = null
    private var llDiscount: ViewGroup? = null
    private var tvCategory: TextView? = null
    private var tvAlias: TextView? = null

    // ═══════════════════════════════════════════════════════════════
    // 实现
    // ═══════════════════════════════════════════════════════════════

    override fun createRootView(): FrameLayout {
        val root = FrameLayout(service).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        // ── Dim 背景 ──
        dimBackground = View(service).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.argb(102, 0, 0, 0))  // 40% black
            visibility = View.GONE
        }
        root.addView(dimBackground)

        // ── 收缩态: 40dp 绿色圆形 ──
        collapsedView = createCollapsedView()
        root.addView(collapsedView)

        // ── 展开态: Inflate XML ──
        expandedView = LayoutInflater.from(service)
            .inflate(R.layout.overlay_checkmark_expanded, root, false) as MaterialCardView
        expandedView?.visibility = View.GONE
        bindExpandedFields()
        root.addView(expandedView)

        return root
    }

    override fun bindViewModel(viewModel: OverlayViewModel) {
        // 持有引用 + 设置点击回调
        viewModelRef = viewModel
        collapsedView?.setOnClickListener { viewModel.onEvent(OverlayEvent.ToggleExpand) }
        dimBackground?.setOnClickListener { viewModel.onEvent(OverlayEvent.ToggleExpand) }

        // 启动 StateFlow 收集循环
        MainScope().launch(Dispatchers.Main) {
            viewModel.uiState.collect { state -> render(state) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 渲染
    // ═══════════════════════════════════════════════════════════════

    private fun render(state: OverlayUiState) {
        CLog.d(TAG) { "render: phase=${state.phase}" }

        when (state.phase) {
            OverlayPhase.HIDDEN -> {
                dismiss()
                service.stopSelf()
            }
            OverlayPhase.COLLAPSED -> {
                showCollapsed()
                updateWindowParams(buildCollapsedParams(collapsedX, collapsedY))
            }
            OverlayPhase.EXPANDED -> {
                saveCurrentPosition()
                bindBillData(state.bill)
                showExpanded()
                updateWindowParams(buildExpandedParams())
            }
        }
    }

    private fun showCollapsed() {
        dimBackground?.visibility = View.GONE
        collapsedView?.visibility = View.VISIBLE
        expandedView?.visibility = View.GONE
    }

    private fun showExpanded() {
        dimBackground?.visibility = View.VISIBLE
        collapsedView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE
    }

    private fun bindBillData(bill: ParsedBill?) {
        if (bill == null) return

        tvHeaderTitle?.text = bill.rawTitle.ifEmpty {
            when (bill.paymentChannel) {
                "ALIPAY" -> "支付成功"
                else -> "支付成功"
            }
        }
        tvAmount?.text = bill.amount?.let {
            String.format("¥ %.2f", it)
        } ?: "—"

        tvMerchant?.text = bill.merchant ?: "—"

        // 支付方式（仅当有数据时显示）
        if (bill.paymentMethod != null) {
            tvPaymentMethod?.text = bill.paymentMethod
            llPaymentMethod?.visibility = View.VISIBLE
        } else {
            llPaymentMethod?.visibility = View.GONE
        }

        // 优惠信息（仅支付宝有）
        if (bill.discountInfo != null && bill.discountInfo.isNotEmpty()) {
            tvDiscount?.text = bill.discountInfo
            llDiscount?.visibility = View.VISIBLE
        } else {
            llDiscount?.visibility = View.GONE
        }

        // 分类 & 别名 — P3 编辑功能接入前为只读
        tvCategory?.text = "未分类"
        tvAlias?.text = "—"
    }

    // ═══════════════════════════════════════════════════════════════
    // Window 参数切换
    // ═══════════════════════════════════════════════════════════════

    private fun updateWindowParams(params: WindowManager.LayoutParams) {
        rootView?.let {
            try { windowManager.updateViewLayout(it, params) } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 收缩态 View 构建
    // ═══════════════════════════════════════════════════════════════

    private fun createCollapsedView(): FrameLayout {
        val size = (40 * service.resources.displayMetrics.density).toInt()

        val circle = FrameLayout(service).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                val margin = (16 * service.resources.displayMetrics.density).toInt()
                setMargins(0, 0, margin, margin)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
                setStroke(2, Color.parseColor("#43A047"))
            }
            elevation = 8f * service.resources.displayMetrics.density
        }

        val checkmark = TextView(service).apply {
            text = "✓"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        circle.addView(checkmark)

        // 拖拽手势
        enableDrag(circle)

        return circle
    }

    /** 在 bindViewModel 中设置，供展开态按钮回调使用 */
    private var viewModelRef: OverlayViewModel? = null

    // ═══════════════════════════════════════════════════════════════
    // 展开态字段绑定
    // ═══════════════════════════════════════════════════════════════

    private fun bindExpandedFields() {
        val card = expandedView ?: return

        tvHeaderTitle = card.findViewById(R.id.tv_header_title)
        tvAmount = card.findViewById(R.id.tv_amount)
        tvMerchant = card.findViewById(R.id.tv_merchant)
        tvPaymentMethod = card.findViewById(R.id.tv_payment_method)
        llPaymentMethod = card.findViewById(R.id.ll_payment_method)
        tvDiscount = card.findViewById(R.id.tv_discount)
        llDiscount = card.findViewById(R.id.ll_discount)
        tvCategory = card.findViewById(R.id.tv_category)
        tvAlias = card.findViewById(R.id.tv_alias)

        // 关闭按钮
        card.findViewById<TextView>(R.id.btn_close)?.setOnClickListener {
            viewModelRef?.onEvent(OverlayEvent.ToggleExpand)
        }

        // "完成" 按钮 → 关闭悬浮窗
        card.findViewById<TextView>(R.id.btn_done)?.setOnClickListener {
            viewModelRef?.onEvent(OverlayEvent.Dismiss)
        }

        // "编辑" 按钮 → P3 实现
        card.findViewById<TextView>(R.id.btn_edit)?.setOnClickListener {
            CLog.d(TAG) { "编辑按钮 — P3 实现" }
        }
    }

    companion object {
        private const val TAG = "Biller/ChkOverlay"
    }
}
