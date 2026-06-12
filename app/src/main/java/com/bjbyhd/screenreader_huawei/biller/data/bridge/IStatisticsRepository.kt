package com.bjbyhd.screenreader_huawei.biller.data.bridge

import com.bjbyhd.screenreader_huawei.biller.data.model.BillSnapshot
import com.bjbyhd.screenreader_huawei.biller.data.model.CategoryStat
import com.bjbyhd.screenreader_huawei.biller.data.model.MoMStat
import com.bjbyhd.screenreader_huawei.biller.data.model.MonthSummary
import java.time.YearMonth

/**
 * 统计聚合查询契约
 *
 * 职责: 月度汇总、分类分布、环比变化、最近交易快照。
 *
 * @see BillRepository 实现类
 */
interface IStatisticsRepository {

    /** 月度汇总 — 总收入/总支出/笔数 */
    suspend fun getMonthSummary(yearMonth: YearMonth): MonthSummary
    /** 分类支出分布 — 按 categoryId 聚合，按金额降序排列 */
    suspend fun getCategoryBreakdown(yearMonth: YearMonth): List<CategoryStat>
    /** 环比统计 — 与上月对比的变化率，上月无数据时返回 null */
    suspend fun getMonthOverMonth(yearMonth: YearMonth): MoMStat?
    /** 最近 N 条交易快照（轻量，仅含展示字段） */
    suspend fun getRecentBills(limit: Int = 5): List<BillSnapshot>
}
