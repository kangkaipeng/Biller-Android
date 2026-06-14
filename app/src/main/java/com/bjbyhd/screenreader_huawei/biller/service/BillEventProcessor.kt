package com.bjbyhd.screenreader_huawei.biller.service

import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.biller.overlay.FloatingOverlayService
import com.bjbyhd.screenreader_huawei.biller.overlay.OverlayMode
import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.data.biller.BillRecord
import com.bjbyhd.screenreader_huawei.biller.parser.ParseResult
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
 * 数据流 (v6):
 * ```
 * AccessibilityService ─┐
 *                       ├→ BillEventProcessor
 *                       │     ├─ collectTexts (唯一 DFS)
 *                       │     ├─ L0 阶段指纹去重
 *                       │     ├─ pkg-first 分发 → Parser.handle()
 *                       │     └─ 结果分发 → L1 去重 → Pipeline → DB
 * NotificationService  ─┘
 * ```
 */
object BillEventProcessor {

    private const val TAG = "BillEvent"
    private const val DEDUP_TTL_MS = 2_000L
    private const val DEDUP_CONTENT_TTL_MS = 1_000L  // 跨窗口去重（更短 TTL）
    private const val MAX_CACHE_SIZE = 50

    // ═══════════ L0 阶段指纹去重 — 在完整解析前拦截重复回调 ═══════════
    //
    // 策略: texts.size 作为阶段分界 + head(3) 捕获页面标题 + tail(2) 捕获底部特征。
    // 同一页面渲染过程中，同阶段多次回调 → fingerprint 碰撞 → 拦截。
    // 页面渐进渲染到新阶段时 → size 变化 → 新 fingerprint → 放行。

    private const val DEDUP_STAGE_TTL_MS = 1_500L
    private const val MAX_STAGE_CACHE_SIZE = 30

    private val recentStageKeys = LinkedHashMap<String, Long>()

    private val recentHashes = LinkedHashMap<String, Long>()
    private val recentContentHashes = LinkedHashMap<String, Long>()  // 不带 windowId 的去重
    private var scope: CoroutineScope? = null

    @Suppress("StaticFieldLeak")
    private var appContext: Context? = null  // ApplicationContext，可安全持有

    fun init(scope: CoroutineScope, context: Context) {
        this.scope = scope
        this.appContext = context.applicationContext
    }

    // ═══════════ 无障碍事件 — 4-phase 处理 ═══════════

