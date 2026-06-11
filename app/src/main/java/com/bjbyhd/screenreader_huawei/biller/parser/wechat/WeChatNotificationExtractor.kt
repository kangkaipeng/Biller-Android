package com.bjbyhd.screenreader_huawei.biller.parser.wechat

import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig
import com.bjbyhd.screenreader_huawei.biller.parser.ParsedBill
import com.bjbyhd.screenreader_huawei.biller.parser.NotificationKeywordChecker
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 微信通知栏文本解析器 (v3 — 商户提取多级降级 + 顶级异常兜底)
 *
 * 模块: feature/biller/parser/notification
 * 职责:
 *   1. 包名 + 关键字轻量预判 — 过滤非微信支付通知
 *   2. 金额提取 — 正则匹配 ¥/￥ 金额（金额缺失 → 返回 null，拒绝入库）
 *   3. 商户提取 — L1→L2→L3 三级降级正则链，覆盖微信通知变体
 *
 * ## 命名说明
 *
 *   本类仅处理**微信**通知。支付宝通知由独立的 [AlipayNotificationParser] 处理。
 *   两个解析器的业务逻辑完全独立，不共享任何公共基类——这是有意为之的设计：
 *   微信与支付宝的通知格式、关键字、解析策略均不相同，未来任一 App 升级后
 *   可能需要独立重构。共享基类会导致修改一方时的回归测试负担。
 *
 * ## 数据流 (v3)
 *
 * ```
 * isTargetData(pkg, title, text)
 *   → NotificationKeywordChecker.checkKeywords() 轻量预判
 *   → true: 进入 parse()
 *
 * parse(pkg, title, text, timestamp)
 *   ├─ try {                                    // ← 顶级异常兜底 (P2-5.1)
 *   │   ├─ normalize()        文本预处理
 *   │   ├─ extractAmount()    金额提取        → null → return null
 *   │   ├─ extractMerchant()  商户提取(L1→L3) → null 可接受
 *   │   └─ buildResult()      组装 ParsedBill
 *   │  }
 *   └─ catch(e) { CLog.w + return null }        // ← 静默容错，不崩溃
 * ```
 *
 * ## 商户提取降级链 (P1-3.1)
 *
 * ```
 * extractMerchant(normalized)
 *   ├─ L1: EXACT_GROUP_REGEX  — "向{name}付款" / "收款方: {name}" / "商户: {name}" / "付款给{name}"
 *   ├─ L2: LABEL_REGEX        — "收款方/商户/商品/对方" 标签后跟值
 *   ├─ L3: PREFIX_REGEX       — 行首商户名后紧跟 ¥ 金额
 *   └─ null                    — 纯金额通知，接受无商户名
 * ```
 *
 * ## 设计原则
 *
 *   - 无状态 object：所有输入通过方法参数传递，纯函数，线程安全
 *   - 每级提取独立 try-catch，单级失败不中断后续降级
 *   - 正则设计为 Lazy 初始化，避免类加载时全局匹配开销
 *   - 目标 App 配置统一从 [TargetConfig] 读取
 *
 * @see AlipayNotificationParser 支付宝通知解析器（独立，彻底解耦）
 * @see NotificationParserRegistry 按包名分发的注册表
 */
object WeChatNotificationExtractor  {

    private const val TAG = "Biller/WeChatNotify"

    // ── 包名常量 ──────────────────────────────────────────────────────

    /** @see TargetConfig.WECHAT_PACKAGE */
    const val WECHAT_PACKAGE = TargetConfig.WECHAT_PACKAGE

    // ═══════════════════════════════════════════════════════════════
    // 金额正则
    // ═══════════════════════════════════════════════════════════════

    /** 金额: ¥19.93, ￥1.00, ¥ 1,234.56 */
    private val AMOUNT_REGEX = Regex("[¥￥]\\s*([\\d,]+\\.\\d{2})")

    // ═══════════════════════════════════════════════════════════════
    // 商户正则 — L1→L2→L3 三级降级链 (P1-3.1)
    // ═══════════════════════════════════════════════════════════════

    /**
     * L1 精确匹配 — 微信通知中明确的商户描述句式
     *
     * 覆盖格式:
     *   "向{name}付款"              — 转账/商业支付描述
     *   "付款给{name}"              — 付款方向描述
     *   "付款至{name}"              — 大额支付变体
     *   "收款方[:：]{name}"         — 收款方标签（微信可能引入）
     *   "商户[:：]{name}"           — 商户标签（微信可能引入）
     */
    private val L1_EXACT_GROUP_REGEX = Regex(
        "向(.+?)付款"                      // group(1) = name
        + "|付款[给至](.+?)[¥￥\\s,\\n]"     // group(2) = name
        + "|收款方[:：]\\s*(.+?)[¥￥\\s,\\n]"  // group(3) = name
        + "|商户[:：]\\s*(.+?)[¥￥\\s,\\n]"    // group(4) = name
    )

