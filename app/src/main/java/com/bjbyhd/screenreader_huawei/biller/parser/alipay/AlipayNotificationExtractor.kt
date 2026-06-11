package com.bjbyhd.screenreader_huawei.biller.parser.alipay

import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.biller.parser.NotificationKeywordChecker
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 支付宝通知栏文本解析器 (v1)
 *
 * 模块: feature/biller/parser/notification
 * 职责: 专门解析支付宝支付通知，从通知文本中提取金额、商户名称、支付通道。
 *
 * ## 与通用 NotificationParser 的区别
 *
 *   - 仅处理支付宝包名 (com.eg.android.AlipayGphone)
 *   - 正则针对支付宝通知格式优化（实测数据驱动）
 *   - 微信通知仍由 [NotificationParser] 处理，两者独立演进
 *
 * ## 实测通知格式
 *
 *   - "你有一笔1.01元的支出，点击领取2个支付宝积分。使用花呗支付，请及时还款。"  ← merchant=null
 *   - "成功支付￥19.93"                                                    ← 无 merchant
 *   - "XX商户 收款￥19.93"                                                ← 空格分隔商户
 *   - "付款给 XX商户 ¥19.93"                                             ← "付款给" 前缀
 *   - "向XX转账￥100.00"                                                 ← 转账格式
 *
 * ## 商户提取策略
 *
 *   L1: "收款方/商户/商品/对方/付款给" 后跟的值
 *   L2: "商户名 ¥金额" 格式（商户名后紧跟金额的行首模式）
 *   L3: 接受 null（通知文本本身不含商户名时）
 *
 * ## 设计原则
 *
 *   - 无状态 object：所有输入通过方法参数传递，线程安全
 *   - isTargetData() 仅在支付宝包名下生效
 *   - 金额是入库必要条件，提取失败返回 null
 */
object AlipayNotificationExtractor  {

    private const val TAG = "Biller/AlipayNotify"

    // ═══════════════════════════════════════════════════════════════
    // 正则
    // ═══════════════════════════════════════════════════════════════

    /**
     * 支付宝通知金额正则
     *
     * 匹配:
     *   "¥19.93" / "￥19.93"           — 带符号
     *   "1.01元" / "19.93 元"          — 后缀
     *   "支付￥19.93" / "成功支付19.93"  — 嵌入文本
     */
    private val AMOUNT_REGEX = Regex(
        "[¥￥]\\s*([\\d,]+\\.\\d{2})" +
        "|([\\d,]+\\.\\d{2})\\s*元"
    )

    /**
     * 支付宝通知商户正则 (增强版)
     *
     * L1: "收款方/商户/商品/对方/付款给" 标签后跟的值
     *     例: "收款方: XX商户" → "XX商户"
     *
     * L2: 行首或空格后的商户名，后紧跟 ¥ 金额
     *     例: "XX商户 ¥19.93" → "XX商户"
     *
     * 注意: 支付宝通知中商户名可能完全不存在（如纯金额通知），此时返回 null 是正确的
     */
    private val MERCHANT_REGEX = Regex(
        "(?:收款方|商户|商品|对方|付款给)[:：\\s]*([\\u4e00-\\u9fa5()（）**a-zA-Z0-9]{2,30})(?:[，,。\\n]|\$)" +
        "|([\\u4e00-\\u9fa5()（）**a-zA-Z0-9]{2,30})\\s+[¥￥]"
    )

    // ═══════════════════════════════════════════════════════════════
    // IBillParser 实现
    // ═══════════════════════════════════════════════════════════════

    fun isTargetData(packageName: String, title: String, text: String): Boolean {
        // 🔑 P4-3.3: 使用 IBillParser 的统一 checkKeywords 工具方法
        return NotificationKeywordChecker.checkKeywords(packageName, title, text, TargetConfig.ALIPAY_PACKAGE)
    }

    fun parse(
        packageName: String,
        title: String,
        text: String,
        timestamp: Long
    ): ParsedBill? {
        if (packageName != TargetConfig.ALIPAY_PACKAGE) return null

        val normalized = "$title $text".replace("\n", " ")
            .replace("\\s+".toRegex(), " ").trim()
        CLog.d(TAG) { "[AlipayNotify] parse: 文本 → ${normalized.take(80)}" }

        // ── 金额提取 ──
        val amountMatch = AMOUNT_REGEX.find(normalized)
        val amount = amountMatch?.let { match ->
            match.groupValues
                .drop(1)
                .firstOrNull { it.isNotEmpty() }
                ?.replace(",", "")
                ?.toDoubleOrNull()
        }
        CLog.d(TAG) {
            "[AlipayNotify] 金额提取 → amount=$amount" +
            if (amountMatch != null) " (匹配: ${amountMatch.value})" else " (无匹配)"
        }

        if (amount == null) {
            CLog.d(TAG) { "[AlipayNotify] 金额提取失败, 返回 null" }
            return null
        }

        // ── 商户提取 ──
        val merchant = extractMerchant(normalized)
        CLog.d(TAG) {
            "[AlipayNotify] 商户提取 → merchant=${merchant ?: "无"}"
        }

        CLog.w(TAG) {
            "[AlipayNotify] 商户提取 → merchant=${merchant ?: "无"}"
        }
        return ParsedBill(
            packageName = packageName,
            rawTitle = title,
            rawText = text,
            amount = amount,
            merchant = merchant,
            paymentChannel = TargetConfig.CHANNEL_NAMES[packageName] ?: "ALIPAY",
            timestamp = timestamp,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 商户提取
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从支付宝通知文本中提取商户名
     *
     * 支付宝通知的特点:
     *   - 部分通知不含商户名（如 "你有一笔1.01元的支出"），此时返回 null
     *   - 部分通知含标签引导的商户名（如 "收款方: XX商户"）
     *   - 部分通知商户名后紧跟金额（如 "XX商户 ¥19.93"）
     */
    private fun extractMerchant(normalized: String): String? {
        val match = MERCHANT_REGEX.find(normalized) ?: return null
        return match.groupValues
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            ?.takeIf { it.length >= 2 }
    }
}
