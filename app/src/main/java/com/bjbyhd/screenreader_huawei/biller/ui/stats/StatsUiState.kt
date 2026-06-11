package com.bjbyhd.screenreader_huawei.biller.ui.stats

import com.bjbyhd.screenreader_huawei.biller.data.model.BillSnapshot
import com.bjbyhd.screenreader_huawei.biller.data.model.CategoryStat
import java.time.YearMonth

/**
 * 统计 Tab MVI — UiState / Event 定义 (Phase 5A)
 *
 * 模块: feature/biller/ui/stats
 * 职责: 定义统计页面的不可变 UI 状态与用户交互事件。
 *
 * ## 注意
 *
 *   [CategoryStat] 已迁移至 [com.bjbyhd.screenreader_huawei.biller.data.model.CategoryStat]，
 *   以避免 data 层反向依赖 ui 层。两个包均从此路径导入。
 */

/** 统计页 UI 状态 */
data class StatsUiState(
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val expenseCount: Int = 0,
    val incomeCount: Int = 0,
    val netAmount: Double = 0.0,
    val lastMonthExpense: Double? = null,
    val monthOverMonthRate: Float? = null,
    val categoryBreakdown: List<CategoryStat> = emptyList(),
    val recentBills: List<BillSnapshot> = emptyList(),
    val selectedYearMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = true,
)

/** 统计页用户交互事件 */
sealed interface StatsEvent {
    data class SelectMonth(val yearMonth: YearMonth) : StatsEvent
    data class ClickCategory(val categoryId: Long) : StatsEvent
    data class ClickRecentBill(val billId: Long) : StatsEvent
}

/**
 * 一次性副作用 — 统计 Tab (Phase 5D)
 *
 * 通过 [BaseMviViewModel.effect] Channel 发射，用于跨 Tab 导航等一次性操作。
 * 与 [StatsUiState]（持续性 UI 状态）分离，确保导航事件不被 StateFlow 重播。
 */
sealed interface StatsEffect {
    /** 导航到账单列表 Tab 并预筛选指定分类 */
    data class NavigateToBills(val categoryId: Long?) : StatsEffect
    /** 导航到账单列表 Tab 并打开指定账单的编辑对话框 */
    data class OpenBillDetail(val billId: Long) : StatsEffect
}
