package com.bjbyhd.screenreader_huawei.biller.parser

/**
 * 解析结果 — 多态返回类型
 *
 * ## 职责
 *   取代 [ParsedBill]? 作为 Parser 层的统一返回类型。
 *   不同页面类型对应不同的结果子类，消除"一个事件=单笔交易"的隐含假设。
 *
 * ## 使用
 *   [BillEventProcessor] 通过 [when] 分支处理不同结果:
 *   - [SingleTransaction] → L1 去重 → Pipeline → DB → 通知 + 悬浮窗
 *   - [TransactionList]   → 去重 → 悬浮窗 PLUS 模式
 *   - [NotTarget]         → 跳过
 *
 * ## 扩展
 *   新增页面类型时在此文件追加子类，[BillEventProcessor] 的 [when] 会强制要求处理新分支。
 */
sealed class ParseResult {

    /** 单笔交易 — 支付成功页 / 转账成功页的提取结果 */
    data class SingleTransaction(val bill: ParsedBill) : ParseResult()

    /** 交易列表 — 账单列表页的提取结果 (P4) */
    data class TransactionList(val entries: List<RawBillEntry>) : ParseResult()

    /** 非目标页面 — 无需处理 */
    object NotTarget : ParseResult()
}

/**
 * 账单列表条目 — 从账单列表页提取的中间模型 (P4)
 *
 * 每个条目对应微信账单列表中的一笔交易记录。
 * 字段从 [android.widget.Button.desc] 中解析得到。
 *
 * @property merchant      商户名称 — 如 "luckin coffee"
 * @property amount        交易金额 (元)
 * @property timestampText 交易时间 — 如 "6月13日20点6分"
 * @property direction     收支方向 — 如 "支出" / "收入"
 * @property rawText       原始 desc 文本 — 备查
 */
data class RawBillEntry(
    val merchant: String?,
    val amount: Double?,
    val timestampText: String?,
    val direction: String?,
    val rawText: String,
)
