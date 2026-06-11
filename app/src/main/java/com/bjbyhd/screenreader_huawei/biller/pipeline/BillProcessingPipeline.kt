package com.bjbyhd.screenreader_huawei.biller.pipeline

import android.content.Context
import com.bjbyhd.screenreader_huawei.biller.data.BillerDatabase
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecordDao
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 账单数据处理管道 — 先到先写，后到合并
 *
 * 职责:
 *   1. 接收 [ParsedBill]，将其存入 Room
 *   2. 存入前在 10 秒时间窗内查找异源同金额同通道的已有记录
 *      - 找到 → 字段合并 + update，flags 标记双源
 *      - 未找到 → insert 新记录
 *
 * 合并策略:
 *   - 商户: 无障碍优先（界面显示更完整）
 *   - 支付方式/原价/优惠: 无障碍独有
 *   - 时间戳: 保留首次写入的时间
 *
 * 初始化: [BillerApplication.onCreate] 中调用 [init]
 */
object BillProcessingPipeline {

    private const val TAG = "Pipeline"
    private const val FUSION_WINDOW_MS = 5_000L

    private var dao: BillRecordDao? = null

    fun init(context: Context) {
        dao = BillerDatabase.getInstance(context).billRecordDao()
    }

    /**
     * 处理一条解析完成的账单数据
     *
     * @param bill       解析结果
     * @param sourceFlag 数据来源标志位 ([BillRecord.FLAG_NOTIFICATION] / [BillRecord.FLAG_ACCESSIBILITY])
     * @param windowId   无障碍窗口 ID（用于跨 session 去重，通知路径传 0）
     * @return 插入或合并后的行 ID
     */
    suspend fun process(bill: ParsedBill, sourceFlag: Long, windowId: Int): Long {
        val d = dao ?: return -1L
        val amount = bill.amount ?: return -1L
        val merchant = bill.merchant ?: ""

        // L2: 持久化去重 — windowId + 金额 + 商户
        if (windowId > 0) {
            val duplicate = d.findByWindowId(windowId, amount, merchant)
            if (duplicate != null) {
                // 补充来源标志
                if ((duplicate.flags and sourceFlag) == 0L) {
                    d.update(duplicate.copy(flags = duplicate.flags or sourceFlag))
                    CLog.i(TAG) { "[补源] id=${duplicate.id} windowId=$windowId amount=$amount — 已有记录，补标志 → ${flagsDesc(duplicate.flags or sourceFlag)}" }
                } else {
                    CLog.d(TAG) { "[跳过] windowId=$windowId amount=$amount — 已存在且来源相同" }
                }
                return duplicate.id
            }
        }

        // L3: 异源融合
        val now = System.currentTimeMillis()
        val candidates = d.findInTimeWindow(
            from = now - FUSION_WINDOW_MS,
            to = now,
            amount = amount
        )
        val match = candidates.firstOrNull {
            it.paymentChannel == bill.paymentChannel
                    && (it.flags and sourceFlag) == 0L
        }

        return if (match != null) {
            val merged = merge(match, bill, sourceFlag)
            d.update(merged)
            CLog.i(TAG) { "[合并] id=${match.id} amount=$amount merchant=${merged.merchant} flags=${merged.flags}" }
            match.id
        } else {
            val record = toRecord(bill, sourceFlag, windowId)
            val rowId = d.insert(record)
            CLog.i(TAG) { "[新建] rowId=$rowId amount=$amount merchant=${record.merchant} flags=$sourceFlag windowId=$windowId" }
            rowId
        }
    }

    // ═══════════════════════════════════════════════════
    // 合并
    // ═══════════════════════════════════════════════════

    private fun merge(existing: BillRecord, incoming: ParsedBill, incomingFlag: Long): BillRecord {
        return existing.copy(
            // 商户: 无障碍优先，已有值不被覆盖
            merchant = existing.merchant ?: incoming.merchant,
            // 支付详情: 无障碍独有，补充
            paymentMethod = existing.paymentMethod ?: incoming.paymentMethod,
            originalAmount = existing.originalAmount ?: incoming.originalAmount,
            discountInfo = existing.discountInfo ?: incoming.discountInfo,
            // flags: 双源合并
            flags = existing.flags or incomingFlag,
        )
    }

    // ═══════════════════════════════════════════════════
    // 映射
    // ═══════════════════════════════════════════════════

    private fun toRecord(bill: ParsedBill, flag: Long, windowId: Int): BillRecord {
        return BillRecord(
            packageName = bill.packageName,
            rawTitle = bill.rawTitle,
            rawText = bill.rawText,
            amount = bill.amount,
            merchant = bill.merchant,
            paymentChannel = bill.paymentChannel,
            timestamp = bill.timestamp,
            flags = flag,
            windowId = windowId,
            paymentMethod = bill.paymentMethod,
            originalAmount = bill.originalAmount,
            discountInfo = bill.discountInfo,
            transactionId = bill.transactionId,
        )
    }

    private fun flagsDesc(flags: Long): String {
        val parts = mutableListOf<String>()
        if ((flags and BillRecord.FLAG_NOTIFICATION) != 0L) parts.add("通知")
        if ((flags and BillRecord.FLAG_ACCESSIBILITY) != 0L) parts.add("无障碍")
        return parts.joinToString("+").ifEmpty { "无" }
    }
}
