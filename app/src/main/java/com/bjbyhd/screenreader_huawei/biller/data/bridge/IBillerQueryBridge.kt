package com.bjbyhd.screenreader_huawei.biller.data.bridge

import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.category.Category
import kotlinx.coroutines.flow.Flow

/**
 * UI 层查询与编辑契约
 *
 * 职责: ViewModel 通过此接口读取账单数据、编辑字段、管理分类。
 *       隔离 ViewModel 对 Room DAO 的直接依赖。
 *
 * @see BillRepository 实现类
 */
interface IBillerQueryBridge {

    // ═══════════ 观察查询 ═══════════

    fun observeAll(): Flow<List<BillRecord>>
    fun observeCategories(): Flow<List<Category>>
    suspend fun getRecentRecords(limit: Int = 50): List<BillRecord>
    suspend fun getDistinctMerchants(): List<String>
    suspend fun count(): Int

    // ═══════════ 编辑 ═══════════

    suspend fun deleteById(id: Long)
    suspend fun updateBillFields(
        id: Long,
        alias: String? = null,
        categoryId: Long? = null,
        note: String? = null,
        amount: Double? = null,
        txnId: String? = null,
    )

    // ═══════════ 分类管理 ═══════════

    suspend fun insertCategory(category: Category): Long
    suspend fun updateCategory(category: Category)
    suspend fun deleteCategory(id: Long)
}
