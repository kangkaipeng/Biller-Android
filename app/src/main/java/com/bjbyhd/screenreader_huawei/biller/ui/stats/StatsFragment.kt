package com.bjbyhd.screenreader_huawei.biller.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bjbyhd.screenreader_huawei.biller.R
import com.bjbyhd.screenreader_huawei.biller.databinding.FragmentStatsBinding
import com.bjbyhd.screenreader_huawei.biller.ui.common.ViewModelFactory
import com.bjbyhd.screenreader_huawei.biller.ui.common.showMonthPickerDialog
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统计 Tab Fragment — MVI View 层 (Phase 5D)
 *
 * 模块: feature/biller/ui/stats
 * 职责:
 *   - 收集 [StatsViewModel.uiState] StateFlow，驱动完整 UI 渲染
 *   - 收集 [StatsViewModel.effect] Channel，处理跨 Tab 导航副作用
 *   - 月份切换、分类/交易点击 → 发射 [StatsEvent]
 *
 * ## MVI 数据流
 *
 * ```
 * StatsFragment ──onEvent──→ StatsViewModel ──combine(Flows)──→ StateFlow
 *      ↑                                                           │
 *      └───────────────── collect(uiState) ────────────────────────┘
 *      ↑
 *      └───────────────── collect(effect) ──→ 跨 Tab 导航
 * ```
 *
 * ## 渲染区域
 *
 *   1. 月份切换栏 → tvMonthLabel
 *   2. 汇总卡片   → tvTotalExpense / tvTotalIncome / tvExpenseCount / tvNetAmount
 *   3. 环比趋势   → cardMom / tvMomRate / tvMomAmount
 *   4. 分类分布   → layoutCategoryList（动态创建行）
 *   5. 最近交易   → layoutRecentBills（动态创建行）
 *
 * ## 生命周期
 *
 * 使用 [repeatOnLifecycle] + [Lifecycle.State.STARTED] 收集 Flow，
 * 在 Fragment hide() 时自动暂停收集，show() 时恢复。
 */
class StatsFragment : Fragment() {

    companion object {
        private const val TAG = "Biller/StatsFrag"
        private val TIME_FORMAT = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    }

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: StatsViewModel

    /** 缓存最新 UiState，供事件回调中读取（避免直接读 StateFlow.value） */
    private var currentState: StatsUiState = StatsUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = ViewModelFactory(requireContext())
        viewModel = ViewModelProvider(this, factory)[StatsViewModel::class.java]

