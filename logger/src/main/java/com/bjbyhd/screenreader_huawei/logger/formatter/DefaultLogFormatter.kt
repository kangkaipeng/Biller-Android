package com.bjbyhd.screenreader_huawei.logger.formatter

import com.bjbyhd.screenreader_huawei.logger.model.LogRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 默认日志格式化器
 *
 * 输出格式：
 *   2026-05-17 14:30:52.123 [INFO] [main] (GuaListFragment.kt:58 onCreate) [S:session01] TAG: 消息内容
 *
 * 异常追加：
 *   当日志包含异常时，在消息后追加堆栈信息。
 *
 * 脱敏处理：
 *   - 支持通过正则表达式替换敏感信息
 *   - 在构造时传入敏感词映射表
 *   - 默认不进行脱敏处理
 */
class DefaultLogFormatter(
    /**
     * 敏感信息脱敏规则映射表
     * Key：正则表达式模式，Value：替换字符串
     */
    private val sensitivePatterns: Map<String, String> = emptyMap()
) : LogFormatter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    override fun format(record: LogRecord): String {
        val time = dateFormat.format(Date(record.timestamp))
        val level = "[${record.level.label.padEnd(7)}]"
        val thread = "[${record.threadName}]"
        val location = "(${record.fileName}:${record.lineNumber} ${record.methodName})"
        val session = "[S:${record.sessionId}]"

        val rawMessage = "$time $level $thread $location $session ${record.tag}: ${record.message}"
        val sanitized = applySensitiveFilter(rawMessage)

        return if (record.throwable != null) {
            "$sanitized\n${record.throwable.stackTraceToString()}"
        } else {
            sanitized
        }
    }

    private fun applySensitiveFilter(message: String): String {
        var result = message
        sensitivePatterns.forEach { (pattern, replacement) ->
            result = result.replace(Regex(pattern), replacement)
        }
        return result
    }
}
