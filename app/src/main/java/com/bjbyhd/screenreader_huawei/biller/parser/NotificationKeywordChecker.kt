package com.bjbyhd.screenreader_huawei.biller.parser

import com.bjbyhd.screenreader_huawei.biller.config.TargetConfig

/**
 * 通知关键字轻量预判工具
 *
 * 职责: 供各 App 的 NotificationExtractor 在 isTargetData() 中调用，
 *       统一包名校验 + 关键字匹配逻辑，避免重复代码。
 */
object NotificationKeywordChecker {

    /**
     * 判断通知是否可能为支付相关通知
     *
     * @param packageName     通知来源包名
     * @param title           通知标题
     * @param text            通知文本
     * @param expectedPackage 期望的包名
     * @return true 表示包名匹配且标题/文本中包含支付关键字
     */
    fun checkKeywords(
        packageName: String,
        title: String,
        text: String,
        expectedPackage: String,
    ): Boolean {
        if (packageName != expectedPackage) return false
        val combined = "$title $text"
        val keywords = TargetConfig.PAYMENT_KEYWORDS[packageName] ?: return false
        return keywords.any { combined.contains(it) }
    }
}
