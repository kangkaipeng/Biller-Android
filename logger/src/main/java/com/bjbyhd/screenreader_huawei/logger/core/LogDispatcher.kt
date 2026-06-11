package com.bjbyhd.screenreader_huawei.logger.core

import com.bjbyhd.screenreader_huawei.logger.model.LogRecord
import com.bjbyhd.screenreader_huawei.logger.appender.LogAppender
import com.bjbyhd.screenreader_huawei.logger.formatter.LogFormatter
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import java.util.concurrent.atomic.AtomicReference

/**
 * 日志调度器 — 核心调度组件
 *
 * 职责：
 * 1. 根据日志等级进行过滤，决定是否将日志分发给各个输出器
 * 2. 调用 Formatter 对日志记录进行格式化
 * 3. 将格式化后的日志内容分发给对应的 Appender
 * 4. 支持运行时动态切换日志等级，无需重启应用
 *
 * 线程安全设计：
 * - 使用 AtomicReference 存储 consoleLevel 和 fileLevel
 * - 等级切换操作是无锁的，性能开销极小
 */
class LogDispatcher(
    consoleLevel: LogLevel,
    fileLevel: LogLevel,
    private val formatter: LogFormatter,
    private val consoleAppender: LogAppender,
    private val fileAppender: LogAppender,
    private val extraAppenders: List<LogAppender> = emptyList()
) {

    private val consoleLevelRef = AtomicReference(consoleLevel)
    private val fileLevelRef = AtomicReference(fileLevel)

    fun setConsoleLevel(level: LogLevel) {
        consoleLevelRef.set(level)
    }

    fun setFileLevel(level: LogLevel) {
        fileLevelRef.set(level)
    }

    /**
     * 分发日志记录到各个输出器
     *
     * 处理流程：
     * 1. 先调用 formatter 对日志记录进行格式化
     * 2. 检查等级后分发给 consoleAppender
     * 3. 检查等级后分发给 fileAppender
     * 4. 分发给所有 extraAppenders（扩展输出器自行内部过滤）
     */
    fun dispatch(record: LogRecord) {
        val formatted = formatter.format(record)

        if (record.level.priority >= consoleLevelRef.get().priority) {
            consoleAppender.append(record, formatted)
        }

        if (record.level.priority >= fileLevelRef.get().priority) {
            fileAppender.append(record, formatted)
        }

        extraAppenders.forEach { it.append(record, formatted) }
    }

    fun flush() {
        consoleAppender.flush()
        fileAppender.flush()
        extraAppenders.forEach { it.flush() }
    }

    fun close() {
        consoleAppender.close()
        fileAppender.close()
        extraAppenders.forEach { it.close() }
    }
}
