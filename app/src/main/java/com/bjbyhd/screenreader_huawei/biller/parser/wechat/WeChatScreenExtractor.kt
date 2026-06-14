package com.bjbyhd.screenreader_huawei.biller.parser.wechat

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
 *   1. 门禁校验: texts.first() == "支付成功" && texts.last() in [完成, 返回商家, ...]
 *   2. 金额定位: 在所有 texts 中匹配 ¥/￥ + 数字 → 金额项
 *   3. 商户定位: 金额项的前一个文本 → 商户
 *   4. 类型判定: 商户含 "确认收款" → 转账；否则 → 付款
 *
 * texts 由 [BillEventProcessor.collectTexts] 统一收集后传入，本类不访问无障碍树。
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

    // ═══════════════════════════════════════════════════
    // 公开入口
    // ═══════════════════════════════════════════════════

    /**
     * 纯文本提取 — 接收已收集的 texts，不访问无障碍树。
     *
     * 供 [WeChatParser.handle] 调用。texts 由 [BillEventProcessor.collectTexts] 统一收集。
     * 门禁检查保留为防御性编程；若门禁失败返回 null，调用方负责诊断记录。
     *
     * @param texts     DFS 收集的全量文本列表
     * @param receivedAt 事件接收时间戳
     * @return 提取成功返回 [ParsedBill]，门禁失败或金额提取失败返回 null
     */
    fun extract(texts: List<String>, receivedAt: Long): ParsedBill? {
        CLog.i(TAG) { "[WeChat] extract — texts(${texts.size})=${texts.joinToString(" | ")}" }

        // 防御性门禁校验
        if (texts.isEmpty()) {
            CLog.d(TAG) { "[WeChat] texts 为空 → 跳过" }
            return null
        }
        if (texts.size < 3 || texts.first() != GATE_FIRST || texts.last() !in GATE_LAST_SET) {
            CLog.d(TAG) { "[WeChat] 门禁未通过: first=${texts.first()} last=${texts.last()} → 非支付成功页" }
            return null
        }

        // 金额定位
        val amountIdx = texts.indexOfFirst { AMOUNT_REGEX.matches(it) }
        if (amountIdx <= 0) {
            CLog.w(TAG) { "[WeChat] 未找到金额项 texts=${texts.joinToString(" | ")}" }
            return null
        }
        val amountText = texts[amountIdx]

        // 商户定位
        val merchant = texts[amountIdx - 1]

        // 类型判定 + 提取
        return if (merchant.contains(TRANSFER_MARKER)) {
            CLog.i(TAG) { "[WeChat] 判定为 → 转账页" }
            extractTransfer(merchant, amountText, receivedAt)
        } else {
            CLog.i(TAG) { "[WeChat] 判定为 → 付款页" }
            extractPayment(merchant, amountText, receivedAt)
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
