package com.bjbyhd.screenreader_huawei.logger.appender

import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import com.bjbyhd.screenreader_huawei.logger.model.LogRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 远程日志上报 Appender — 远程日志上报预留接口实现
 *
 * 提供远程日志上报的基础实现框架，将日志异步上报到远程服务器。
 * 支持设置最小上报等级 minLevel，只有等级大于等于此值的日志才会被上报。
 */
class RemoteLogAppender(
    private val api: RemoteLogApi,
    private val minLevel: LogLevel = LogLevel.ERROR
) : LogAppender {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun append(record: LogRecord, formattedMessage: String) {
        if (record.level.priority < minLevel.priority) return

        scope.launch {
            runCatching { api.report(formattedMessage) }
        }
    }

    override fun flush() = Unit

    override fun close() = Unit
}

/**
 * 远程日志上报接口
 *
 * 实现此接口以对接你的远程日志服务（如 Sentry、Firebase Crashlytics 等）。
 */
interface RemoteLogApi {
    /** 上报日志到远程服务器 */
    suspend fun report(message: String)
}
