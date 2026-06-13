package com.bjbyhd.screenreader_huawei.biller.parser.wechat

import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.diagnostic.ParseFailureDumper
import com.bjbyhd.screenreader_huawei.biller.parser.AccessibilityTreeDumper
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
 *   2. 门禁校验: texts.first() == "支付成功" && texts.last() in [完成, 返回商家, ...]
 *   3. 金额定位: 在所有 texts 中匹配 ¥/￥ + 数字 → 金额项
 *   4. 商户定位: 金额项的前一个文本 → 商户
 *   5. 类型判定: 商户含 "确认收款" → 转账；否则 → 付款
 *
 * ## 实测数据
 *
 * 付款页 A:  支付成功 | Cavalry98（**朋） | ¥1.01  | 完成
 * 付款页 B:  支付成功 | 杭州深度求索       | ￥1.00  | 完成
 * 付款页 C:  支付成功 | 支付成功 | Cavalry98（**朋） | ¥0.05 | 完成 | 完成
 * 美团跳转:   支付成功 | 美团 | ￥29.90 | 返回商家
 * 转账页:    支付成功 | 支付成功 | 待Cavalry98确认收款 | ￥0.06 | 完成 | 完成
 * 旧订单(排除): 待Cavalry98收款 | ¥1.09 | ... 账单详情  (首元素≠支付成功)
 */
object WeChatScreenExtractor {

    private const val TAG = "Biller/WeChat"

    private const val GATE_FIRST = "支付成功"
    private val GATE_LAST_SET = setOf("完成", "返回商家")
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

        // 门禁校验: 至少3项 + 首="支付成功" + 尾 ∈ [完成, 返回商家, ...]
        if (texts.size < 3 || texts.first() != GATE_FIRST || texts.last() !in GATE_LAST_SET) {
            CLog.d(TAG) { "[WeChat] 门禁未通过: first=${texts.first()} last=${texts.last()} → 非支付成功页" }
            return null
        }

        // 金额定位: 在所有 texts 中匹配 ¥/￥ 正则
        val amountIdx = texts.indexOfFirst { AMOUNT_REGEX.matches(it) }
        if (amountIdx <= 0) {
            CLog.w(TAG) { "[WeChat] 未找到金额项 texts=${texts.joinToString(" | ")}" }
            ParseFailureDumper.dump(
                extractor = "WeChat",
                texts = texts,
                reason = "支付成功页未找到金额正则匹配项 (¥/￥ 符号 + 两位小数)",
                treeDump = AccessibilityTreeDumper.dumpToString(rootNode)
            )
            return null
        }
        val amountText = texts[amountIdx]

        // 商户定位: 金额项的前一个文本
        val merchant = texts[amountIdx - 1]

        // 类型判定: 商户含 "确认收款" → 转账
        val result = if (merchant.contains(TRANSFER_MARKER)) {
            CLog.i(TAG) { "[WeChat] 判定为 → 转账页" }
            extractTransfer(merchant, amountText, receivedAt)
        } else {
            CLog.i(TAG) { "[WeChat] 判定为 → 付款页" }
            extractPayment(merchant, amountText, receivedAt)
        }
        if (result == null) {
            ParseFailureDumper.dump(
                extractor = "WeChat",
                texts = texts,
                reason = "金额文本匹配成功但 toDouble 转换失败: amountText=$amountText",
                treeDump = AccessibilityTreeDumper.dumpToString(rootNode)
            )
        }
        return result
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
    // 付款提取: merchant 由 parse() 定位，amountText 由正则匹配
    // ═══════════════════════════════════════════════════

    private fun extractPayment(merchant: String, amountText: String, receivedAt: Long): ParsedBill? {
        val amount = extractAmount(amountText)
        if (amount == null) {
            CLog.w(TAG) { "[WeChat] 付款金额提取失败: amountText=$amountText" }
            return null
        }
        CLog.i(TAG) { "[WeChat] 付款: amount=$amount merchant=$merchant" }
        return buildResult(amount, merchant, receivedAt = receivedAt)
    }

    // ═══════════════════════════════════════════════════
    // 转账提取: merchant 为"待xxx确认收款"，正则提取收款人名
    // ═══════════════════════════════════════════════════

    private fun extractTransfer(merchant: String, amountText: String, receivedAt: Long): ParsedBill? {
        val amount = extractAmount(amountText)
        if (amount == null) {
            CLog.w(TAG) { "[WeChat] 转账金额提取失败: amountText=$amountText" }
            return null
        }
        val payeeName = TRANSFER_MERCHANT_REGEX.find(merchant)?.groupValues?.getOrNull(1)
        CLog.i(TAG) { "[WeChat] 转账: amount=$amount merchant=${payeeName ?: "无"}" }
        return buildResult(amount, payeeName, autoCategory = "转账", receivedAt = receivedAt)
    }

    // ═══════════════════════════════════════════════════
    // 通用
    // ═══════════════════════════════════════════════════

    /**
     * 从文本中提取金额（单位为元）。
     *
     * 使用正则 `[¥￥](\d+\.\d{2})` 匹配人民币符号后的两位小数金额：
     *
     * 例 1: 输入 `¥1.00` → 匹配 group(1)=`1.00` → 返回 `1.00`
     * 例 2: 输入 `￥298.50` → 匹配 group(1)=`298.50` → 返回 `298.50`
     *
     * @param text 待匹配的文本
     * @return 提取成功返回 Double 金额，格式不符或无法解析返回 null
     */
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
