package com.bjbyhd.screenreader_huawei.biller.parser.alipay

import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.diagnostic.ParseFailureDumper
import com.bjbyhd.screenreader_huawei.biller.parser.AccessibilityTreeDumper
import com.bjbyhd.screenreader_huawei.biller.parser.ParseResult
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 支付宝解析器 — 统一对外入口
 *
 * 职责: 接收监听层的原始事件，内部按来源分发给子提取器:
 *   - 无障碍事件 → [AlipayScreenExtractor]
 *   - 通知事件   → [AlipayNotificationExtractor]
 */
object AlipayParser {

    private const val TAG = "Biller/Alipay"

    /**
     * 支付宝统一无障碍入口 — 判定 + 提取 + 诊断。
     *
     * 供 [BillEventProcessor] 在 pkg-first 分发后调用。
     * texts 已由 [BillEventProcessor.collectTexts] 收集，rootNode 仅用于失败诊断的树 dump。
     *
     * ## 流程
     *   1. 内联判定: texts[0] 以 "支付成功"/"转账成功" 开头 → 进入提取
     *   2. Alipay 特有 "完成" 按钮检查 → 缺失则 dump
     *   3. [AlipayScreenExtractor.extract] 金额提取 + 分流
     *   4. 提取失败 → [ParseFailureDumper.dump] 记录 texts + 树
     *
     * ## 与 WeChatParser.handle 的区别
     *   - 支付宝暂无需独立 PageClassifier（页面类型仅支付/转账两种）
     *   - "完成" 按钮检查是支付宝特有门禁条件
     *   - 未来支付宝账单列表页出现时，可提取 [AlipayPageClassifier]
     *
     * @param texts     DFS 收集的全量文本列表
     * @param receivedAt 事件接收时间戳
     * @param rootNode  无障碍根节点（仅用于失败诊断的树 dump，不会在此方法内 recycle）
     * @return [ParseResult.SingleTransaction] / [ParseResult.NotTarget]
     */
    fun handle(
        texts: List<String>,
        receivedAt: Long,
        rootNode: AccessibilityNodeInfo,
    ): ParseResult {
        val first = texts.firstOrNull() ?: return ParseResult.NotTarget
        val isPayment = first.startsWith("支付成功")
        val isTransfer = first.startsWith("转账成功")

        if (!isPayment && !isTransfer) {
            return ParseResult.NotTarget
        }

        // 支付宝特有: "完成" 按钮检查
        if ("完成" !in texts) {
            ParseFailureDumper.dump(
                extractor = "Alipay",
                texts = texts,
                reason = "支付结果页缺少'完成'按钮，页面可能未渲染完整",
                treeDump = AccessibilityTreeDumper.dumpToString(rootNode),
            )
            return ParseResult.NotTarget
        }

        val result = AlipayScreenExtractor.extract(texts, receivedAt)
        return if (result != null) {
            ParseResult.SingleTransaction(result)
        } else {
            // 门禁通过但金额提取失败 → 诊断记录
            ParseFailureDumper.dump(
                extractor = "Alipay",
                texts = texts,
                reason = "实付金额提取失败 (L1嵌入格式与L2拆分格式均未命中)",
                treeDump = AccessibilityTreeDumper.dumpToString(rootNode),
            )
            ParseResult.NotTarget
        }
    }

    fun parseNotification(sbn: StatusBarNotification): ParsedBill? {
        val pkg = sbn.packageName
        val title = sbn.notification.extras?.getString("android.title") ?: ""
        val text = sbn.notification.extras?.getString("android.text") ?: ""
        val timestamp = sbn.postTime

        CLog.i(TAG) { "[Alipay] parseNotification: 开始 → title=${title.take(20)} text=${text.take(30)}" }

        if (!AlipayNotificationExtractor.isTargetData(pkg, title, text)) {
            CLog.d(TAG) { "[Alipay] parseNotification: isTargetData=false — 非支付通知" }
            return null
        }

        val result = AlipayNotificationExtractor.parse(pkg, title, text, timestamp)
        CLog.i(TAG) { "[Alipay] parseNotification: 完成 → amount=${result?.amount} merchant=${result?.merchant}" }
        return result
    }
}
