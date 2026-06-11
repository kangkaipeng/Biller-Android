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

    suspend fun getMonthSummary(yearMonth: YearMonth): MonthSummary
    suspend fun getCategoryBreakdown(yearMonth: YearMonth): List<CategoryStat>
    suspend fun getMonthOverMonth(yearMonth: YearMonth): MoMStat?
    suspend fun getRecentBills(limit: Int = 5): List<BillSnapshot>
}
