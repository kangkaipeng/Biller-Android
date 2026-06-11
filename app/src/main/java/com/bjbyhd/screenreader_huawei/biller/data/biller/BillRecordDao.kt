package com.bjbyhd.screenreader_huawei.biller.data.biller

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bjbyhd.screenreader_huawei.biller.data.model.RawCategoryAgg
import com.bjbyhd.screenreader_huawei.biller.data.model.RawMonthSummary
import kotlinx.coroutines.flow.Flow

/**
 * 账单记录 DAO — Room 数据访问对象
 *
 * 模块: feature/biller/data
 * 职责: 定义 [BillRecord] 的所有数据库操作接口，
 *       由 Room 在编译时通过 KSP 生成实现类。
 *
 * 方法命名约定:
 *   - suspend 函数用于一次性操作（在协程内调用，Room 自动在后台线程执行）
 *   - Flow 返回值用于持续观察（数据变化时自动发射新值）
 *
 * @see BillRecord 账单数据模型
 * @see BillerDatabase 数据库单例持有者
 */
@Dao
interface BillRecordDao {

    /**
     * 插入一条账单记录
     *
     * Room 的 [Insert] 自动处理主键自增，返回新行的 rowId。
     * 此方法在 Room 内部使用 ArchTaskExecutor 的 IO 线程执行，调用方无需切线程。
     *
     * @param record 待插入的账单记录（id 设为 0 表示自增）
     * @return 插入后的行 ID
     */
    @Insert
    suspend fun insert(record: BillRecord): Long

    /**
     * 更新一条已有的账单记录
     *
     * 用于后续的合流合并（更新 merchant / mergeStatus）和别名编辑。
     * Room 通过主键 id 匹配行，只更新非 null 字段。
     *
     * @param record 待更新的记录（主键必须与 DB 中一致）
     */
    @Update
    suspend fun update(record: BillRecord)

