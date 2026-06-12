package com.bjbyhd.screenreader_huawei.biller.data.category

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 消费分类 DAO — Room 数据访问对象
 *
 * 模块: feature/biller/data
 * 职责: 定义 [Category] 的所有数据库操作接口，
 *       由 Room 在编译时通过 KSP 生成实现类。
 *
 * @see Category 分类数据模型
 * @see BillerDatabase 数据库单例持有者
 */
@Dao
interface CategoryDao {

    /**
     * 观察全部分类（按 sort_order 升序 → id 升序）
     *
     * Flow 保证数据一致：任何 insert/update/delete 后自动重新发射。
     * 预置分类 sort_order 较低，排序后出现在列表前部。
     */
    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<Category>>

    /**
     * 插入一条分类
     *
     * @return 新插入行的 ID
     */
    @Insert
    suspend fun insert(category: Category): Long

    /**
     * 更新分类（名称 / emoji / 颜色）
     *
     * 预置分类的 [isDefault] 字段不应通过此方法修改。
     */
    @Update
    suspend fun update(category: Category)

    /**
     * 删除分类（仅允许删除非预置分类）
     *
     * 调用方应先检查 [Category.isDefault]。
     */
    @Query("DELETE FROM categories WHERE id = :id AND is_default = 0")
    suspend fun deleteById(id: Long)

    /**
     * 按 ID 查询单条分类
     */
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Category?

    /**
     * 一次性获取全部分类快照 (Phase 5C)
     *
     * 用于 Repository 层的暂停函数中同步获取分类列表做内存关联。
     * 与 [observeAll]（持续观察的 Flow）不同，此方法返回一次性快照。
     *
     * 按 sort_order 升序 → id 升序排列。
     */
    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<Category>

    /**
     * 统计分类总数
     */
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    /**
     * 按名称查找分类 (v5.2)
     *
     * 用于自动分类标记场景——解析器通过 [ParsedBill.extras] 传递语义标签
     * （如 "转账"），Repository 层通过此方法查表获取实际 ID。
     *
     * 名称作为业务语义键，预设分类的名称不应被用户修改。
     * 若存在多条同名记录（边缘情况），取第一条。
     *
     * @param name 分类名称，精确匹配
     * @return 匹配的 Category，不存在时返回 null
     */
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Category?
}
