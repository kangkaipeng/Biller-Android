package com.bjbyhd.screenreader_huawei.biller.pipeline

import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord

/**
 * 账单数据处理结果 — [BillProcessingPipeline.process] 的返回值
 *
 * 职责: 告知 caller DB 写入发生了什么，callee 不参与业务判断。
 *       通知决策由 caller（[BillEventProcessor]）根据来源上下文做出。
 */
sealed class ProcessResult {

    /** 本次操作后的完整 BillRecord，[Skipped] 时为 null */
    abstract val record: BillRecord?

    /** 首次出现，新插入记录 */
    data class Created(override val record: BillRecord) : ProcessResult()

    /** L3 异源融合 — 与旧记录合并字段 */
    data class Merged(override val record: BillRecord) : ProcessResult()

    /** L2 补来源标志 — 旧记录增加新的 flags 位 */
    data class Patched(override val record: BillRecord) : ProcessResult()

    /** L2 同源已存在 — 无 DB 操作，跳过 */
    data object Skipped : ProcessResult() {
        override val record: BillRecord? get() = null
    }
}