    /**
     * L2 标签键值 — "标签: 值" 模式的通用抓取
     *
     * 覆盖格式:
     *   "收款方:沙县小吃"   → "沙县小吃"
     *   "商户: 星巴克"      → "星巴克"
     *   "商品: 美团外卖"     → "美团外卖"
     *   "对方: Cavalry98"   → "Cavalry98"
     *
     * 约束: 值长度 2~30 字符，排除金额/状态等短文本
     */
    private val L2_LABEL_REGEX = Regex(
        "(?:收款方|商户|商品|对方)[:：]\\s*" +
        "([\\u4e00-\\u9fa5（）()a-zA-Z0-9**]{2,30})" +
        "(?:[，,。\\n]|\$)"
    )

    /**
     * L3 行首模式 — "商户名 ¥金额" 格式
     *
     * 覆盖格式:
     *   "美团 ¥36.00"       → "美团"
     *   "沙县小吃 ￥18.50"   → "沙县小吃"
     *
     * 约束: 商户名长度 2~20 字符
     */
    private val L3_PREFIX_REGEX = Regex(
        "([\\u4e00-\\u9fa5（）()a-zA-Z0-9**]{2,20})\\s+[¥￥]"
    )

    // ═══════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 轻量预判 — 仅微信包名 + 关键字匹配
     */
    fun isTargetData(packageName: String, title: String, text: String): Boolean {
        return NotificationKeywordChecker.checkKeywords(packageName, title, text, TargetConfig.WECHAT_PACKAGE)
    }

