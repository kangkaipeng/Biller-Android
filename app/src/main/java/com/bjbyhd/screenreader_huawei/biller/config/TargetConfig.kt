package com.bjbyhd.screenreader_huawei.biller.config

/**
 * 目标 App 统一过滤配置 — 全模块单一数据源
 *
 * 模块: feature/biller/engine
 * 职责: 集中定义"监控哪些 App、如何识别支付通知"的全部配置。
 *      所有 Parser 和 Service 均从此处读取，不再各自硬编码。
 *
 * 设计意图:
 *   - 新增目标 App（如云闪付）只需修改此文件，全局生效
 *   - 包名常量和关键字列表集中管理，避免多份副本不同步
 *   - 关键字列表供 NotificationParser.isTargetData() 做轻量预判
 *
 * 新增目标 App 的步骤:
 *   1. 在 [TARGET_PACKAGES] 中添加包名常量
 *   2. 在 [PAYMENT_KEYWORDS] 中添加对应的关键字列表
 *   3. 在 NotificationParser 中新增金额/商户正则（如需）
 */
object TargetConfig {

    // ═══════════════════════════════════════════════════════════════
    // 包名常量
    // ═══════════════════════════════════════════════════════════════

    /** 微信包名 */
    const val WECHAT_PACKAGE = "com.tencent.mm"
    /** 支付宝包名 */
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    // ═══════════════════════════════════════════════════════════════
    // 目标包名集合 — Service 和 Parser 的过滤依据
    // ═══════════════════════════════════════════════════════════════

    /** 所有需要监控的 App 包名集合 */
    val TARGET_PACKAGES: Set<String> = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE)

    // ═══════════════════════════════════════════════════════════════
    // 支付通知关键字 — 用于 NotificationParser.isTargetData() 轻量预判
    // ═══════════════════════════════════════════════════════════════

    /**
     * 各 App 的支付通知判定关键字
     *
     * 用于 NotificationParser.isTargetData() 做关键词轻量预判：
     * 通知的 title + text 中必须至少命中一个关键字才会进入完整解析。
     * 这样可以过滤掉微信的聊天通知、支付宝的活动推送等非支付通知。
     */
    val PAYMENT_KEYWORDS: Map<String, List<String>> = mapOf(
        WECHAT_PACKAGE to listOf("付款", "支付", "收款", "¥", "￥"),
        ALIPAY_PACKAGE to listOf("付款", "支付", "消费", "扣款", "¥", "￥", "转账", "支出", "详情")
    )

    // ═══════════════════════════════════════════════════════════════
    // 支付通道标识 — 入库时使用的通道名
    // ═══════════════════════════════════════════════════════════════

    /** 包名 → 支付通道名的映射 */
    val CHANNEL_NAMES: Map<String, String> = mapOf(
        WECHAT_PACKAGE to "WEIXIN",
        ALIPAY_PACKAGE to "ALIPAY"
    )
}
