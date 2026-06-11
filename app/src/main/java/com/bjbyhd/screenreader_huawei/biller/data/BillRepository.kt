package com.bjbyhd.screenreader_huawei.biller.data

import android.content.Context
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecordDao
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerImportExportBridge
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerQueryBridge
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IStatisticsRepository
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import com.bjbyhd.screenreader_huawei.biller.data.category.CategoryDao
import com.bjbyhd.screenreader_huawei.biller.data.model.BillSnapshot
import com.bjbyhd.screenreader_huawei.biller.data.model.CategoryStat
import com.bjbyhd.screenreader_huawei.biller.data.model.FullBillExport
import com.bjbyhd.screenreader_huawei.biller.data.model.ImportResult
import com.bjbyhd.screenreader_huawei.biller.data.model.MoMStat
import com.bjbyhd.screenreader_huawei.biller.data.model.MonthSummary
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

/**
 * 数据仓库 — UI 层与 Room 之间的唯一数据访问入口
 *
 * 职责:
 *   1. 实现 [IBillerQueryBridge] — 账单查询、编辑、分类管理
 *   2. 实现 [IStatisticsRepository] — 月度汇总、分类分布、环比、最近交易
 *   3. 实现 [IBillerImportExportBridge] — CSV 全量导出导入
 *
 * 注意: 写入路径（insert/update/merge）由 [BillProcessingPipeline] 处理，
 *       本类仅负责读取和编辑。
 *
 * 初始化: [BillerApplication.onCreate] 中调用 [init]
 */