    /**
     * 从微信通知文本中提取账单信息
     *
     * ## 数据处理链 (v3)
     *
     *   1. 文本预处理 — 合并标题与正文，规范化空白字符
     *   2. extractAmount()   — 正则匹配金额（失败 → null，整体返回 null）
     *   3. extractMerchant() — L1→L2→L3 降级提取商户名（失败 → null，可接受）
     *   4. 组装 ParsedBill — 通道固定为 WEIXIN
     *
     * ## 异常策略
     *
     *   整个方法体由 try-catch 包裹，确保即使微信通知格式发生不可预知的变化，
     *   我方 App 也能静默容错而不发生 Runtime Exception。
     */
    fun parse(
        packageName: String,
        title: String,
        text: String,
        timestamp: Long
    ): ParsedBill? {
        // 仅处理微信
        if (packageName != TargetConfig.WECHAT_PACKAGE) return null

        // 🔑 P2-5.1 顶级异常兜底: 微信通知格式升级可能导致解析异常
        return try {
            parseInternal(title, text, timestamp)
        } catch (e: Exception) {
            CLog.w(TAG, e) {
                "[WeChatNotify] parse: 整体解析异常 → 返回 null | title=${title.take(20)} " +
                "text=${text.take(30)} | ${e.message}"
            }
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部解析 — 独立方法，职责边界清晰
    // ═══════════════════════════════════════════════════════════════

    /**
     * 核心解析逻辑
     *
     * 步骤分离:
     *   1. normalize()       — 纯字符串处理，无外部依赖
     *   2. extractAmount()   — 金额提取（必须成功）
     *   3. extractMerchant() — 商户提取（可降级为 null）
     *   4. buildResult()     — 组装不可变数据对象
     *
     * @param title     通知标题
     * @param text      通知正文
     * @param timestamp 通知到达时间戳（毫秒）
     * @return ParsedBill，金额缺失返回 null
     */
    private fun parseInternal(
        title: String,
        text: String,
        timestamp: Long,
    ): ParsedBill? {
        // ── Step 1: 文本预处理 ──
        val normalized = normalize(title, text)
        CLog.d(TAG) { "[WeChatNotify] Step1 normalize → ${normalized.take(60)}" }

        // ── Step 2: 金额提取（必须成功）──
        val amount = try {
            extractAmount(normalized)
        } catch (e: Exception) {
            CLog.w(TAG, e) { "[WeChatNotify] Step2 extractAmount 异常: ${e.message}" }
            null
        }
        if (amount == null) {
            CLog.d(TAG) { "[WeChatNotify] Step2 extractAmount: 金额缺失 → 返回 null" }
            return null
        }
        CLog.d(TAG) { "[WeChatNotify] Step2 extractAmount → amount=$amount" }

        // ── Step 3: 商户提取（L1→L2→L3 降级，可接受 null）──
        val merchant = try {
            extractMerchant(normalized)
        } catch (e: Exception) {
            CLog.w(TAG, e) { "[WeChatNotify] Step3 extractMerchant 异常: ${e.message}" }
            null
        }
        CLog.d(TAG) { "[WeChatNotify] Step3 extractMerchant → merchant=${merchant ?: "null"}" }

        // ── Step 4: 组装结果 ──
        return buildResult(amount, merchant, title, text, timestamp)
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 1: 文本预处理
    // ═══════════════════════════════════════════════════════════════

    /** 合并标题与正文，规范化为单行文本 */
    private fun normalize(title: String, text: String): String {
        return "$title $text"
            .replace("\n", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 2: 金额提取
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从规范化文本中提取金额
     *
     * 匹配 ¥/￥ 符号后的 xx.xx 格式。移除千分位逗号后转为 Double。
     * 返回 null 表示当前通知不含有效金额。
     */
    private fun extractAmount(normalized: String): Double? {
        val match = AMOUNT_REGEX.find(normalized) ?: return null
        return match.groupValues
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.replace(",", "")     // 移除千分位逗号: 1,234.56 → 1234.56
            ?.toDoubleOrNull()
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3: 商户提取 — L1→L2→L3 三级降级链
    // ═══════════════════════════════════════════════════════════════

    /**
     * 三级降级提取商户名
     *
     * ┌─────── L1: EXACT_GROUP_REGEX ──────┐
     * │ "向{name}付款" / "收款方: {name}"   │  最精确，信息最完整
     * │ "付款给{name}" / "商户: {name}"      │
     * └──────────────┬─────────────────────┘
     *                │ 失败 ↓
     * ┌─────── L2: LABEL_REGEX ────────────┐
     * │ "收款方/商户/商品/对方: value"       │  通用标签键值抓取
     * └──────────────┬─────────────────────┘
     *                │ 失败 ↓
     * ┌─────── L3: PREFIX_REGEX ───────────┐
     * │ "商户名 ¥金额" 行首模式              │  最弱，长度约束 2~20
     * └──────────────┬─────────────────────┘
     *                │ 失败 ↓
     *              null   — 纯金额通知，接受无商户名
     *
     * 每级独立 try-catch，单级异常不中断后续降级。
     */
    private fun extractMerchant(normalized: String): String? {
        // L1: 精确匹配
        try {
            l1Extract(normalized)?.let { name ->
                CLog.d(TAG) { "[WeChatNotify] 商户 L1 命中: '$name'" }
                return name
            }
        } catch (e: Exception) {
            CLog.d(TAG) { "[WeChatNotify] 商户 L1 异常跳过: ${e.message}" }
        }

        // L2: 标签键值
        try {
            l2Extract(normalized)?.let { name ->
                CLog.d(TAG) { "[WeChatNotify] 商户 L2 命中: '$name'" }
                return name
            }
        } catch (e: Exception) {
            CLog.d(TAG) { "[WeChatNotify] 商户 L2 异常跳过: ${e.message}" }
        }

        // L3: 行首模式
        try {
            l3Extract(normalized)?.let { name ->
                CLog.d(TAG) { "[WeChatNotify] 商户 L3 命中: '$name'" }
                return name
            }
        } catch (e: Exception) {
            CLog.d(TAG) { "[WeChatNotify] 商户 L3 异常跳过: ${e.message}" }
        }

        // 全部失败: 纯金额通知，接受无商户名
        CLog.d(TAG) { "[WeChatNotify] 商户 全部降级失败 → null（纯金额通知）" }
        return null
    }

    /** L1: "向{name}付款" / "收款方: {name}" / "付款给{name}" / "商户: {name}" */
    private fun l1Extract(text: String): String? {
        val match = L1_EXACT_GROUP_REGEX.find(text) ?: return null
        return match.groupValues
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            ?.takeIf { it.length >= 2 }
    }

    /** L2: "收款方/商户/商品/对方: value" 通用标签抓取 */
    private fun l2Extract(text: String): String? {
        val match = L2_LABEL_REGEX.find(text) ?: return null
        return match.groupValues
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            ?.takeIf { it.length >= 2 }
    }

    /** L3: 行首 "商户名 ¥金额" */
    private fun l3Extract(text: String): String? {
        val match = L3_PREFIX_REGEX.find(text) ?: return null
        return match.groupValues
            .drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            ?.takeIf { it.length in 2..20 }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 4: 组装结果
    // ═══════════════════════════════════════════════════════════════

    /** 组装不可变的 [ParsedBill] 实例 */
    private fun buildResult(
        amount: Double,
        merchant: String?,
        title: String,
        text: String,
        timestamp: Long,
    ): ParsedBill {
        CLog.i(TAG) {
            "[WeChatNotify] 解析完成: amount=$amount merchant=${merchant ?: "无"} " +
            "title=${title.take(20)}"
        }
        return ParsedBill(
            packageName = TargetConfig.WECHAT_PACKAGE,
            rawTitle = title,
            rawText = text,
            amount = amount,
            merchant = merchant,
            paymentChannel = TargetConfig.CHANNEL_NAMES[TargetConfig.WECHAT_PACKAGE] ?: "WEIXIN",
            timestamp = timestamp,
        )
    }
}
