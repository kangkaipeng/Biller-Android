package com.bjbyhd.screenreader_huawei.biller.data.biller

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账单记录 — Room Entity
 *
 * 模块: data/biller
 * 职责: 表示一条从通知栏或无障碍服务捕获的支付记录。
 *       由数据仓库层从 ParsedBill 映射而来。
 *
 * ## 双源采集与合流
 *
 * 本表同时接收多个来源的数据:
 *   - 通知栏监听 (NotificationListenerService)
 *   - 无障碍屏幕捕获 (AccessibilityService)
 *   - 微信记账本小程序详情页（未来）
 *   - 支付宝账单详情页（未来）
 *
 * 多条不同来源的记录可能对应同一笔真实交易。
 * 合流标识由 [flags] 字段承载：只有单一来源位 = 未合并，多个来源位 = 已合并。
 *
 * ## flags 标志位定义
 *
 * [flags] 为 Long 类型，使用低 16 位存储数据来源标志:
 *
 * ```
 * 0x0000 0000 0000 0000
 *                  └── bit 0 (0x0001): 通知栏监听
 *                  └── bit 1 (0x0002): 无障碍屏幕捕获
 *                  └── bit 2 (0x0004): 微信记账本详情（预留）
 *                  └── bit 3 (0x0008): 支付宝账单详情（预留）
 *                  └── bit 4 (0x0010): 预留
 *                     ...
 *                  └── bit 15 (0x8000): 预留
 * ```
 *
 * **使用方式**:
 *   - 单一来源记录: `flags = FLAG_NOTIFICATION`
 *   - 多源合并记录: `flags = FLAG_NOTIFICATION or FLAG_ACCESSIBILITY`
 *   - 判断是否已合并: `java.lang.Long.bitCount(flags) > 1`
 *   - 追加来源: `existing.flags or incomingSourceFlag`
 *
 * @property id              自增主键，无业务含义
 * @property packageName     来源包名 (com.tencent.mm / com.eg.android.AlipayGphone)
 * @property rawTitle        通知原始标题（无障碍路径为空字符串）
 * @property rawText         原始文本（通知路径=正文，无障碍路径=解析摘要）
 * @property amount          实付金额（单位: 元），null 表示未能识别
 * @property merchant        商户/收款方名称，null 表示未能识别
 * @property paymentChannel  支付通道: (计划处理双支付平台如美团->微信支付)
 * @property timestamp       交易时间戳（毫秒），通知路径=通知到达时间，无障碍路径=System.currentTimeMillis()
 * @property flags           数据来源标志位（Long 位掩码），见类文档 flags 定义
 * @property paymentMethod   支付方式详情 — 如 "余额宝(转出资金付款)", "中信银行信用卡(1111)"
 * @property originalAmount  订单原价 — 支付宝付款页展示原价和实付
 * @property discountInfo    优惠信息 — 如 "百次立减 -¥0.07"
 * @property merchantAlias   商户别名 — 用户自定义的商户显示名称
 * @property categoryId      消费分类 ID — 外键指向 [Category] 表
 * @property note            用户备注 — 自由文本备注
 * @property transactionId   交易流水号 — 从详情页补充或用户手动填写
 */
@Entity(
    tableName = "bill_records",
    indices = [
        // 按标志位 + 时间降序: UI 列表按来源过滤 + 排序
        Index(value = ["flags", "timestamp"]),
        // 按金额 + 时间窗口: 双源融合去重
        Index(value = ["timestamp", "amount"]),
    ]
)
data class BillRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "raw_title")
    val rawTitle: String,

    @ColumnInfo(name = "raw_text")
    val rawText: String,

    @ColumnInfo(name = "amount")
    val amount: Double? = null,

    @ColumnInfo(name = "merchant")
    val merchant: String? = null,

    @ColumnInfo(name = "payment_channel")
    val paymentChannel: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "flags")
    val flags: Long = 0L,

    @ColumnInfo(name = "window_id")
    val windowId: Int = 0,

    @ColumnInfo(name = "payment_method")
    val paymentMethod: String? = null,

    @ColumnInfo(name = "original_amount")
    val originalAmount: Double? = null,

    @ColumnInfo(name = "discount_info")
    val discountInfo: String? = null,

    @ColumnInfo(name = "merchant_alias")
    val merchantAlias: String? = null,

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String? = null,
) {
    companion object {
        // ═══════════ flags 标志位常量 ═══════════
        // 使用 Long 位掩码，多源合并时做位或 (or) 操作

        /** 数据来源 — 通知栏监听 */
        const val FLAG_NOTIFICATION = 0x0001L
        /** 数据来源 — 无障碍屏幕捕获 */
        const val FLAG_ACCESSIBILITY = 0x0002L
        /** 数据来源 — 微信记账本详情（预留） */
        const val FLAG_MINI_PROGRAM = 0x0004L
        /** 数据来源 — 支付宝账单详情（预留） */
        const val FLAG_BILL_DETAIL = 0x0008L
    }
}