class BillRepository(
    private val billDao: BillRecordDao,
    private val categoryDao: CategoryDao,
) : IBillerQueryBridge, IStatisticsRepository, IBillerImportExportBridge {

    companion object {
        private const val TAG = "BillRepo"

        @Volatile
        private var INSTANCE: BillRepository? = null

        fun init(context: Context): BillRepository {
            return INSTANCE ?: synchronized(BillRepository::class.java) {
                INSTANCE ?: BillRepository(
                    BillerDatabase.getInstance(context).billRecordDao(),
                    BillerDatabase.getInstance(context).categoryDao(),
                ).also { INSTANCE = it }
            }
        }

        fun getInstance(): BillRepository = INSTANCE!!
    }

    // ═══════════ IBillerQueryBridge ═══════════

    override fun observeAll(): Flow<List<BillRecord>> = billDao.observeAll()
    override fun observeCategories(): Flow<List<Category>> = categoryDao.observeAll()
    override suspend fun getRecentRecords(limit: Int): List<BillRecord> = billDao.getRecent(limit)
    override suspend fun getDistinctMerchants(): List<String> = billDao.findDistinctMerchants()
    override suspend fun count(): Int = billDao.count()
    override suspend fun deleteById(id: Long) = billDao.deleteById(id)

    override suspend fun updateBillFields(
        id: Long, alias: String?, categoryId: Long?, note: String?, amount: Double?, txnId: String?
    ) {
        billDao.getById(id)?.let { record ->
            billDao.update(
                record.copy(
                    merchantAlias = alias ?: record.merchantAlias,
                    categoryId = categoryId ?: record.categoryId,
                    note = note?.let { if (it.isBlank()) null else it } ?: record.note,
                    amount = amount ?: record.amount,
                    transactionId = txnId?.let { if (it.isBlank()) null else it } ?: record.transactionId,
                )
            )
        }
    }

    override suspend fun insertCategory(category: Category): Long = categoryDao.insert(category)
    override suspend fun updateCategory(category: Category) = categoryDao.update(category)
    override suspend fun deleteCategory(id: Long) = categoryDao.deleteById(id)

    // ═══════════ IStatisticsRepository ═══════════

    override suspend fun getMonthSummary(yearMonth: YearMonth): MonthSummary {
        val (startMs, endMs) = yearMonth.toMillisRange()
        val raw = billDao.getMonthSummaryRaw(startMs, endMs)
        return MonthSummary(
            totalExpense = raw.totalExpense,
            totalIncome = raw.totalIncome,
            expenseCount = raw.expenseCount,
            incomeCount = raw.incomeCount,
        )
    }

    override suspend fun getCategoryBreakdown(yearMonth: YearMonth): List<CategoryStat> {
        val (startMs, endMs) = yearMonth.toMillisRange()
        val rawList = billDao.getCategoryBreakdownRaw(startMs, endMs)
        if (rawList.isEmpty()) return emptyList()

        val catMap = categoryDao.getAll().associateBy { it.id }
        val totalExpense = rawList.sumOf { it.totalAmount }

        return rawList.map { raw ->
            val cat = if (raw.categoryId == -1L) null else catMap[raw.categoryId]
            CategoryStat(
                categoryId = raw.categoryId,
                categoryName = cat?.name ?: if (raw.categoryId == -1L) "其他" else "已删除分类",
                iconEmoji = cat?.iconEmoji ?: "📋",
                colorArgb = cat?.colorArgb ?: 0xFF9E9E9E.toInt(),
                amount = raw.totalAmount,
                count = raw.recordCount,
                percentage = if (totalExpense > 0) (raw.totalAmount / totalExpense).toFloat() else 0f,
            )
        }
    }

    override suspend fun getMonthOverMonth(yearMonth: YearMonth): MoMStat? {
        val current = getMonthSummary(yearMonth)
        val last = getMonthSummary(yearMonth.minusMonths(1))
        if (last.totalExpense == 0.0 && last.expenseCount == 0) return null

        val changeAmount = current.totalExpense - last.totalExpense
        val changeRate = if (last.totalExpense > 0) (changeAmount / last.totalExpense).toFloat() else 0f

        return MoMStat(
            currentMonthExpense = current.totalExpense,
            lastMonthExpense = last.totalExpense,
            changeAmount = changeAmount,
            changeRate = changeRate,
        )
    }

    override suspend fun getRecentBills(limit: Int): List<BillSnapshot> {
        if (limit <= 0) return emptyList()
        val records = billDao.getRecent(limit)
        val catMap = categoryDao.getAll().associateBy { it.id }

        return records.map { record ->
            val cat = record.categoryId?.let { catMap[it] }
            BillSnapshot(
                id = record.id,
                merchantDisplay = record.merchantAlias ?: record.merchant ?: "未知商户",
                amount = record.amount ?: 0.0,
                categoryEmoji = cat?.iconEmoji ?: "",
                timestamp = record.timestamp,
                isIncome = (record.amount ?: 0.0) < 0,
            )
        }
    }

    // ═══════════ IBillerImportExportBridge ═══════════

    override suspend fun exportRecords(limit: Int?): List<FullBillExport> {
        val records = if (limit != null) billDao.getRecent(limit) else billDao.getRecent(Int.MAX_VALUE)
        if (records.isEmpty()) return emptyList()

        val catMap = categoryDao.getAll().associateBy { it.id }

        return records.map { record ->
            val cat = record.categoryId?.let { catMap[it] }
            FullBillExport(
                id = record.id,
                packageName = record.packageName,
                rawTitle = record.rawTitle,
                rawText = record.rawText,
                amount = record.amount,
                merchant = record.merchant,
                paymentChannel = record.paymentChannel,
                timestamp = record.timestamp,
                source = "",
                mergeStatus = "",
                paymentMethod = record.paymentMethod,
                originalAmount = record.originalAmount,
                discountInfo = record.discountInfo,
                merchantAlias = record.merchantAlias,
                categoryId = record.categoryId,
                categoryName = cat?.name,
                note = record.note,
                transactionId = record.transactionId,
            )
        }
    }

    override suspend fun importFromCsv(records: List<FullBillExport>): ImportResult {
        if (records.isEmpty()) return ImportResult(0, 0, 0, emptyList())

        val catNameToId = categoryDao.getAll().associate { it.name to it.id }
        val toInsert = mutableListOf<BillRecord>()
        var skippedCount = 0
        val errors = mutableListOf<String>()

        for (row in records) {
            if (row.amount == null) {
                skippedCount++
                if (errors.size < 20) errors.add("id=${row.id}: 金额缺失")
                continue
            }
            val categoryId = row.categoryName?.let { catNameToId[it] }

            toInsert.add(
                BillRecord(
                    id = 0L,
                    packageName = row.packageName,
                    rawTitle = row.rawTitle,
                    rawText = row.rawText,
                    amount = row.amount,
                    merchant = row.merchant,
                    paymentChannel = row.paymentChannel,
                    timestamp = row.timestamp,
                    flags = 0L,
                    paymentMethod = row.paymentMethod,
                    originalAmount = row.originalAmount,
                    discountInfo = row.discountInfo,
                    merchantAlias = row.merchantAlias,
                    categoryId = categoryId,
                    note = row.note,
                    transactionId = row.transactionId,
                )
            )
        }

        val inserted = try {
            billDao.insertAll(toInsert).size
        } catch (e: Exception) {
            CLog.e(TAG, e) { "importFromCsv: 批量写入失败" }
            skippedCount += toInsert.size
            0
        }

        return ImportResult(
            total = records.size,
            inserted = inserted,
            skipped = skippedCount,
            errors = errors,
        )
    }
}
