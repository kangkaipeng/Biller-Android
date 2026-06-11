package com.bjbyhd.screenreader_huawei.biller.parser.alipay

import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 支付宝无障碍页面提取器
 *
 * 职责: 从支付宝无障碍事件中提取支付/转账/优惠信息。
 *
 * ## 算法
 *
 *   1. DFS 遍历 → 收集所有节点的 text + contentDescription
 *   2. texts[0] 作为锚点，匹配 "支付成功￥xx.xx" 或 "转账成功￥xx.xx"
 *   3. texts[0→2] 为固定前缀: [锚点, 类型文本, 裸金额]
 *   4. texts[3..] 两两成对 → 键值对 Map
 *   5. 尾部按钮 (完成/回首页/返回商家) 丢弃
 *
 * ## 实测数据
 *
 * texts[0]           texts[1]    texts[2]  texts[3..] 成对
 * ─────────────────────────────────────────────────────
 * 支付成功￥1.01      支付成功     1.01      喜乐街奶茶店 | ￥1.01 | 交易方式 | 花呗 | 完成 | 回首页
 * 支付成功￥1.99      支付成功     1.99      杭州深度求索 | ￥2.00 | 百次立减 | -￥0.05 | 付款方式 | 中信银行信用卡 | 回首页 | 返回商家 | 完成
 * 转账成功￥0.53      转账成功     0.53      收款方 | 鄂不韡韡 | 交易方式 | 余额宝 | 完成
 *
 * 切掉 [0][1][2] + 切尾部按钮 → 对:
 *   {喜乐街奶茶店: ￥1.01, 交易方式: 花呗}
 *   {杭州深度求索: ￥2.00, 百次立减: -￥0.05, 付款方式: 中信银行信用卡}
 *   {收款方: 鄂不韡韡, 交易方式: 余额宝}
 */
object AlipayScreenExtractor {

    private const val TAG = "Biller/Alipay"

    private val ANCHOR_REGEX = Regex("^(支付成功|转账成功)[¥￥](\\d+\\.\\d{2})$")

    private val BUTTONS = setOf("完成", "回首页", "返回商家", "我的")
    private const val TRANSFER_TYPE = "转账成功"

    private const val MAX_DEPTH = 80

    // ═══════════════════════════════════════════════════
    // 公开入口
    // ═══════════════════════════════════════════════════

    fun parse(rootNode: AccessibilityNodeInfo, receivedAt: Long): ParsedBill? {
        val texts = collectTexts(rootNode)
        CLog.i(TAG) { "[Alipay] 收集完成 — texts(${texts.size})=${texts.joinToString(" | ")}" }

        if (texts.size < 4) {
            CLog.d(TAG) { "[Alipay] texts 不足4项 → 跳过" }
            return null
        }

        // texts[0] 作为锚点
        val anchorMatch = ANCHOR_REGEX.find(texts[0]) ?: run {
            CLog.d(TAG) { "[Alipay] 锚点不匹配: texts[0]=${texts[0]} → 非支付页" }
            return null
        }
        val type = anchorMatch.groupValues[1]
        val paidAmount = anchorMatch.groupValues[2].toDouble()

        if ("完成" !in texts) {
            CLog.d(TAG) { "[Alipay] 缺少'完成' → 可能未渲染完成" }
            return null
        }

        CLog.i(TAG) { "[Alipay] 判定为 → ${if (type == TRANSFER_TYPE) "转账" else "付款"} paidAmount=$paidAmount" }

        // 切 [0][1][2] + 切尾部按钮 → 成对
        val body = texts.drop(3).dropLastWhile { it in BUTTONS }
        val pairs = body.chunked(2).associate { it[0] to it[1] }
        CLog.i(TAG) { "[Alipay] 键值对: $pairs" }

        return if (type == TRANSFER_TYPE) {
            extractTransfer(pairs, paidAmount, receivedAt)
        } else {
            extractPayment(pairs, paidAmount, receivedAt)
        }
    }