    /**
     * 按时间戳降序获取全部记录
     *
     * 用于 UI 展示全量账单列表。
     * Flow 保证数据一致：任何 insert/update 操作后自动重新发射最新列表。
     *
     * @return 按时间戳降序排列的账单记录流
     */
    @Query("SELECT * FROM bill_records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BillRecord>>

    /**
     * 按时间戳降序获取最近 N 条记录
     *
     * 用于 Activity 初次加载时快速展示最新数据。
     *
     * @param limit 加载数量上限（默认 50）
     * @return 最近 N 条账单记录
     */
    @Query("SELECT * FROM bill_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<BillRecord>

    /**
     * 滑动时间窗匹配查询（去重合流算法核心）
     *
     * 查询指定时间窗口内、相同金额、且尚未合并的记录（flags 中仅有单一来源位）。
     * 用于数据仓库层的双服务数据融合匹配（时间窗 + 金额 + 来源）。
     *
     * 合流状态判断: (flags & (flags - 1)) = 0 且 flags != 0
     * 即 flags 中仅有 1 个 bit 为 1 → 单源未合并记录。
     *
     * @param from   时间窗口起始时间戳（毫秒）
     * @param to     时间窗口结束时间戳（毫秒）
     * @param amount 待匹配的金额
     * @return 窗口内匹配的记录列表
     */
    @Query("""
        SELECT * FROM bill_records
        WHERE timestamp BETWEEN :from AND :to
          AND amount = :amount
          AND (flags & (flags - 1)) = 0
          AND flags != 0
        ORDER BY timestamp DESC
    """)
    suspend fun findInTimeWindow(
        from: Long,
        to: Long,
        amount: Double
    ): List<BillRecord>

    /**
     * 持久化去重查询 — windowId + 金额 + 商户
     *
     * 用于跨 session 去重：App 重启后内存哈希清空，
     * 但窗口未变时可通过此查询发现已入库的相同记录。
     */
    @Query("""
        SELECT * FROM bill_records
        WHERE window_id = :windowId AND amount = :amount
          AND (merchant = :merchant OR (merchant IS NULL AND :merchant = ''))
        LIMIT 1
    """)
    suspend fun findByWindowId(windowId: Int, amount: Double, merchant: String): BillRecord?

    /**
     * 按 ID 精确删除（测试用）
     *
     * @param id 主键
     */
    @Query("DELETE FROM bill_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 清空全表（测试用，重置数据库）
     */
    @Query("DELETE FROM bill_records")
    suspend fun deleteAll()

    /**
     * 统计总记录数
     */
    @Query("SELECT COUNT(*) FROM bill_records")
    suspend fun count(): Int

    /**
     * 按 ID 精确查询单条记录 (v4)
     *
     * 用于 UI 层单字段更新前获取完整记录以便 copy()。
     *
     * @param id 主键
     * @return 记录实例，不存在时返回 null
     */
    @Query("SELECT * FROM bill_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BillRecord?

    /**
     * 获取已识别的去重商户名列表 (v4)
     *
     * 用于商户别名管理页面展示所有历史上出现过的商户。
     * 按时间戳降序排列，最近出现的商户排在前。
     */
    @Query("SELECT DISTINCT merchant FROM bill_records WHERE merchant IS NOT NULL ORDER BY timestamp DESC")
    suspend fun findDistinctMerchants(): List<String>

    // ═══════════ 统计聚合查询 (Phase 5B) ═══════════

    /**
     * 月度收支汇总聚合查询
     *
     * 在指定时间窗口内执行 SUM / COUNT 聚合，一次性返回四个指标:
     *   - totalExpense: amount > 0 的合计（支出为正数）
     *   - totalIncome:  amount < 0 的绝对值合计（收入为负数）
     *   - expenseCount: 支出笔数
     *   - incomeCount:  收入笔数
     *
     * # 为什么用 COALESCE
     *
     * 当时间窗口内无数据时，SUM 返回 NULL。COALESCE 将 NULL 转为 0，
     * 确保返回值永远非 null，避免 Room 类型映射异常。
     *
     * # 为什么不使用 Flow
     *
     * 聚合查询是一次性快照读取——统计 Tab 只需要当前月份的快照数据，
     * 不需要持续观察。使用 suspend fun 配合 Room 的自动后台线程执行。
     *
     * @param startMs 时间窗口起始时间戳（毫秒，含）
     * @param endMs   时间窗口结束时间戳（毫秒，含）
     * @return 月度汇总原始数据，无数据时返回全零对象
     */
    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0.0) AS totalExpense,
            COALESCE(SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END), 0.0) AS totalIncome,
            COUNT(CASE WHEN amount > 0 THEN 1 END) AS expenseCount,
            COUNT(CASE WHEN amount < 0 THEN 1 END) AS incomeCount
        FROM bill_records
        WHERE timestamp BETWEEN :startMs AND :endMs
    """)
    suspend fun getMonthSummaryRaw(startMs: Long, endMs: Long): RawMonthSummary

    /**
     * 分类支出分布聚合查询
     *
     * 在指定时间窗口内，按 category_id 分组统计支出金额和笔数。
     * 仅统计支出记录 (amount > 0)，按支出金额降序排列。
     *
     * # 未分类处理
     *
     * 使用 COALESCE(category_id, -1) 将未分类账单归入 categoryId = -1 的统一分组。
     * 不做 LEFT JOIN categories——分类名称/图标/颜色由 Repository 层通过
     * [CategoryDao.observeAll] 的结果做内存关联，保持 DAO 层的纯聚合职责。
     *
     * # 为什么使用 GROUP BY 而非逐个查询
     *
     * 单条 SQL 的 GROUP BY 比 N 次独立查询效率高得多。Room 的 @Query
     * 能将结果直接映射为 List<RawCategoryAgg>，无需额外处理。
     *
     * @param startMs 时间窗口起始时间戳（毫秒，含）
     * @param endMs   时间窗口结束时间戳（毫秒，含）
     * @return 按金额降序排列的分类聚合列表，无数据时返回空列表
     */
    @Query("""
        SELECT
            COALESCE(category_id, -1) AS categoryId,
            SUM(amount) AS totalAmount,
            COUNT(*) AS recordCount
        FROM bill_records
        WHERE timestamp BETWEEN :startMs AND :endMs
          AND amount > 0
        GROUP BY COALESCE(category_id, -1)
        ORDER BY SUM(amount) DESC
    """)
    suspend fun getCategoryBreakdownRaw(startMs: Long, endMs: Long): List<RawCategoryAgg>

    // ═══════════ 全量导出/导入 (v5.3) ═══════════

    /**
     * 批量插入账单记录 — 用于 CSV 导入时一次性写入全部数据
     *
     * Room 的 [Insert] 注解支持 List 参数，内部使用事务保证原子性:
     * 所有记录要么全部成功写入，要么全部回滚。
     *
     * 导入时 CSV 中的 id 被忽略（统一设为 0），由 Room 自动分配新主键。
     * 因此不会与已有数据产生主键冲突。
     *
     * @param records 待插入的记录列表
     * @return 新分配的行 ID 列表（顺序与输入一致）
     */
    @Insert
    suspend fun insertAll(records: List<BillRecord>): List<Long>
}
