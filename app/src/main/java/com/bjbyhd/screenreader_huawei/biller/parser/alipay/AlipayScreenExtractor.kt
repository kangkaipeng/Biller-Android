package com.bjbyhd.screenreader_huawei.biller.parser.alipay

import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 支付宝无障碍页面提取器
 *
 * 从 texts 列表（由 [BillEventProcessor.collectTexts] 统一收集）中提取支付/转账/优惠信息。
 * 本类不访问无障碍树。
 *
 * ## 解析流程
 *
 *   1. 门禁: texts.first() 以 "支付成功"/"转账成功" 开头 + "完成" 存在
 *   2. 实付金额: 嵌入格式 (Format A) → 拆分格式 (Format B) fallback
 *   3. 分流: 支付 (¥ pair 扫描 Body 起点) / 转账 (纯文本 Body)
 *
 * 完整策略文档见 docs/支付宝页面解析逻辑.md
 */
object AlipayScreenExtractor {

    private const val TAG = "Biller/Alipay"

    // ═══════════════════════════════════════════════════
    // 正则 & 常量
    // ═══════════════════════════════════════════════════

    /** 锚点正则 — 匹配嵌入格式 (Format A): "支付成功￥xx.xx"，金额从 group(2) 提取 */
    private val ANCHOR_REGEX = Regex("^(支付成功|转账成功)[¥￥](\\d+\\.\\d{2})$")

    /** 裸数字正则 — 拆分格式 (Format B): 紧跟裸 ¥ 后的纯两位小数 */
    private val BARE_NUMBER_REGEX = Regex("^\\d+\\.\\d{2}$")

    /**
     * Body ¥ 值正则 — 支付页 Body 起点的定位依据。
     * 匹配组合格式 "￥8.90"，与头部裸 "￥" 符号形态互斥。
     */
    private val YUAN_VALUE_REGEX = Regex("^[¥￥]\\d+\\.\\d{2}$")

    /** 底部按钮 — 不属于账单数据 */
    private val BUTTONS = setOf("完成", "回首页", "返回商家", "我的")

    /** 拆分格式裸 ¥ 搜索限域 — 仅在前 N 项中搜索，Body 区无裸 ¥ */
    private const val HEADER_SCAN_LIMIT = 5

    // ═══════════════════════════════════════════════════
    // 公开入口
    // ═══════════════════════════════════════════════════

    /**
     * 纯文本提取 — 接收已收集的 texts，不访问无障碍树。
     *
     * 供 [AlipayParser.handle] 调用。texts 由 [BillEventProcessor.collectTexts] 统一收集。
     * 门禁检查保留为防御性编程；若门禁失败返回 null。
     *
     * 预条件: 调用方应确保 texts[0] 以 "支付成功"/"转账成功" 开头且 "完成" in texts。
     *         如果预条件不满足，本方法通过门禁检查安全返回 null。
     *
     * @param texts     DFS 收集的全量文本列表
     * @param receivedAt 事件接收时间戳
     * @return 提取成功返回 [ParsedBill]，门禁失败或金额提取失败返回 null
     */
    fun extract(texts: List<String>, receivedAt: Long): ParsedBill? {
        CLog.i(TAG) { "[Alipay] extract — texts(${texts.size})=${texts.joinToString(" | ")}" }

        // 防御性门禁校验
        if (texts.size < 4) {
            CLog.d(TAG) { "[Alipay] texts 不足4项 → 跳过" }
            return null
        }

        val isTransfer = texts.first().startsWith("转账成功")
        val isPayment = texts.first().startsWith("支付成功")
        if (!isPayment && !isTransfer) {
            CLog.d(TAG) { "[Alipay] 门禁不匹配: texts[0]=${texts[0]} → 非支付/转账页" }
            return null
        }

        if ("完成" !in texts) {
            CLog.d(TAG) { "[Alipay] 缺少'完成' → 可能未渲染完成" }
            return null
        }

        // 实付金额提取
        val extractResult = extractPaidAmount(texts)
        if (extractResult == null) {
            CLog.w(TAG) { "[Alipay] 实付金额提取失败 → 跳过" }
            return null
        }
        val (paidAmount, paidEndIdx) = extractResult

        CLog.i(TAG) {
            "[Alipay] 判定为 → ${if (isTransfer) "转账" else "付款"} paidAmount=$paidAmount"
        }

        return if (isTransfer) {
            parseTransfer(texts, paidAmount, paidEndIdx, receivedAt)
        } else {
            parsePayment(texts, paidAmount, paidEndIdx, receivedAt)
        }
    }

    // ═══════════════════════════════════════════════════
    // 金额提取 — L1 嵌入 (Format A) → L2 拆分 (Format B)
    // ═══════════════════════════════════════════════════

