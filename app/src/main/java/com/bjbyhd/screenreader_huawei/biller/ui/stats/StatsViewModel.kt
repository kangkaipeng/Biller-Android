package com.bjbyhd.screenreader_huawei.biller.ui.stats

import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerQueryBridge
import com.bjbyhd.screenreader_huawei.biller.data.toMillisRange
import com.bjbyhd.screenreader_huawei.biller.data.model.BillSnapshot
import com.bjbyhd.screenreader_huawei.biller.data.model.CategoryStat
import com.bjbyhd.screenreader_huawei.biller.ui.common.BaseMviViewModel
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.time.YearMonth

/**
 * 统计 Tab ViewModel — MVI 单向数据流核心 (Phase 5D)
 *
 * 模块: feature/biller/ui/stats
 * 职责:
 *   - 订阅 [IBillerQueryBridge] 的账单 Flow，结合月份选择进行内存聚合
 *   - 产生单一的 [StatsUiState] StateFlow 驱动 UI 渲染
 *   - 处理用户事件 [StatsEvent]（月份切换、分类/交易点击）
 *   - 通过 Effect Channel 发射跨 Tab 导航副作用
 *
 * ## MVI 循环
 *
 * ```
 * StatsFragment                   StatsViewModel
 *    │                                │
 *    ├─ collect(uiState) ←──────── StateFlow<StatsUiState>
 *    │                                │
 *    ├─ collect(effect)  ←──────── Effect Channel            │
 *    │                                │
 *    ├─ onEvent(SelectMonth) ──────→ when(event) { ... }
 *    │                                │
 *    ├─ onEvent(ClickCategory) ────→ sendEffect(ClickMonthSummary)
 * ```
 *
 * ## 聚合策略
 *
 * 使用 ViewModel 层内存聚合（filter + groupBy + sumOf）而非 Repository 的 SQL 聚合：
 *   - 优势: Room Flow 数据变更自动触发重算，无需手动刷新
 *   - 劣势: 大数据量时性能不如 SQL GROUP_BY
 *   - 过渡: [statsRepo] 持有 SQL 聚合接口引用，长期可平滑切换
 *
 * ## 依赖
 *
 *   - [IBillerQueryBridge]: 获取全量账单记录 Flow + 分类 Flow
 *   - [IStatisticsRepository]: SQL 聚合接口（预留，Phase 5D 非主路径）
 *
 * @see StatsUiState UI 状态快照
 * @see StatsEvent 用户交互事件
 * @see StatsEffect 一次性副作用
 */
