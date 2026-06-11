package com.bjbyhd.screenreader_huawei.biller.data.model

/**
 * DAO 聚合查询原始结果 — 分类支出分布 (Phase 5B)
 *
 * 模块: feature/biller/data/model
 * 职责: 承载 [BillRecordDao.getCategoryBreakdownRaw] 的 SQL GROUP_BY 聚合查询返回值。
 *       每行代表一个分类（或未分类归组）的支出合计和笔数。
 *
 * 设计意图:
 *   - 未分类账单（category_id IS NULL）通过 SQL COALESCE 归入 categoryId = -1
 *   - DAO 不感知 [Category] 表结构——分类名称/emoji/颜色的关联在 Repository 层完成
 *   - 百分比计算在 Repository 或 ViewModel 层完成（需要全量数据才能归一化）
 *
 * @property categoryId   分类 ID，-1 表示未分类（SQL COALESCE 结果）
 * @property totalAmount  该分类的支出合计（正数）
 * @property recordCount  该分类的交易笔数
 */
data class RawCategoryAgg(
    val categoryId: Long,
    val totalAmount: Double,
    val recordCount: Int,
)