    fun onAccessibilityEvent(pkg: String, event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?) {
        rootNode ?: return
        val receivedAt = System.currentTimeMillis()
        val windowId = event.windowId

        CLog.i(TAG) { "[A11y→分发] pkg=$pkg windowId=$windowId type=${event.eventType}" }

        try {
            // ══════════════════════════════════════════════
            // Phase 1: 收集 texts（唯一一次 DFS）
            // ══════════════════════════════════════════════
            val texts = collectTexts(rootNode)

            // ══════════════════════════════════════════════
            // Phase 2: L0 阶段指纹去重
            // ══════════════════════════════════════════════
            val stageKey = buildStageFingerprint(pkg, windowId, texts)
            if (isStageDuplicate(stageKey)) {
                CLog.d(TAG) { "[L0去重→跳过] pkg=$pkg windowId=$windowId size=${texts.size}" }
                return
            }

            // ══════════════════════════════════════════════
            // Phase 3: pkg-first 分发 → Parser.handle()
            // ══════════════════════════════════════════════
            val parseResult = when (pkg) {
                TargetConfig.WECHAT_PACKAGE -> WeChatParser.handle(texts, receivedAt, rootNode)
                TargetConfig.ALIPAY_PACKAGE -> AlipayParser.handle(texts, receivedAt, rootNode)
                else -> {
                    CLog.d(TAG) { "[A11y→丢弃] pkg=$pkg 不在目标列表中" }
                    ParseResult.NotTarget
                }
            }

            // ══════════════════════════════════════════════
            // Phase 4: 结果分发
            // ══════════════════════════════════════════════
            when (parseResult) {
                is ParseResult.SingleTransaction -> {
                    val bill = parseResult.bill
                    if (isDuplicate(windowId, bill)) {
                        CLog.d(TAG) { "[L1去重→跳过] windowId=$windowId amount=${bill.amount} merchant=${bill.merchant ?: "无"}" }
                        return
                    }
                    CLog.i(TAG) { "[A11y→管道] amount=${bill.amount} merchant=${bill.merchant ?: "无"}" }
                    scope?.launch {
                        val pr = BillProcessingPipeline.process(bill, BillRecord.FLAG_ACCESSIBILITY, windowId)
                        pr.record?.let { record ->
                            CaptureNotifier.onBillSaved(record)
                            appContext?.let { ctx ->
                                FloatingOverlayService.show(ctx, record, OverlayMode.CHECKMARK)
                            }
                        }
                    }
                }
                is ParseResult.TransactionList -> {
                    // P2-6: FloatingOverlayService.show(appContext, parseResult.entries, OverlayMode.PLUS)
                }
                is ParseResult.NotTarget -> { /* skip */ }
            }
        } finally {
            rootNode.recycle()
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

    // ═══════════ L1 内容去重 ═══════════

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

    // ═══════════ 树遍历 — 唯一 DFS 入口 ═══════════

    /** 最大递归深度 — 防止异常深层嵌套导致栈溢出 */
    private const val MAX_TREE_DEPTH = 80

    /**
     * 从无障碍根节点 DFS 收集所有文本内容。
     *
     * ## 为什么放在这里
     *   texts 是 L0 指纹去重、页面分类、内容提取三者的共同输入，
     *   在 [BillEventProcessor] 层收集一次，避免每个下游组件各自遍历树。
     *
     * ## 算法
     *   - DFS 先序遍历整棵 [AccessibilityNodeInfo] 树
     *   - 每个节点收集: [AccessibilityNodeInfo.text] + [AccessibilityNodeInfo.contentDescription]
     *   - 空字符串和纯空白文本被过滤（trim + isEmpty）
     *   - 超出 [MAX_TREE_DEPTH] 时停止递归
     *   - 每个 child 通过 [AccessibilityNodeInfo.getChild] 获取，使用后立即 [AccessibilityNodeInfo.recycle]
     *
     * ## 线程安全
     *   [AccessibilityNodeInfo] 不是线程安全对象。此方法必须在接收无障碍回调的线程
     *   （主线程）上同步调用，不可以在协程中异步执行。
     *
     * ## 性能
     *   - 支付成功页 (30-50 节点): ~2ms
     *   - 账单列表页 (100-300 节点): ~8-15ms
     *   - 超出 [MAX_TREE_DEPTH]=80 自动截断
     *
     * @param rootNode 无障碍根节点（不会被 recycle，由调用方管理生命周期）
     * @return 按 DFS 顺序排列的文本列表（可能为空，但不会为 null）
     */
    private fun collectTexts(rootNode: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        collectRecursive(rootNode, result, depth = 0)
        return result
    }

    private fun collectRecursive(
        node: AccessibilityNodeInfo?,
        result: MutableList<String>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_TREE_DEPTH) return

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

    // ═══════════ L0 阶段指纹去重 ═══════════

    /**
     * 构建阶段指纹 — 在完整解析前拦截重复回调。
     *
     * 指纹 = pkg + windowId + texts.size + head(3) + tail(2)。
     * texts.size 作为阶段分界：页面渐进渲染过程中，不同阶段 texts 数量不同。
     * head + tail 保证不同页面不会碰撞。
     *
     * @param pkg      来源包名
     * @param windowId 无障碍窗口 ID
     * @param texts    DFS 收集到的全量文本列表
     */
    private fun buildStageFingerprint(pkg: String, windowId: Int, texts: List<String>): String {
        val head = texts.take(3).joinToString("|")
        val tail = texts.takeLast(2).joinToString("|")
        val size = texts.size
        return "$pkg|$windowId|$size|$head|$tail"
    }

    /**
     * L0 阶段去重 — 检查当前阶段是否已处理过。
     *
     * 返回 true 表示此 stage 在 TTL 内已收到过回调，当前应跳过。
     */
    @Synchronized
    private fun isStageDuplicate(stageKey: String): Boolean {
        val now = System.currentTimeMillis()
        return isCached(recentStageKeys, stageKey, now, DEDUP_STAGE_TTL_MS)
    }
}
