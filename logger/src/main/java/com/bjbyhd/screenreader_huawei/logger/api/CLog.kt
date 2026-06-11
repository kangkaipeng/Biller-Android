package com.bjbyhd.screenreader_huawei.logger.api

import android.content.Context
import com.bjbyhd.screenreader_huawei.logger.appender.FileAppender
import com.bjbyhd.screenreader_huawei.logger.appender.LogcatAppender
import com.bjbyhd.screenreader_huawei.logger.core.LogDispatcher
import com.bjbyhd.screenreader_huawei.logger.core.StackTraceResolver
import com.bjbyhd.screenreader_huawei.logger.enhancement.AnrWatchdog
import com.bjbyhd.screenreader_huawei.logger.enhancement.CrashHandler
import com.bjbyhd.screenreader_huawei.logger.file.LogFileCleaner
import com.bjbyhd.screenreader_huawei.logger.file.LogFileExporter
import com.bjbyhd.screenreader_huawei.logger.file.LogFileManager
import com.bjbyhd.screenreader_huawei.logger.formatter.DefaultLogFormatter
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import com.bjbyhd.screenreader_huawei.logger.model.LogRecord
import java.io.File

/**
 * 日志系统统一入口
 *
 * 提供应用内统一的日志记录能力，支持控制台输出、文件写入、崩溃捕获、ANR检测等功能。
 *
 * 使用方式：
 * 1. 在 Application.onCreate() 中调用 CLog.init(context, config) 完成初始化
 * 2. 在任意模块中直接调用 CLog.d("TAG") { "message" } 等方式记录日志
 * 3. 通过 setConsoleLevel/setFileLevel 可在运行时动态调整日志级别
 *
 * 设计要点：
 * - 使用 inline 函数保证行号准确，且等级不满足时 lambda 不执行（零性能损耗）
 * - 延迟初始化模式：init() 必须在任何日志调用之前执行
 */
object CLog {

    private lateinit var dispatcher: LogDispatcher
    private lateinit var fileManager: LogFileManager
    private lateinit var exporter: LogFileExporter
    private lateinit var sessionId: String
    private var initialized = false

    // ── 初始化 ────────────────────────────────────────────────────────────────

    /**
     * 初始化日志系统
     *
     * 必须在 Application.onCreate() 中调用，且仅首次调用有效（幂等设计）。
     *
     * @param context 应用上下文，用于获取文件存储路径和注册系统服务
     * @param config 日志配置参数，包含日志级别、存储策略、脱敏规则等
     */
    @JvmStatic
    fun init(context: Context, config: LogConfig) {
        if (initialized) return

        fileManager = LogFileManager(context, config)
        fileManager.startNewSession()
        sessionId = fileManager.sessionId

        val formatter = config.formatter
            ?: DefaultLogFormatter(config.sensitivePatterns)

        val logcatAppender = LogcatAppender()
        val fileAppender = FileAppender(fileManager = fileManager)

        dispatcher = LogDispatcher(
            consoleLevel = config.consoleLevel,
            fileLevel = config.fileLevel,
            formatter = formatter,
            consoleAppender = logcatAppender,
            fileAppender = fileAppender,
            extraAppenders = config.extraAppenders
        )

        exporter = LogFileExporter(context, config)

        LogFileCleaner(config).clean()

        if (config.enableCrashHandler) {
            CrashHandler(dispatcher, fileManager, sessionId).register()
        }

        if (config.enableAnrWatchdog) {
            AnrWatchdog(dispatcher, sessionId, config.anrTimeoutMs).start()
        }

        initialized = true
        i("Logger") { "日志系统初始化完成，SessionId=$sessionId" }
    }

    // ── 运行时动态切换等级 ────────────────────────────────────────────────────

    fun setConsoleLevel(level: LogLevel) = dispatcher.setConsoleLevel(level)

    fun setFileLevel(level: LogLevel) = dispatcher.setFileLevel(level)

    // ── 核心日志 API（inline 保证调用栈准确）──────────────────────────────────

    @JvmStatic
    inline fun v(tag: String, message: () -> String) {
        log(LogLevel.VERBOSE, tag, message(), null)
    }

    @JvmStatic
    inline fun d(tag: String, message: () -> String) {
        log(LogLevel.DEBUG, tag, message(), null)
    }

    @JvmStatic
    inline fun i(tag: String, message: () -> String) {
        log(LogLevel.INFO, tag, message(), null)
    }

    @JvmStatic
    inline fun w(tag: String, throwable: Throwable? = null, message: () -> String) {
        log(LogLevel.WARN, tag, message(), throwable)
    }

    @JvmStatic
    inline fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        log(LogLevel.ERROR, tag, message(), throwable)
    }

    // ── 内部分发（不用 inline，避免堆栈解析错误）───────────────────────────────

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (!initialized) return
        val frame = StackTraceResolver.resolve()
        val record = LogRecord(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
            fileName = frame.fileName ?: "Unknown",
            methodName = frame.methodName,
            lineNumber = frame.lineNumber,
            sessionId = sessionId
        )
        dispatcher.dispatch(record)
    }

    // ── 导出工具 ──────────────────────────────────────────────────────────────

    @JvmStatic
    fun flush() {
        if (initialized) {
            dispatcher.flush()
        }
    }

    fun getExporter(): LogFileExporter = exporter

    fun shareAllLogs(context: Context) {
        exporter.shareAllLogs()
    }

    fun getLogFilesDir(context: Context): File {
        return File(context.applicationContext.filesDir, "logs")
    }

    /**
     * 清除所有日志文件 (v5.2)
     *
     * 删除日志目录下全部 .log 文件，关闭当前写入器后启动新会话。
     * 调用后日志系统无缝继续工作——旧日志被删除，新日志写入新文件。
     *
     * 仅当日志系统已初始化时生效，未初始化时静默返回 0。
     *
     * @return 被删除的日志文件数量
     */
    @JvmStatic
    fun clearAllLogs(): Int {
        if (!initialized) return 0
        val count = fileManager.deleteAllLogFiles()
        // 重置 exporter 缓存的文件列表视图
        i("Logger") { "清除全部日志: $count 个文件已删除，新会话已启动" }
        return count
    }
}
