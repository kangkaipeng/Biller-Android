package com.bjbyhd.screenreader_huawei.logger.appender

import android.util.Log
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import com.bjbyhd.screenreader_huawei.logger.model.LogRecord

/**
 * Logcat 输出器 — Android 系统日志输出实现
 *
 * 将日志记录转发到 Android 系统的 Logcat。
 * Android 的 Log 类是线程安全的，可在多线程环境下直接调用。
 */
class LogcatAppender : LogAppender {

    override fun append(record: LogRecord, formattedMessage: String) {
        val tag = record.tag
        val msg = formattedMessage
        when (record.level) {
            LogLevel.VERBOSE -> Log.v(tag, msg)
            LogLevel.DEBUG   -> Log.d(tag, msg)
            LogLevel.INFO    -> Log.i(tag, msg)
            LogLevel.WARN    -> Log.w(tag, msg)
            LogLevel.ERROR   -> Log.e(tag, msg, record.throwable)
            LogLevel.NONE    -> Unit
        }
    }

    override fun flush() = Unit

    override fun close() = Unit
}