        setupMonthButtons()
        observeUiState()
    }

    // ═══════════ 月份选择 — DatePicker ═══════════

    /** 绑定月份标签点击 → 弹出 MaterialDatePicker */
    private fun setupMonthButtons() {
        binding.layoutMonthSelector.setOnClickListener {
            showMonthPicker()
        }
    }

    /**
     * 弹出双 NumberPicker 月份选择器 (v4.6)
     */
    private fun showMonthPicker() {
        showMonthPickerDialog(
            context = requireContext(),
            initialYearMonth = currentState.selectedYearMonth,
        ) { pickedYm ->
            viewModel.onEvent(StatsEvent.SelectMonth(pickedYm))
        }
    }

    // ═══════════ 状态 & 副作用观察 ═══════════

    /**
     * 并行收集 StateFlow（UI 渲染）和 Effect Channel（导航副作用）
     *
     * ## 为什么用两个独立的 launch
     *
     * State 和 Effect 是两条独立的数据流。将它们放在两个子协程中并行收集，
     * 确保其中一个的消费速度不会阻塞另一个。例如，导航 Effect 不应被
     * 复杂的 UI 渲染所延迟。
     */
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 并行: State 渲染
                launch {
                    viewModel.uiState.collect { state ->
                        currentState = state
                        renderUi(state)
                    }
                }
                // 并行: Effect 消费
                launch {
                    viewModel.effect.collect { effect ->
                        handleEffect(effect)
                    }
                }
            }
        }
    }

    // ═══════════ 全局 UI 渲染入口 ═══════════

    /**
     * 根据 [StatsUiState] 驱动整个页面的可见性和数据填充
     *
     * 三态切换:
     *   - isLoading  → 显示加载提示，隐藏内容和空状态
     *   - 无数据      → 显示空状态引导
     *   - 有数据      → 显示完整内容
     */
    private fun renderUi(state: StatsUiState) {
        val hasData = state.totalExpense > 0 || state.totalIncome > 0

        // 三态可见性切换
        binding.layoutLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.layoutContent.visibility = if (!state.isLoading && hasData) View.VISIBLE else View.GONE
        binding.layoutEmpty.visibility   = if (!state.isLoading && !hasData) View.VISIBLE else View.GONE

        // 月份标签始终更新——即使用户切换到无数据的月份，也需要看到当前选了哪个月
        renderMonthHeader(state)

        // 无数据或加载中时跳过数据区域渲染
        if (!hasData || state.isLoading) return

        // 逐区域渲染数据内容
        renderSummaryCard(state)
        renderMoMCard(state)
        renderCategoryBreakdown(state)
        renderRecentBills(state)
    }

    // ═══════════ 区域渲染 — 月份标签 ═══════════

    private fun renderMonthHeader(state: StatsUiState) {
        val ym = state.selectedYearMonth
        binding.tvMonthLabel.text = "${ym.year}年${ym.monthValue}月"
    }

    // ═══════════ 区域渲染 — 汇总卡片 ═══════════

    private fun renderSummaryCard(state: StatsUiState) {
        with(binding) {
            tvTotalExpense.text = "¥${String.format("%.2f", state.totalExpense)}"
            tvTotalIncome.text  = "¥${String.format("%.2f", state.totalIncome)}"
            tvExpenseCount.text = "${state.expenseCount} 笔"
            tvIncomeCount.text  = "${state.incomeCount} 笔"
            tvNetAmount.text    = "¥${String.format("%.2f", state.netAmount)}"
        }
    }

    // ═══════════ 区域渲染 — 环比趋势 ═══════════

    /**
     * 渲染月度环比趋势
     *
     * 颜色逻辑:
     *   - 支出增加（正变化）→ 红色（花了更多钱，警示色调）
     *   - 支出减少（负变化）→ 绿色（更节省，积极色调）
     *
     * 无上月数据时（monthOverMonthRate == null）隐藏环比卡片。
     */
    private fun renderMoMCard(state: StatsUiState) {
        val rate = state.monthOverMonthRate
        if (rate == null) {
            binding.cardMom.visibility = View.GONE
            return
        }

        binding.cardMom.visibility = View.VISIBLE

        val isIncreased = rate >= 0f
        // 支出增加→红色警示；支出减少→绿色积极
        val color = if (isIncreased) Color.parseColor("#F44336") else Color.parseColor("#4CAF50")
        val arrow = if (isIncreased) "▲" else "▼"
        val sign = if (isIncreased) "+" else ""

        binding.tvMomArrow.setTextColor(color)
        binding.tvMomArrow.text = arrow
        binding.tvMomRate.setTextColor(color)
        binding.tvMomRate.text = "${sign}${String.format("%.1f", rate * 100)}%"

        // 变化额
        val changeAmount = state.totalExpense - (state.lastMonthExpense ?: 0.0)
        val amountSign = if (changeAmount >= 0) "+" else ""
        binding.tvMomAmount.text = "${amountSign}¥${String.format("%.2f", changeAmount)}"
    }

    // ═══════════ 区域渲染 — 分类分布 ═══════════

    /**
     * 动态构建分类分布列表
     *
     * 每个分类行包含:
     *   [emoji] 分类名    金额    百分比进度条
     *
     * 使用动态 View 创建而非 RecyclerView——分类数量通常 ≤ 15，
     * 不需要回收复用机制。直接操作 LinearLayout 更简单。
     *
     * ## 进度条实现
     *
     * 在每个分类行中使用两层嵌套 View:
     *   外层 FrameLayout (固定宽度, 圆角背景) → 内层 View (动态宽度, 表示百分比)
     * 百分比宽度 = percentage * 外层总宽，最小宽度保证 0% 时也有可见指示。
     */
    private fun renderCategoryBreakdown(state: StatsUiState) {
        val container = binding.layoutCategoryList
        container.removeAllViews()

        val breakdown = state.categoryBreakdown
        if (breakdown.isEmpty()) {
            binding.tvCategoryEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvCategoryEmpty.visibility = View.GONE

        // 进度条最大宽度（dp → px）
        val barMaxWidth = (resources.displayMetrics.density * 120).toInt()

        for (item in breakdown) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6; bottomMargin = 6
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // emoji 图标
            row.addView(TextView(requireContext()).apply {
                text = item.iconEmoji
                textSize = 16f
                setPadding(0, 0, 8, 0)
            })

            // 分类名
            row.addView(TextView(requireContext()).apply {
                text = item.categoryName
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(Color.parseColor("#212121"))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            // 金额
            row.addView(TextView(requireContext()).apply {
                text = "¥${String.format("%.2f", item.amount)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(8, 0, 8, 0)
            })

            // 百分比进度条
            val barWidth = (barMaxWidth * item.percentage).toInt().coerceAtLeast(2)
            val barOuter = android.widget.FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(barMaxWidth, 8).apply {
                    marginStart = 4; marginEnd = 4
                }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
            val barInner = View(requireContext()).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(barWidth, 8)
                setBackgroundColor(item.colorArgb)
            }
            barOuter.addView(barInner)
            row.addView(barOuter)

            // 百分比数字
            row.addView(TextView(requireContext()).apply {
                text = "${String.format("%.1f", item.percentage * 100)}%"
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4 }
            })

            // 点击 → 发射 ClickCategory 事件
            row.setOnClickListener {
                viewModel.onEvent(StatsEvent.ClickCategory(item.categoryId))
            }
            row.isClickable = true
            row.background = getThemeDrawable(android.R.attr.selectableItemBackground)

            container.addView(row)
        }
    }

    // ═══════════ 区域渲染 — 最近交易 ═══════════

    /**
     * 动态构建最近交易列表
     *
     * 每行: [emoji] 商户名 ··· ¥金额 · 时间
     * 收入条目前显示 "+¥" 前缀以区分。
     * 点击条目 → 发射 ClickRecentBill 事件。
     */
    private fun renderRecentBills(state: StatsUiState) {
        val container = binding.layoutRecentBills
        container.removeAllViews()

        val recent = state.recentBills
        if (recent.isEmpty()) {
            binding.tvRecentEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvRecentEmpty.visibility = View.GONE

        for (bill in recent) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8; bottomMargin = 8
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                background = getThemeDrawable(android.R.attr.selectableItemBackground)
                setOnClickListener {
                    viewModel.onEvent(StatsEvent.ClickRecentBill(bill.id))
                }
            }

            // emoji
            if (bill.categoryEmoji.isNotEmpty()) {
                row.addView(TextView(requireContext()).apply {
                    text = bill.categoryEmoji
                    textSize = 16f
                    setPadding(0, 0, 8, 0)
                })
            }

            // 商户名
            row.addView(TextView(requireContext()).apply {
                text = bill.merchantDisplay
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            // 金额
            val amountPrefix = if (bill.isIncome) "+¥" else "¥"
            row.addView(TextView(requireContext()).apply {
                text = "$amountPrefix${String.format("%.2f", bill.amount)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(
                    if (bill.isIncome) Color.parseColor("#4CAF50")
                    else Color.parseColor("#F44336")
                )
                setPadding(8, 0, 0, 0)
            })

            // 时间
            row.addView(TextView(requireContext()).apply {
                text = TIME_FORMAT.format(Date(bill.timestamp))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(8, 0, 0, 0)
            })

            container.addView(row)
        }
    }

    // ═══════════ 副作用处理 — 跨 Tab 导航 ═══════════

    /**
     * 消费 ViewModel Effect Channel 的一次性事件
     *
     * 当前处理的 Effect:
     *   - NavigateToBills: 点击分类 → 切换到账单 Tab 并预筛选
     *   - OpenBillDetail: 点击最近交易 → 切换到账单 Tab 并打开编辑对话框
     */
    private fun handleEffect(effect: Any?) {
        when (effect) {
            is StatsEffect.NavigateToBills -> {
                CLog.d(TAG) {
                    "Effect: 导航到账单列表 | categoryId=${effect.categoryId}"
                }
                val activity = activity
                if (activity is com.bjbyhd.screenreader_huawei.biller.ui.main.BillDashboardActivity) {
                    activity.navigateToBills(effect.categoryId)
                }
            }
            is StatsEffect.OpenBillDetail -> {
                CLog.d(TAG) {
                    "Effect: 打开账单详情 | billId=${effect.billId}"
                }
                val activity = activity
                if (activity is com.bjbyhd.screenreader_huawei.biller.ui.main.BillDashboardActivity) {
                    activity.openBillEdit(effect.billId)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ═══════════ 工具方法 ═══════════

    /**
     * 从当前主题中解析 attr 属性并获取对应的 Drawable
     *
     * Android 的 attr（如 selectableItemBackground）不是直接的 drawable 资源 ID，
     * 必须通过 [android.content.res.Resources.Theme.resolveAttribute] 解析后，
     * 才能获取到实际绑定的 drawable 引用。
     *
     * 直接使用 attr ID 调用 [ContextCompat.getDrawable] 会导致
     * Resources$NotFoundException（Resource ID #0x101030e is a complex map type）。
     *
     * @param attrId 主题属性 ID（如 android.R.attr.selectableItemBackground）
     * @return 解析后的 Drawable，解析失败返回 null
     */
    private fun getThemeDrawable(attrId: Int): android.graphics.drawable.Drawable? {
        val typedValue = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attrId, typedValue, true)) {
            ContextCompat.getDrawable(requireContext(), typedValue.resourceId)
        } else {
            null
        }
    }
}
