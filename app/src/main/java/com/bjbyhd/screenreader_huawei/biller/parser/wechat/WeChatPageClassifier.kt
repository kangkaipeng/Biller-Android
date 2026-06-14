package com.bjbyhd.screenreader_huawei.biller.parser.wechat

/**
 * 微信页面分类器 — 纯文本特征匹配
 *
 * ## 职责
 *   在不访问无障碍树的情况下，仅通过 texts 列表判定当前页面属于哪种类型。
 *   被 [WeChatParser.handle] 调用，决定后续走单笔提取还是账单列表提取。
 *
 * ## 判定逻辑
 *
 *   - **支付成功页**: texts[0] == "支付成功" 且 texts[last] ∈ ["完成", "返回商家"]
 *   - **账单列表页**: texts 中同时命中 ≥2 个筛选按钮特征（"全部账单"/"查找交易"/"收支统计"）
 *   - **非目标页**: 以上均不满足
 *
 * ## 设计边界
 *
 *   - **仅负责微信页面 (com.tencent.mm)**。支付宝有独立的分类逻辑，在 [AlipayParser] 内部实现。
 *   - 不做金额提取、不做商户识别。判定粒度仅到"页面类型"级别。
 *   - texts 由 [BillEventProcessor.collectTexts] 收集，本类不接触 [android.view.accessibility.AccessibilityNodeInfo]。
 *   - 纯函数，无状态，无副作用。
 *
 * ## 扩展性
 *
 *   微信小程序内的账单页（如美团跳转）若门禁特征不同，在此文件追加判定分支。
 *   微信记账本小程序的页面特征可能与钱包账单不同，需收集 tree sample 后调整。
 */
object WeChatPageClassifier {

    /** 支付成功页底部按钮 — 用于门禁校验 */
    private val GATE_LAST_SET = setOf("完成", "返回商家")

    /** 账单列表页筛选按钮 — 同时命中 ≥2 个时判定为账单列表 */
    private val BILL_LIST_MARKERS = setOf("全部账单", "查找交易", "收支统计")

    // ═══════════════════════════════════════════════════════════════
    // 公开方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据 texts 列表判定微信页面类型。
     *
     * @param texts DFS 收集的全量文本列表（非空，由调用方保证 size ≥ 3）
     * @return [PageType.PAYMENT] / [PageType.BILL_LIST] / [PageType.NOT_TARGET]
     */
    fun classify(texts: List<String>): PageType {
        if (texts.size < 3) return PageType.NOT_TARGET

        val first = texts.first()
        val last = texts.last()

        // ── 支付成功页 / 转账页 ──
        if (first == "支付成功" && last in GATE_LAST_SET) {
            return PageType.PAYMENT
        }

        // ── 账单列表页 ──
        val markerCount = BILL_LIST_MARKERS.count { it in texts }
        if (markerCount >= 2) {
            return PageType.BILL_LIST
        }

        return PageType.NOT_TARGET
    }
}

/**
 * 微信页面类型 — [WeChatPageClassifier.classify] 的返回值
 */
enum class PageType {
    /** 支付成功页 / 转账成功页 — 走单笔提取 */
    PAYMENT,
    /** 微信钱包账单列表 / 记账本 — 走列表提取 (P4) */
    BILL_LIST,
    /** 非目标页面 — 跳过 */
    NOT_TARGET,
}
