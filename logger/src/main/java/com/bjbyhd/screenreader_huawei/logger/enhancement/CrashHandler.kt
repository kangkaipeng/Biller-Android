package com.bjbyhd.screenreader_huawei.logger.enhancement

import com.bjbyhd.screenreader_huawei.logger.core.LogDispatcher
import com.bjbyhd.screenreader_huawei.logger.file.LogFileManager
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import com.bjbyhd.screenreader_huawei.logger.model.LogRecord

/**
 * 崩溃捕获处理器
 *
 * 通过注册为系统的未捕获异常处理器，在应用发生崩溃时能够及时记录异常信息
 * 并确保日志写入磁盘，为后续问题排查提供关键线索。
 *
 * 使用方式：
 * - 在 Logger.init() 时自动注册（由 LogConfig.enableCrashHandler 控制）
 * - 记录崩溃日志后会将异常交还给系统默认处理器，保留系统默认崩溃行为
 */
class CrashHandler(
    private val dispatcher: LogDispatcher,
    private val fileManager: LogFileManager,
    private val sessionId: String
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun register() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val record = LogRecord(
            level = LogLevel.ERROR,
            tag = "CRASH",
            message = "未捕获异常：${throwable.message}",
            throwable = throwable,
            threadName = thread.name,
            fileName = "CrashHandler",
            methodName = "uncaughtException",
            lineNumber = -1,
            sessionId = sessionId
        )
        dispatcher.dispatch(record)
        dispatcher.flush()
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