    /**
     * 实付金额提取，两级 fallback。
     *
     * - L1: texts[0] 匹配 [ANCHOR_REGEX] (嵌入) → paidEndIdx = 2
     * - L2: 头部 [HEADER_SCAN_LIMIT] 项内找裸 "￥" + 裸数字 (拆分)
     *
     * L2 限域在头部: Body 区的 ¥ 均为组合格式节点，不会匹配此条件。
     *
     * @return Pair(实付金额, header 结束索引) 或 null
     */
    private fun extractPaidAmount(texts: List<String>): Pair<Double, Int>? {
        // ── L1: 嵌入格式 — texts[0] 含完整 "支付成功￥xx.xx" ──
        val anchorMatch = ANCHOR_REGEX.find(texts[0])
        if (anchorMatch != null) {
            val amount = anchorMatch.groupValues[2].toDoubleOrNull()
            if (amount != null) {
                CLog.d(TAG) { "[Alipay] 金额 L1 命中 (嵌入): texts[0]=${texts[0]} → $amount" }
                return Pair(amount, 2) // header: [锚点, 类型文本, 裸金额]
            }
        }

        // ── L2: 拆分格式 — 头部内搜索裸 ¥ + 裸数字 ──
        val limit = minOf(texts.size - 1, HEADER_SCAN_LIMIT)
        for (i in 0 until limit) {
            val isBareYuan = texts[i] == "￥" || texts[i] == "¥"
            val nextIsBareNumber = BARE_NUMBER_REGEX.matches(texts[i + 1])
            if (isBareYuan && nextIsBareNumber) {
                val amount = texts[i + 1].toDoubleOrNull()
                if (amount != null) {
                    CLog.d(TAG) {
                        "[Alipay] 金额 L2 命中 (拆分): texts[$i]='${texts[i]}' texts[${i + 1}]='${texts[i + 1]}' → $amount"
                    }
                    return Pair(amount, i + 1)
                }
            }
        }

        CLog.d(TAG) { "[Alipay] 金额 L1+L2 均未命中" }
        return null
    }

    // ═══════════════════════════════════════════════════
    // 支付成功 — ¥ pair 自适应 Body 定位
    // ═══════════════════════════════════════════════════

    /**
     * 支付页: ¥ pair 扫描定位 Body 起点 → chunk 配对 → extractPayment。
     *
     * Body 起点 = 第一个满足 texts\[i\] ∉ BUTTONS 且 texts\[i+1\] 匹配 ¥N.NN 的位置。
     * 扫描从 paidEndIdx+1 开始，自动跳过头部噪音 (按钮/推广等)。
     * YUAN_VALUE_REGEX 要求 ¥ 与数字在同一文本中，头部裸 "￥" 不会误匹配。
     */
    private fun parsePayment(
        texts: List<String>,
        paidAmount: Double,
        paidEndIdx: Int,
        receivedAt: Long,
    ): ParsedBill? {
        val bodyStart = findBodyStartByYuanPair(texts, paidEndIdx + 1)
        val body = texts.drop(bodyStart).dropLastWhile { it in BUTTONS }
        val pairs = body.chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
        CLog.i(TAG) { "[Alipay] 键值对: $pairs" }
        return extractPayment(pairs, paidAmount, receivedAt)
    }

    /** 扫描 texts\[startIdx..\] 中第一个 (key, ¥N.NN) 的位置。未找到返回 startIdx 作为 fallback。 */
    private fun findBodyStartByYuanPair(texts: List<String>, startIdx: Int): Int {
        for (i in startIdx until texts.size - 1) {
            if (texts[i] !in BUTTONS && YUAN_VALUE_REGEX.matches(texts[i + 1])) {
                CLog.d(TAG) { "[Alipay] ¥ pair 定位: i=$i key='${texts[i]}' value='${texts[i + 1]}'" }
                return i
            }
        }
        CLog.d(TAG) { "[Alipay] 未找到 ¥ pair, fallback startIdx=$startIdx" }
        return startIdx
    }

    // ═══════════════════════════════════════════════════
    // 转账成功 — 纯文本 Body 定位
    // ═══════════════════════════════════════════════════

    /**
     * 转账页: Body 从 paidEndIdx+1 开始 → chunk 配对 → extractTransfer。
     *
     * 转账页 Body 无 ¥ 键值对，¥ pair 扫描不适用。
     * 注意: 拆分格式转账页无实测数据，当前 paidEndIdx+1 推断可能需调整。
     */
    private fun parseTransfer(
        texts: List<String>,
        paidAmount: Double,
        paidEndIdx: Int,
        receivedAt: Long,
    ): ParsedBill? {
        val body = texts.drop(paidEndIdx + 1).dropLastWhile { it in BUTTONS }
        val pairs = body.chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
        CLog.i(TAG) { "[Alipay] 键值对: $pairs" }
        return extractTransfer(pairs, paidAmount, receivedAt)
    }

    // ═══════════════════════════════════════════════════
    // 付款提取
    // ═══════════════════════════════════════════════════

    private fun extractPayment(pairs: Map<String, String>, paidAmount: Double, receivedAt: Long): ParsedBill? {
        // 商户: 第一个 value 为 ¥正数 (非负) 的 key
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
            discountInfo = discounts.joinToString(" ") { (name, amount) -> "$name -¥${amount}" }.takeIf { it.isNotEmpty() },
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
