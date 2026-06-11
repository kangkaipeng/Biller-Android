package com.bjbyhd.screenreader_huawei.biller.ui.stub

import com.bjbyhd.screenreader_huawei.biller.data.bridge.IStatisticsRepository
import com.bjbyhd.screenreader_huawei.biller.data.model.BillSnapshot
import com.bjbyhd.screenreader_huawei.biller.data.model.CategoryStat
import com.bjbyhd.screenreader_huawei.biller.data.model.MoMStat
import com.bjbyhd.screenreader_huawei.biller.data.model.MonthSummary
import java.time.YearMonth

/**
 * IStatisticsRepository 桩实现 — UI 层独立运行时返回空/零数据
 *
 * 用途: 在数据仓库层未迁移前，为 ViewModel 提供编译兼容的实现。
 *       数据仓库层迁移后，以 BillerRepository 替换此类。
 *       所有聚合方法返回零值或空列表，验证 UI 对无数据状态的处理。
 */
class StubStatisticsRepository : IStatisticsRepository {

    override suspend fun getMonthSummary(yearMonth: YearMonth): MonthSummary =
        MonthSummary(totalExpense = 0.0, totalIncome = 0.0, expenseCount = 0, incomeCount = 0)

    override suspend fun getCategoryBreakdown(yearMonth: YearMonth): List<CategoryStat> =
        emptyList()

    override suspend fun getMonthOverMonth(yearMonth: YearMonth): MoMStat? = null

    override suspend fun getRecentBills(limit: Int): List<BillSnapshot> = emptyList()
}
