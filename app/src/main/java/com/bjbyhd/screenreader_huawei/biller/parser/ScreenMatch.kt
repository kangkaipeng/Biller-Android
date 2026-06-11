package com.bjbyhd.screenreader_huawei.biller.parser

/**
 * 屏幕匹配结果 — match() 方法的返回值类型体系 (v3)
 *
 * 模块: feature/biller/engine/screen
 * 职责: 封装一次完整 DFS 遍历的产出——页面类型判定 + 已收集的全部文本数据。
 *       extract() 不再需要持有或遍历 AccessibilityNodeInfo，直接处理此结构中的纯数据即可。
 *
 * ## v3 重构变更
 *
 * v2 中 [Success] 持有 [android.view.accessibility.AccessibilityNodeInfo] 引用:
 *   - match() 早停后 extract() 仍需再次 DFS 收集文本，造成冗余遍历
 *   - 节点引用生命周期依赖调用方同步执行，异步化即危险
 *
 * v3 将 DFS 文本收集职责移入 match():
 *   - match() 在一次 DFS 中同时完成"锚点判定"和"全树文本收集"
 *   - [collectedTexts] 是 extract() 的唯一数据来源（纯字符串，零树遍历）
 *   - [Success] 不再持有任何 Android 系统类型引用
 *
 * ## 扩展方式
 *
 * 新增交易类型（如"红包"）时，在 [Success] 下添加新的子类:
 * ```
 * class RedPacket(anchorDesc: String, collectedTexts: List<String>) : Success(anchorDesc, collectedTexts)
 * ```
 * 编译器会自动提示所有 when 分支需要补全。
 *
 * @see IScreenParser 策略接口
 * @see WeChatScreenParser 微信实现
 * @see AlipayScreenParser 支付宝实现
 */
sealed class ScreenMatch {

    /**
     * 匹配成功 — 携带 match() 阶段收集的全部数据，供 extract() 直接消费
     *
     * @property anchorDesc      锚点节点的 contentDescription 原文（金额可能编码在其中，如 "转账成功￥1.00"）
     * @property collectedTexts  DFS 遍历过程中收集的全部非空 TextView 文本（按 DFS 访问顺序排列）
     */
    abstract class Success(
        val anchorDesc: String,
        val collectedTexts: List<String>,
        /**
         * DFS 遍历过程中统计的节点总数 (P2-2.2 新增)
         *
         * 由 Parser.match() 在一次 DFS 遍历中统计，避免 Service 层单独的 countNodes() 调用。
         * 仅用于日志输出，无业务含义。默认为 0 以保持向后兼容。
         */
        val nodeCount: Int = 0,
    ) : ScreenMatch()

    /**
     * 付款成功 — 锚点 Desc 包含"支付成功"关键字
     */
    class Payment(
        anchorDesc: String,
        collectedTexts: List<String>,
        nodeCount: Int = 0,  // P2-2.2: 节点计数
    ) : Success(anchorDesc, collectedTexts, nodeCount)

    /**
     * 转账成功 — 锚点 Desc 包含"转账成功"关键字
     */
    class Transfer(
        anchorDesc: String,
        collectedTexts: List<String>,
        nodeCount: Int = 0,  // P2-2.2: 节点计数
    ) : Success(anchorDesc, collectedTexts, nodeCount)

    /**
     * 未匹配 — 当前页面不是任何已知的支付/转账成功页
     */
    object NoMatch : ScreenMatch()

    /**
     * 遍历异常 — DFS 过程中发生不可恢复的错误
     *
     * 调用方应记录异常信息后跳过此事件，不进入 extract()。
     * 与 [NoMatch] 的区别: Failure 表示系统级异常（如节点树损坏），
     * 而非业务上的"不匹配"。
     *
     * @property cause 原始异常
     */
    class Failure(val cause: Throwable) : ScreenMatch()
}
