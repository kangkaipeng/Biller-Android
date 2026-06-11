package com.bjbyhd.screenreader_huawei.biller.data.model

/**
 * 全量账单导出记录 — 包含 BillRecord 的全部字段及关联分类名称
 *
 * 模块: feature/biller/data/model
 * 职责: 作为 CSV 全量导出/导入的数据传输对象，承载一条账单记录的完整信息。
 *
 * ## 与 BillRecord 的关系
 *
 *   [FullBillExport] 是 [BillRecord] 的"扁平化投影"：除包含所有数据库字段外，
 *   还通过 LEFT JOIN 关联 [Category] 表填充 [categoryName] 字段。
 *   这使得 CSV 导出对人类可读（分类名称而非 ID），同时保留了全部结构化数据
 *   以供导入还原。
 *
 * ## CSV 列序（18 列）
 *
 *   id, packageName, rawTitle, rawText, amount, merchant, paymentChannel,
 *   timestamp, source, mergeStatus, paymentMethod, originalAmount,
 *   discountInfo, merchantAlias, categoryId, categoryName, note, transactionId
 *
 * @property id              自增主键
 * @property packageName     来源包名（com.tencent.mm / com.eg.android.AlipayGphone）
 * @property rawTitle        通知原始标题（无障碍路径为空）
 * @property rawText         原始采集文本
 * @property amount          实付金额（元）
 * @property merchant        原始商户/收款方名称
 * @property paymentChannel  支付通道标识（WEIXIN / ALIPAY）
 * @property timestamp       交易时间戳（毫秒）
 * @property source          数据来源（NOTIFICATION / ACCESSIBILITY）
 * @property mergeStatus     合流状态（SINGLE / MERGED）
 * @property paymentMethod   支付方式详情
 * @property originalAmount  订单原价
 * @property discountInfo    优惠信息
 * @property merchantAlias   用户自定义商户别名
 * @property categoryId      分类 ID
 * @property categoryName    分类名称（关联查询得到，导出时人类可读，导入时用于反向查找 ID）
 * @property note            用户备注
 * @property transactionId   交易流水号
 */
data class FullBillExport(
    val id: Long,
    val packageName: String,
    val rawTitle: String,
    val rawText: String,
    val amount: Double?,
    val merchant: String?,
    val paymentChannel: String,
    val timestamp: Long,
    val source: String,
    val mergeStatus: String,
    val paymentMethod: String?,
    val originalAmount: Double?,
    val discountInfo: String?,
    val merchantAlias: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val note: String?,
    val transactionId: String?,
)

/**
 * CSV 导入结果统计
 *
 * 由 [IBillerImportExportBridge.importFromCsv] 返回，
 * 供 UI 层展示导入摘要。
 *
 * @property total    本次导入的总条数
 * @property inserted 成功新增的条数
 * @property skipped  跳过的条数（如金额缺失无法入库）
 * @property errors   错误详情列表（每条对应一行解析失败的原因）
 */
data class ImportResult(
    val total: Int,
    val inserted: Int,
    val skipped: Int,
    val errors: List<String>,
) {
    /** 简洁文本摘要，供 Toast 展示 */
    fun toSummary(): String = buildString {
        append("导入完成：共 $total 条")
        if (inserted > 0) append("，新增 $inserted 条")
        if (skipped > 0) append("，跳过 $skipped 条")
        if (errors.isNotEmpty()) append("，${errors.size} 条异常")
    }
}
