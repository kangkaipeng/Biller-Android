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

    /** 订阅全量账单记录（按时间降序），数据变更时自动发射 */
    fun observeAll(): Flow<List<BillRecord>>
    /** 订阅全部分类（按 sortOrder 升序），数据变更时自动发射 */
    fun observeCategories(): Flow<List<Category>>
    /** 获取最近 N 条账单记录 */
    suspend fun getRecentRecords(limit: Int = 50): List<BillRecord>
    /** 获取所有不重复商户名列表 */
    suspend fun getDistinctMerchants(): List<String>
    /** 总记录条数 */
    suspend fun count(): Int

    // ═══════════ 编辑 ═══════════

    /** 按 ID 删除单条账单 */
    suspend fun deleteById(id: Long)
    /**
     * 批量更新账单可编辑字段
     * @param id   账单 ID
     * @param alias 商户别名，null = 不修改
     * @param categoryId 分类 ID，null = 不修改
     * @param note  备注，null = 不修改
     * @param amount 金额，null = 不修改
     * @param txnId  流水号，null = 不修改
     */
    suspend fun updateBillFields(
        id: Long,
        alias: String? = null,
        categoryId: Long? = null,
        note: String? = null,
        amount: Double? = null,
        txnId: String? = null,
    )

    // ═══════════ 分类管理 ═══════════

    /** 新增分类，返回自增 ID */
    suspend fun insertCategory(category: Category): Long
    /** 更新分类（按 id 匹配） */
    suspend fun updateCategory(category: Category)
    /** 按 ID 删除分类 */
    suspend fun deleteCategory(id: Long)
}
