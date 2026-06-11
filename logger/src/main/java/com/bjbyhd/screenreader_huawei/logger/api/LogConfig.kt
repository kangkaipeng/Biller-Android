package com.bjbyhd.screenreader_huawei.logger.api

import com.bjbyhd.screenreader_huawei.logger.appender.LogAppender
import com.bjbyhd.screenreader_huawei.logger.formatter.LogFormatter
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import java.io.File

/**
 * 日志系统配置数据类
 *
 * 集中管理日志系统的所有配置参数，作为 Logger.init() 的初始化参数。
 * 采用不可变 data class 设计，确保配置的一致性和线程安全性。
 *
 * @property logDir 日志文件存储目录，必须具有读写权限
 * @property consoleLevel Logcat 输出的最低日志等级
 * @property fileLevel 文件写入的最低日志等级
 * @property maxFileSizeBytes 单个日志文件的最大字节数（默认 5MB）
 * @property maxFileCount 最多保留的日志文件数量（默认 10）
 * @property maxRetainDays 日志文件的最大保留天数（默认 7）
 * @property formatter 自定义日志格式化器，null 时使用 DefaultLogFormatter
 * @property extraAppenders 扩展的 Appender 列表
 * @property enableCrashHandler 是否启用未捕获异常自动捕获（默认开启）
 * @property enableAnrWatchdog 是否启用 ANR 检测（默认开启）
 * @property anrTimeoutMs ANR 检测超时阈值（默认 5000ms）
 * @property autoFlush 是否每次写入后立即刷新（默认 false）
 * @property autoFlushInterval 自动刷新间隔毫秒（默认 5000ms）
 * @property criticalLevelFlush ERROR 级别是否立即刷新（默认 true）
 * @property bufferFlushThreshold 缓冲区刷新阈值字节（默认 4096）
 * @property sensitivePatterns 敏感信息脱敏规则映射表
 */
data class LogConfig(
    val logDir: File,
    val consoleLevel: LogLevel = LogLevel.VERBOSE,
    val fileLevel: LogLevel = LogLevel.INFO,
    val maxFileSizeBytes: Long = 5 * 1024 * 1024L,
    val maxFileCount: Int = 10,
    val maxRetainDays: Int = 7,
    val formatter: LogFormatter? = null,
    val extraAppenders: List<LogAppender> = emptyList(),
    val enableCrashHandler: Boolean = true,
    val enableAnrWatchdog: Boolean = true,
    val anrTimeoutMs: Long = 5000L,
    val autoFlush: Boolean = false,
    val autoFlushInterval: Long = 5000L,
    val criticalLevelFlush: Boolean = true,
    val bufferFlushThreshold: Int = 4096,
    val sensitivePatterns: Map<String, String> = defaultSensitivePatterns()
) {
    companion object {

        fun defaultSensitivePatterns(): Map<String, String> = emptyMap()

        @JvmStatic
        fun builder(logDir: File): Builder = Builder(logDir)

        class Builder(private val logDir: File) {
            private var consoleLevel: LogLevel = LogLevel.VERBOSE
            private var fileLevel: LogLevel = LogLevel.INFO
            private var maxFileSizeBytes: Long = 5 * 1024 * 1024L
            private var maxFileCount: Int = 10
            private var maxRetainDays: Int = 7
            private var formatter: LogFormatter? = null
            private var extraAppenders: List<LogAppender> = emptyList()
            private var enableCrashHandler: Boolean = true
            private var enableAnrWatchdog: Boolean = true
            private var anrTimeoutMs: Long = 5000L
            private var autoFlush: Boolean = false
            private var autoFlushInterval: Long = 5000L
            private var criticalLevelFlush: Boolean = true
            private var bufferFlushThreshold: Int = 4096
            private var sensitivePatterns: Map<String, String> = defaultSensitivePatterns()

            fun consoleLevel(level: LogLevel) = apply { this.consoleLevel = level }
            fun fileLevel(level: LogLevel) = apply { this.fileLevel = level }
            fun maxFileSizeBytes(bytes: Long) = apply { this.maxFileSizeBytes = bytes }
            fun maxFileCount(count: Int) = apply { this.maxFileCount = count }
            fun maxRetainDays(days: Int) = apply { this.maxRetainDays = days }
            fun formatter(formatter: LogFormatter?) = apply { this.formatter = formatter }
            fun extraAppenders(appenders: List<LogAppender>) = apply { this.extraAppenders = appenders }
            fun enableCrashHandler(enable: Boolean) = apply { this.enableCrashHandler = enable }
            fun enableAnrWatchdog(enable: Boolean) = apply { this.enableAnrWatchdog = enable }
            fun anrTimeoutMs(ms: Long) = apply { this.anrTimeoutMs = ms }
            fun autoFlush(enable: Boolean) = apply { this.autoFlush = enable }
            fun autoFlushInterval(intervalMs: Long) = apply { this.autoFlushInterval = intervalMs }
            fun criticalLevelFlush(enable: Boolean) = apply { this.criticalLevelFlush = enable }
            fun bufferFlushThreshold(bytes: Int) = apply { this.bufferFlushThreshold = bytes }
            fun sensitivePatterns(patterns: Map<String, String>) = apply { this.sensitivePatterns = patterns }

            fun build(): LogConfig = LogConfig(
                logDir = logDir,
                consoleLevel = consoleLevel,
                fileLevel = fileLevel,
                maxFileSizeBytes = maxFileSizeBytes,
                maxFileCount = maxFileCount,
                maxRetainDays = maxRetainDays,
                formatter = formatter,
                extraAppenders = extraAppenders,
                enableCrashHandler = enableCrashHandler,
                enableAnrWatchdog = enableAnrWatchdog,
                anrTimeoutMs = anrTimeoutMs,
                autoFlush = autoFlush,
                autoFlushInterval = autoFlushInterval,
                criticalLevelFlush = criticalLevelFlush,
                bufferFlushThreshold = bufferFlushThreshold,
                sensitivePatterns = sensitivePatterns
            )
        }
    }
}