    // ═══════════════════════════════════════════════════
    // 文本收集
    // ═══════════════════════════════════════════════════

    private fun collectTexts(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        collectRecursive(node, result, depth = 0)
        return result
    }

    private fun collectRecursive(node: AccessibilityNodeInfo?, result: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            var child: AccessibilityNodeInfo? = null
            try {
                child = node.getChild(i)
                collectRecursive(child, result, depth + 1)
            } finally {
                child?.recycle()
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 付款提取
    // ═══════════════════════════════════════════════════

    private fun extractPayment(pairs: Map<String, String>, paidAmount: Double, receivedAt: Long): ParsedBill? {
        // 商户: value 为 ¥xx.xx（正数，非负）的 key
        val merchant = pairs.entries
            .firstOrNull { (_, v) ->
                (v.startsWith("¥") || v.startsWith("￥")) && !v.startsWith("-")
            }?.key

        // 原价: 商户对应的 ¥ 金额
        val originalAmount = merchant?.let { parseAmount(pairs[it]) }

        // 优惠: value 以 -¥ 或 -￥ 开头的项
        val discounts = pairs.entries
            .filter { (_, v) -> v.startsWith("-¥") || v.startsWith("-￥") }
            .map { it.key to (parseAmount(it.value) ?: 0.0) }

        if (discounts.isNotEmpty()) {
            val discountTotal = discounts.sumOf { it.second }
            val computed = (originalAmount ?: paidAmount) + discountTotal
            CLog.i(TAG) { "[Alipay] 优惠校验: 原价=$originalAmount 折扣=$discounts 计算=$computed 实付=$paidAmount" }
        }

        val paymentMethod = pairs["付款方式"] ?: pairs["交易方式"]

        CLog.i(TAG) { "[Alipay] 付款: merchant=$merchant paid=$paidAmount original=$originalAmount discounts=$discounts method=$paymentMethod" }

        return ParsedBill(
            packageName = TargetConfig.ALIPAY_PACKAGE,
            rawTitle = "",
            rawText = buildRawText(paidAmount, merchant, paymentMethod),
            amount = paidAmount,
            merchant = merchant,
            paymentChannel = TargetConfig.CHANNEL_NAMES[TargetConfig.ALIPAY_PACKAGE] ?: "ALIPAY",
            timestamp = receivedAt,
            paymentMethod = paymentMethod,
            originalAmount = originalAmount,
            discountInfo = discounts.joinToString(" ") { (name, amount) -> "$name -¥$amount" }.takeIf { it.isNotEmpty() },
        )
    }

    // ═══════════════════════════════════════════════════
    // 转账提取
    // ═══════════════════════════════════════════════════

    private fun extractTransfer(pairs: Map<String, String>, paidAmount: Double, receivedAt: Long): ParsedBill? {
        val merchant = pairs["收款方"]
        val paymentMethod = pairs["交易方式"]

        CLog.i(TAG) { "[Alipay] 转账: merchant=$merchant amount=$paidAmount method=$paymentMethod" }

        return ParsedBill(
            packageName = TargetConfig.ALIPAY_PACKAGE,
            rawTitle = "",
            rawText = buildRawText(paidAmount, merchant, paymentMethod),
            amount = paidAmount,
            merchant = merchant,
            paymentChannel = TargetConfig.CHANNEL_NAMES[TargetConfig.ALIPAY_PACKAGE] ?: "ALIPAY",
            timestamp = receivedAt,
            paymentMethod = paymentMethod,
            extras = mapOf("autoCategory" to "转账"),
        )
    }

    // ═══════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════

    private fun parseAmount(text: String?): Double? {
        return text?.replace("¥", "")?.replace("￥", "")?.toDoubleOrNull()
    }

    private fun buildRawText(amount: Double, merchant: String?, method: String?): String {
        return buildString {
            append("amount=$amount")
            if (merchant != null) append(" merchant=$merchant")
            if (method != null) append(" method=$method")
        }
    }
}
