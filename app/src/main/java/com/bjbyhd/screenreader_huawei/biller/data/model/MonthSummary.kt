package com.bjbyhd.screenreader_huawei.biller.data.model

/**
 * 月度收支汇总 — 统计 Tab 数据模型 (Phase 5A)
 *
 * 模块: feature/biller/data/model
 * 职责: 承载单月支出/收入/笔数的聚合数据。
 *
 * @property totalExpense    月支出合计（正数）
 * @property totalIncome     月收入合计（正数）
 * @property expenseCount    支出笔数
 * @property incomeCount     收入笔数
 * @property averageExpense  笔均支出（totalExpense / expenseCount）
 */
data class MonthSummary(
    val totalExpense: Double,
    val totalIncome: Double,
    val expenseCount: Int,
    val incomeCount: Int,
) {
    /** 笔均支出 — 支出笔数为 0 时返回 0.0 */
    val averageExpense: Double
        get() = if (expenseCount > 0) totalExpense / expenseCount else 0.0
}

/** 月度环比变化 */
data class MoMStat(
    val currentMonthExpense: Double,
    val lastMonthExpense: Double,
    val changeAmount: Double,     // 变化额（正=增长）
    val changeRate: Float,        // 变化率（正=增长%）
)

/** 最近交易轻量快照 — 仅渲染所需字段 */
data class BillSnapshot(
    val id: Long,
    val merchantDisplay: String,
    val amount: Double,
    val categoryEmoji: String,
    val timestamp: Long,
    val isIncome: Boolean,
)
