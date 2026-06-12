package com.bjbyhd.screenreader_huawei.biller.service

import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.biller.parser.alipay.AlipayParser
import com.bjbyhd.screenreader_huawei.biller.parser.wechat.WeChatParser
import com.bjbyhd.screenreader_huawei.biller.callback.CaptureNotifier
import com.bjbyhd.screenreader_huawei.biller.pipeline.BillProcessingPipeline
import com.bjbyhd.screenreader_huawei.biller.pipeline.ProcessResult
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 支付事件处理器 — 监听层的唯一下游入口
 *
 * 数据流:
 * ```
 * AccessibilityService ─┐
 *                       ├→ BillEventProcessor → Parser → 去重 → Pipeline → DB
 * NotificationService  ─┘
 * ```
 */
object BillEventProcessor {

    private const val TAG = "BillEvent"
    private const val DEDUP_TTL_MS = 2_000L
    private const val DEDUP_CONTENT_TTL_MS = 1_000L  // 跨窗口去重（更短 TTL）
    private const val MAX_CACHE_SIZE = 50

    private val recentHashes = LinkedHashMap<String, Long>()
    private val recentContentHashes = LinkedHashMap<String, Long>()  // 不带 windowId 的去重
    private var scope: CoroutineScope? = null

    fun init(scope: CoroutineScope) {
        this.scope = scope
    }

    // ═══════════ 无障碍事件 ═══════════

    fun onAccessibilityEvent(pkg: String, event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?) {
        val receivedAt = System.currentTimeMillis()
        val windowId = event.windowId

        CLog.i(TAG) { "[A11y→分发] pkg=$pkg windowId=$windowId type=${event.eventType}" }

        val result = when (pkg) {
            TargetConfig.WECHAT_PACKAGE -> {
                if (rootNode != null) WeChatParser.parseAccessibility(rootNode, receivedAt) else null
            }
            TargetConfig.ALIPAY_PACKAGE -> {
                if (rootNode != null) AlipayParser.parseAccessibility(rootNode, receivedAt) else null
            }
            else -> {
                CLog.d(TAG) { "[A11y→丢弃] pkg=$pkg 不在目标列表中" }
                null
            }
        }

        rootNode?.recycle()

        if (result != null && isDuplicate(windowId, result)) {
            CLog.d(TAG) { "[去重→跳过] windowId=$windowId amount=${result.amount} merchant=${result.merchant ?: "无"}" }
            return
        }

        if (result != null) {
            CLog.i(TAG) { "[A11y→管道] amount=${result.amount} merchant=${result.merchant ?: "无"}" }
            scope?.launch {
                val pr = BillProcessingPipeline.process(result, BillRecord.FLAG_ACCESSIBILITY, windowId)
                pr.record?.let { CaptureNotifier.onBillSaved(it) }
            }
        }
    }

    // ═══════════ 通知栏事件 ═══════════

    fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        CLog.i(TAG) { "[Notify→分发] pkg=$pkg" }

        if (pkg in setOf(TargetConfig.WECHAT_PACKAGE, TargetConfig.ALIPAY_PACKAGE)) {
            NotificationLogger.log(sbn)
        }

        val result = when (pkg) {
            TargetConfig.WECHAT_PACKAGE -> WeChatParser.parseNotification(sbn)
            TargetConfig.ALIPAY_PACKAGE -> AlipayParser.parseNotification(sbn)
            else -> {
                CLog.d(TAG) { "[Notify→丢弃] pkg=$pkg 不在目标列表中" }
                null
            }
        }

        if (result != null && isDuplicate(windowId = 0, result)) {
            CLog.d(TAG) { "[去重→跳过] notify amount=${result.amount} merchant=${result.merchant ?: "无"}" }
            return
        }

        if (result != null) {
            CLog.i(TAG) { "[Notify→管道] amount=${result.amount} merchant=${result.merchant ?: "无"}" }
            scope?.launch {
                BillProcessingPipeline.process(result, BillRecord.FLAG_NOTIFICATION, windowId = 0)
                // 通知栏源不通知用户（用户已看到通知栏）
            }
        }
    }

    // ═══════════ 去重 ═══════════

    @Synchronized
    private fun isDuplicate(windowId: Int, bill: ParsedBill): Boolean {
        val now = System.currentTimeMillis()
        val contentHash = "${bill.paymentChannel}|${bill.amount}|${bill.merchant ?: ""}"
        val windowHash = "$contentHash|$windowId"

        // L1a: 跨窗口去重 — 不同 windowId 但相同内容（支付宝多窗口渲染）
        if (isCached(recentContentHashes, contentHash, now, DEDUP_CONTENT_TTL_MS)) {
            return true
        }

        // L1b: 同窗口去重 — 同一 windowId 重复回调
        if (isCached(recentHashes, windowHash, now, DEDUP_TTL_MS)) {
            return true
        }

        return false
    }

    private fun isCached(map: LinkedHashMap<String, Long>, hash: String, now: Long, ttl: Long): Boolean {
        if (map.size > MAX_CACHE_SIZE) {
            map.entries.removeAll { now - it.value > ttl }
        }
        val lastTime = map[hash]
        return if (lastTime != null && now - lastTime < ttl) {
            true
        } else {
            map[hash] = now
            false
        }
    }
}
