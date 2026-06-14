package com.bjbyhd.screenreader_huawei.biller.parser.wechat

import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.diagnostic.ParseFailureDumper
import com.bjbyhd.screenreader_huawei.biller.parser.AccessibilityTreeDumper
import com.bjbyhd.screenreader_huawei.biller.parser.ParseResult
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 微信解析器 — 统一对外入口
 *
 * 职责: 接收监听层的原始事件，内部按来源分发给子提取器:
 *   - 无障碍事件 → [WeChatScreenExtractor]
 *   - 通知事件   → [WeChatNotificationExtractor]
 *
 * 数据流日志:
 *   入口 → 分发 → 子提取器 → 结果汇总
 */
object WeChatParser {

    private const val TAG = "Biller/WeChat"

    /**
     * 解析无障碍事件（微信支付成功页）
     *
     * @return 解析成功返回 [ParsedBill]，页面不匹配或金额缺失返回 null
     */
    /**
     * 微信统一无障碍入口 — 分类 + 提取 + 诊断。
     *
     * 供 [BillEventProcessor] 在 pkg-first 分发后调用。
     * texts 已由 [BillEventProcessor.collectTexts] 收集，rootNode 仅用于失败诊断的树 dump。
     *
     * ## 流程
     *   1. [WeChatPageClassifier.classify] 判定页面类型
     *   2. PAYMENT → [WeChatScreenExtractor.extract] 单笔提取
     *   3. 提取失败 → [ParseFailureDumper.dump] 记录 texts + 树
     *   4. BILL_LIST → 预留 (P4)
     *
     * @param texts     DFS 收集的全量文本列表
     * @param receivedAt 事件接收时间戳
     * @param rootNode  无障碍根节点（仅用于失败诊断的树 dump，不会在此方法内 recycle）
     * @return [ParseResult.SingleTransaction] / [ParseResult.TransactionList] / [ParseResult.NotTarget]
     */
    fun handle(
        texts: List<String>,
        receivedAt: Long,
        rootNode: AccessibilityNodeInfo,
    ): ParseResult {
        return when (WeChatPageClassifier.classify(texts)) {
            PageType.PAYMENT -> {
                val result = WeChatScreenExtractor.extract(texts, receivedAt)
                if (result != null) {
                    ParseResult.SingleTransaction(result)
                } else {
                    // Classifier 判定为支付成功页但提取失败 → 诊断记录
                    ParseFailureDumper.dump(
                        extractor = "WeChat",
                        texts = texts,
                        reason = "支付成功页门禁通过但金额提取/转换失败",
                        treeDump = AccessibilityTreeDumper.dumpToString(rootNode),
                    )
                    ParseResult.NotTarget
                }
            }
            PageType.BILL_LIST -> {
                // 预留: P4 账单列表提取
                ParseResult.NotTarget
            }
            PageType.NOT_TARGET -> ParseResult.NotTarget
        }
    }

    /**
     * 解析通知栏事件（微信支付通知）
     *
     * @return 解析成功返回 [ParsedBill]，非支付通知或金额缺失返回 null
     */
    fun parseNotification(sbn: StatusBarNotification): ParsedBill? {
        val pkg = sbn.packageName
        val title = sbn.notification.extras?.getString("android.title") ?: ""
        val text = sbn.notification.extras?.getString("android.text") ?: ""
        val timestamp = sbn.postTime

        CLog.i(TAG) { "[WeChat] parseNotification: 开始 → title=${title.take(20)} text=${text.take(30)}" }

        if (!WeChatNotificationExtractor.isTargetData(pkg, title, text)) {
            CLog.d(TAG) { "[WeChat] parseNotification: isTargetData=false — 非支付通知" }
            return null
        }

        val result = WeChatNotificationExtractor.parse(pkg, title, text, timestamp)
        CLog.i(TAG) { "[WeChat] parseNotification: 完成 → amount=${result?.amount} merchant=${result?.merchant}" }
        return result
    }
}
