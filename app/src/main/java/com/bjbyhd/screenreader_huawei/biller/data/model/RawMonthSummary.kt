package com.bjbyhd.screenreader_huawei.biller.data.model

/**
 * DAO 聚合查询原始结果 — 月度收支汇总 (Phase 5B)
 *
 * 模块: feature/biller/data/model
 * 职责: 承载 [BillRecordDao.getMonthSummaryRaw] 的 SQL 聚合查询返回值。
 *       与 [MonthSummary]（UI 层数据模型）解耦——DAO 返回原始数据，
 *       Repository 层负责映射和语义化字段。
 *
 * 设计意图:
 *   - DAO 层只关心数据库字段的投影，不引入 UI 层模型依赖
 *   - SQL 列别名必须与此类构造函数参数名完全一致（Room 编译时匹配）
 *   - 金额正负语义由 Repository 层统一处理，DAO 不做业务判断
 *
 * @property totalExpense  支出合计（SQL: SUM(CASE WHEN amount > 0)）
 * @property totalIncome   收入合计（SQL: SUM(CASE WHEN amount < 0)）
 * @property expenseCount  支出笔数
 * @property incomeCount   收入笔数
 */
data class RawMonthSummary(
    val totalExpense: Double,
    val totalIncome: Double,
    val expenseCount: Int,
    val incomeCount: Int,
)
