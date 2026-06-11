package com.bjbyhd.screenreader_huawei.biller.parser

/**
 * 解析后的账单中间数据 — Parser 层统一输出模型
 *
 * 模块: feature/biller/engine
 * 职责: 作为 NotificationParser 和 IScreenParser 实现类（WeChat/Alipay）的统一输出，
 *       由 data 层 ([IBillerServiceBridge.persist]) 进一步转换为 BillRecord 入库。
 *
 * ## 精度说明
 *
 * `amount` 和 `originalAmount` 使用 [Double] 类型。金额来源是正则提取的两位小数字符串
 * （如 "19.93"、"¥1.00"），通过 `toDoubleOrNull()` 转换。在两位小数的账单场景中
 * 浮点精度风险极低——金额仅用于比对和展示，不参与累加运算。
 *
 * ## 通道标识
 *
 * `paymentChannel` 使用 [String] 类型，有效值由 [TargetConfig.CHANNEL_NAMES] 定义
 * （当前为 "WEIXIN" 和 "ALIPAY"）。新增支付通道时在 TargetConfig 中添加映射即可，
 * 无需修改本数据类。
 *
 * ## 向后兼容
 *
 * 所有新增字段均设置了默认值（null 或 emptyMap），旧代码不需要修改即可编译通过。
 *
 * @property packageName     来源包名 (com.tencent.mm / com.eg.android.AlipayGphone)
 * @property rawTitle        原始标题 — 通知路径有值, 无障碍路径为空字符串
 * @property rawText         解析摘要 — 无障碍路径为 "amount=xx merchant=xx", 通知路径为原文
 * @property amount          实付金额 (单位: 元), null 表示未能识别
 * @property merchant        商户/对象名称, null 表示未能识别
 * @property paymentChannel  支付通道标识: "WEIXIN" / "ALIPAY" (来自 TargetConfig.CHANNEL_NAMES)
 * @property timestamp       交易时间戳 (毫秒), 无障碍路径为 System.currentTimeMillis()
 * @property paymentMethod   支付方式详情 — 如 "余额宝(转出资金付款)", "中信银行信用卡(1111)"
 * @property originalAmount  订单原价 (仅支付宝付款页有原价/实付之分)
 * @property discountInfo    优惠信息 — 如 "百次立减 -¥0.07"
 * @property transactionId   交易流水号 — 预留, 当前版本未采集
 * @property extras          通用扩展键值对 — 暂存未来新增字段, 避免频繁改数据类签名
 */
data class ParsedBill(
    // ═══════ 基础字段 ═══════
    val packageName: String,
    val rawTitle: String,
    val rawText: String,
    val amount: Double?,
    val merchant: String?,
    val paymentChannel: String,
    val timestamp: Long,

    // ═══════ 扩展字段 (v2, 默认 null) ═══════
    /** 支付方式详情 — 如 "余额宝(转出资金付款)", "中信银行信用卡(1111)" */
    val paymentMethod: String? = null,
    /** 订单原价 — 支付宝付款页展示原价和实付两个金额, 此字段存原价 */
    val originalAmount: Double? = null,
    /** 优惠信息 — 如 "百次立减 -¥0.07" */
    val discountInfo: String? = null,
    /** 交易流水号 — 预留字段 */
    val transactionId: String? = null,
    /** 通用键值扩展 — key: 标签文本, value: 值文本 */
    val extras: Map<String, String> = emptyMap(),
)
