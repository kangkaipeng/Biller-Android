package com.bjbyhd.screenreader_huawei.logger.appender

import com.bjbyhd.screenreader_huawei.logger.model.LogRecord

/**
 * 组合输出器 — 组合模式（Composite Pattern）实现
 *
 * 将多个 LogAppender 组合成一个整体，对外表现为单个 Appender，
 * 内部将操作分发给所有子 Appender。
 *
 * 核心优势：
 * 1. 统一接口：调用方无需关心是单个还是组合 Appender
 * 2. 灵活组合：可任意组合多个输出器（如同时输出到 Logcat 和文件）
 * 3. 批量操作：一条日志同时分发给多个目标
 */
class CompositeAppender(
    private val appenders: List<LogAppender>
) : LogAppender {

    override fun append(record: LogRecord, formattedMessage: String) {
        appenders.forEach { it.append(record, formattedMessage) }
    }

    override fun flush() {
        appenders.forEach { it.flush() }
    }

    override fun close() {
        appenders.forEach { it.close() }
    }
}