class StatsViewModel(
    private val billRepo: IBillerQueryBridge,
) : BaseMviViewModel<StatsUiState, StatsEvent>(
    initialUiState = StatsUiState()
) {

    companion object {
        private const val TAG = "Biller/StatsVM"
    }

    // ═══════════ 内部状态 — 月份选择 Flow ═══════════

    /**
     * 当前选中的年月
     *
     * 使用 [MutableStateFlow] 而非 UiState 字段单独存储，
     * 使其可以作为 [combine] 的一个输入流参与响应式聚合。
     * 初始值从 UiState 的默认值（YearMonth.now()）中读取以保持一致。
     */
    private val _selectedMonth = MutableStateFlow(YearMonth.now())

    // ═══════════ 初始化 — 订阅数据变化 ═══════════

    init {
        // 订阅账单记录和分类变化，结合月份选择 → 自动重算统计
        launchSafe {
            combine(
                billRepo.observeAll(),
                billRepo.observeCategories(),
                _selectedMonth,
            ) { records, categories, month ->
                // 在后台线程执行聚合计算（combine 的 lambda 在调用方协程上下文中执行）
                computeStats(records, categories, month)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    // ═══════════ Event 分发 — 统一入口 ═══════════

    override fun onEvent(event: StatsEvent) {
        CLog.d(TAG) { "onEvent: ${event::class.simpleName}" }
        when (event) {
            is StatsEvent.SelectMonth     -> onSelectMonth(event.yearMonth)
            is StatsEvent.ClickCategory   -> onCategoryClicked(event.categoryId)
            is StatsEvent.ClickRecentBill -> onRecentBillClicked(event.billId)
        }
    }

    // ═══════════ Event 处理器 ═══════════

    /** 切换月份 → 更新内部 Flow，combine 自动触发重算 */
    private fun onSelectMonth(yearMonth: YearMonth) {
        _selectedMonth.value = yearMonth
    }

    /** 点击分类 → 发射跨 Tab 导航 Effect */
    private fun onCategoryClicked(categoryId: Long) {
        sendEffect(StatsEffect.ClickMonthSummary(categoryId))
    }

    /** 点击最近交易 → 发射跨 Tab 导航 Effect（跳转并打开编辑对话框） */
    private fun onRecentBillClicked(billId: Long) {
        sendEffect(StatsEffect.OpenBillDetail(billId))
    }

    // ═══════════ 聚合计算 — 数据 → UiState ═══════════

    /**
     * 将原始数据聚合为 [StatsUiState]
     *
     * 处理流程: 月度筛选 → 收支分离 → 汇总 → 环比 → 分类分布 → 最近交易。
     * 使用内存聚合而非 SQL，避免对 observeAll 已加载数据做二次查询。
     *
     * 数据约定: amount > 0 为支出，amount < 0 为收入。
     *
     * @param records    全量账单记录
     * @param categories 全部分类信息
     * @param month      目标年月
     */
    private suspend fun computeStats(
        records: List<BillRecord>,
        categories: List<Category>,
        month: YearMonth,
    ): StatsUiState {
        // 月度筛选 + 收支分离（amount > 0 = 支出，< 0 = 收入）
        val range = month.toMillisRange()
        val monthRecords = records.filter { record ->
            record.timestamp in range.first..range.second
        }

        val expenseRecords = monthRecords.filter { (it.amount ?: 0.0) > 0 }
        val incomeRecords  = monthRecords.filter { (it.amount ?: 0.0) < 0 }

        val totalExpense = expenseRecords.sumOf { it.amount ?: 0.0 }
        val totalIncome  = incomeRecords.sumOf { -(it.amount ?: 0.0) }
        val expenseCount = expenseRecords.size
        val incomeCount  = incomeRecords.size

        // 环比 + 分类分布 + 最近交易
        val (lastExpense, momRate) = computeMoM(records, month, totalExpense)

        val categoryMap = categories.associateBy { it.id }
        val breakdown = computeCategoryBreakdown(expenseRecords, categoryMap, totalExpense)
        val recentBills = monthRecords
            .sortedByDescending { it.timestamp }
            .take(5)
            .map { record ->
                val cat = record.categoryId?.let { categoryMap[it] }
                BillSnapshot(
                    id = record.id,
                    merchantDisplay = record.merchantAlias
                        ?: record.merchant
                        ?: "未知商户",
                    amount = record.amount ?: 0.0,
                    categoryEmoji = cat?.iconEmoji ?: "",
                    timestamp = record.timestamp,
                    isIncome = (record.amount ?: 0.0) < 0,
                )
            }

        return StatsUiState(
            totalExpense        = totalExpense,
            totalIncome         = totalIncome,
            expenseCount        = expenseCount,
            incomeCount         = incomeCount,
            netAmount           = totalExpense - totalIncome,
            lastMonthExpense    = lastExpense,
            monthOverMonthRate  = momRate,
            categoryBreakdown   = breakdown,
            recentBills         = recentBills,
            selectedYearMonth   = month,
            isLoading           = false,
        )
    }

    /**
     * 计算月度环比变化
     *
     * @param records      全量账单记录
     * @param currentMonth 当前目标月份
     * @param currentExpense 当前月支出合计
     * @return Pair<上月支出: Double?, 环比变化率: Float?>
     *         上月无数据时两者均为 null
     */
    private fun computeMoM(
        records: List<BillRecord>,
        currentMonth: YearMonth,
        currentExpense: Double,
    ): Pair<Double?, Float?> {
        val lastMonth = currentMonth.minusMonths(1)
        val lastRange = lastMonth.toMillisRange()
        val lastExpense = records
            .filter { it.timestamp in lastRange.first..lastRange.second }
            .filter { (it.amount ?: 0.0) > 0 }
            .sumOf { it.amount ?: 0.0 }

        if (lastExpense == 0.0) return null to null

        val rate = ((currentExpense - lastExpense) / lastExpense).toFloat()
        return lastExpense to rate
    }

    /**
     * 计算分类支出分布
     *
     * 按 categoryId 分组聚合，关联分类元数据（名称/emoji/颜色），
     * 计算每个分类占总支出百分比。未分类账单归入 categoryId=-1 的"其他"。
     *
     * @param expenseRecords 当前月份的全部支出记录
     * @param categoryMap    id → Category 关联表
     * @param totalExpense   月支出合计（用于计算百分比）
     * @return 按金额降序排列的分类统计列表
     */
    private fun computeCategoryBreakdown(
        expenseRecords: List<BillRecord>,
        categoryMap: Map<Long, Category>,
        totalExpense: Double,
    ): List<CategoryStat> {
        if (expenseRecords.isEmpty()) return emptyList()

        return expenseRecords
            .groupBy { it.categoryId ?: -1L }
            .map { (catId, bills) ->
                val cat = categoryMap[catId]
                val amount = bills.sumOf { it.amount ?: 0.0 }
                CategoryStat(
                    categoryId  = catId,
                    categoryName = cat?.name
                        ?: if (catId == -1L) "其他" else "已删除分类",
                    iconEmoji   = cat?.iconEmoji ?: "📋",
                    colorArgb   = cat?.colorArgb ?: 0xFF9E9E9E.toInt(),
                    amount      = amount,
                    count       = bills.size,
                    percentage  = if (totalExpense > 0)
                        (amount / totalExpense).toFloat() else 0f,
                )
            }
            .sortedByDescending { it.amount }
    }
}
