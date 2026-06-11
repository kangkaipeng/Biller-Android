package com.bjbyhd.screenreader_huawei.biller.data.category

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 消费分类 — Room Entity (v4)
 *
 * 模块: feature/biller/data
 * 职责: 定义账单的消费分类体系，支持系统预置分类和用户自定义分类。
 *
 * ## 预置分类
 *
 * 首次启动时由 [BillerApplication] 插入 10 个默认分类，
 * [isDefault] 标记为 true 的分类不可删除。
 *
 * ## 使用方式
 *
 * [BillRecord.categoryId] 外键指向本表 id。
 * UI 层通过 [CategoryDao.observeAll] 获取分类列表用于下拉选择器。
 *
 * @property id        自增主键
 * @property name      分类名称（如"餐饮"、"交通"）
 * @property iconEmoji 分类图标 emoji（如 "🍔"、"🚇"）
 * @property colorArgb 分类标签 ARGB 色值（如 0xFFFF5722.toInt()）
 * @property isDefault 是否为系统预置分类（true 时不可删除）
 * @property sortOrder 排序权重（值越小越靠前）
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "icon_emoji")
    val iconEmoji: String = "📋",

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int = 0xFF607D8B.toInt(),

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)
