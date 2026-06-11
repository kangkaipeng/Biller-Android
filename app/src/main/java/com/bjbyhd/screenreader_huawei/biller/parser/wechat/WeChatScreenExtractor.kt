package com.bjbyhd.screenreader_huawei.biller.parser.wechat

import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 微信无障碍页面提取器
 *
 * 职责: 从微信无障碍事件中提取支付/转账信息。
 *
 * ## 算法
 *
 *   1. DFS 遍历整棵树 → 收集所有节点的 text + contentDescription
 *   2. 门禁校验: texts[0] == "支付成功" && texts[last] == "完成"
 *   3. 类型判定: texts[1] 含 "确认收款" → 转账；否则 → 付款
 *   4. 按位提取: texts[1] = 商户/转账描述, texts[2] = 金额
 *
 * ## 实测数据
 *
 * 付款页 A:  支付成功 | Cavalry98（**朋） | ¥1.01  | 完成
 * 付款页 B:  支付成功 | 杭州深度求索       | ￥1.00  | 完成
 * 转账页:    支付成功 | 待Cavalry98确认收款 | ￥1.06  | 完成
 * 旧订单(排除): 待Cavalry98收款 | ¥1.09 | ... 账单详情  (首元素≠支付成功)
 */
object WeChatScreenExtractor {

    private const val TAG = "Biller/WeChat"

    private const val GATE_FIRST = "支付成功"
    private const val GATE_LAST = "完成"
    private const val TRANSFER_MARKER = "确认收款"

    private val AMOUNT_REGEX = Regex("[¥￥](\\d+\\.\\d{2})")
    private val TRANSFER_MERCHANT_REGEX = Regex("待(.+)确认收款")

    private const val MAX_DEPTH = 80

    // ═══════════════════════════════════════════════════
    // 公开入口
    // ═══════════════════════════════════════════════════

    fun parse(rootNode: AccessibilityNodeInfo, receivedAt: Long): ParsedBill? {
        val texts = collectTexts(rootNode)
        CLog.i(TAG) { "[WeChat] 收集完成 — texts(${texts.size})=${texts.joinToString(" | ")}" }

        if (texts.isEmpty()) {
            CLog.d(TAG) { "[WeChat] texts 为空 → 跳过" }
            return null
        }

        // 门禁校验: 至少3项 + 首="支付成功" + 尾="完成"
        if (texts.size < 3 || texts.first() != GATE_FIRST || texts.last() != GATE_LAST) {
            CLog.d(TAG) { "[WeChat] 门禁未通过: first=${texts.first()} last=${texts.last()} → 非支付成功页" }
            return null
        }

        // 类型判定: texts[1] 含 "确认收款" → 转账
        return if (texts[1].contains(TRANSFER_MARKER)) {
            CLog.i(TAG) { "[WeChat] 判定为 → 转账页" }
            extractTransfer(texts, receivedAt)
        } else {
            CLog.i(TAG) { "[WeChat] 判定为 → 付款页" }
            extractPayment(texts, receivedAt)
        }
    }

    // ═══════════════════════════════════════════════════
    // 文本收集: DFS，收集所有节点的 text + contentDescription
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
    // 付款提取: texts[1]=商户, texts[2]=金额
    // ═══════════════════════════════════════════════════

    private fun extractPayment(texts: List<String>, receivedAt: Long): ParsedBill? {
        val amount = extractAmount(texts[2])
        if (amount == null) {
            CLog.w(TAG) { "[WeChat] 付款金额提取失败: texts[2]=${texts[2]}" }
            return null
        }
        val merchant = texts[1]
        CLog.i(TAG) { "[WeChat] 付款: amount=$amount merchant=$merchant" }
        return buildResult(amount, merchant, receivedAt = receivedAt)
    }

    // ═══════════════════════════════════════════════════
    // 转账提取: texts[1]="待xxx确认收款", texts[2]=金额
    // ═══════════════════════════════════════════════════

    private fun extractTransfer(texts: List<String>, receivedAt: Long): ParsedBill? {
        val amount = extractAmount(texts[2])
        if (amount == null) {
            CLog.w(TAG) { "[WeChat] 转账金额提取失败: texts[2]=${texts[2]}" }
            return null
        }
        val merchant = TRANSFER_MERCHANT_REGEX.find(texts[1])?.groupValues?.getOrNull(1)
        CLog.i(TAG) { "[WeChat] 转账: amount=$amount merchant=${merchant ?: "无"}" }
        return buildResult(amount, merchant, autoCategory = "转账", receivedAt = receivedAt)
    }

    // ═══════════════════════════════════════════════════
    // 通用
    // ═══════════════════════════════════════════════════

    private fun extractAmount(text: String): Double? {
        return AMOUNT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun buildResult(amount: Double, merchant: String?, autoCategory: String? = null, receivedAt: Long): ParsedBill {
        return ParsedBill(
            packageName = TargetConfig.WECHAT_PACKAGE,
            rawTitle = "",
            rawText = "amount=$amount" + if (merchant != null) " merchant=$merchant" else "",
            amount = amount,
            merchant = merchant,
            paymentChannel = TargetConfig.CHANNEL_NAMES[TargetConfig.WECHAT_PACKAGE] ?: "WEIXIN",
            timestamp = receivedAt,
            extras = if (autoCategory != null) mapOf("autoCategory" to autoCategory) else emptyMap(),
        )
    }
}
