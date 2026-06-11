package com.bjbyhd.screenreader_huawei.logger.appender

import com.bjbyhd.screenreader_huawei.logger.model.LogRecord
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import com.bjbyhd.screenreader_huawei.logger.file.LogFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 日志写入回调接口 — 用于测试阶段验证写入结果
 */
interface WriteCallback {
    fun onSuccess(message: String)
    fun onFailure(message: String, error: Throwable)
}

/** Channel 中传递的日志消息 */
private data class LogMessage(
    val content: String,
    val level: LogLevel
)

/**
 * 文件输出器 — 异步文件日志写入实现
 *
 * 设计目标：实现高性能、非阻塞的文件日志写入，确保日志记录不会拖慢业务线程。
 *
 * 异步架构：
 * - 使用独立的单线程协程（Dispatchers.IO）处理所有文件写入操作
 * - 使用 Channel 作为生产者-消费者队列，解耦日志提交和实际写入
 * - 调用方线程只需将日志放入 Channel，立即返回，零阻塞
 *
 * Channel 背压处理：
 * - 容量：1024 条消息
 * - 溢出策略：DROP_OLDEST（丢弃最旧的日志），防止 OOM
 *
 * @param fileManager 文件管理器，负责打开文件、写入内容、滚动日志文件
 * @param writeCallback 写入回调（测试专用，生产环境传 null）
 */
class FileAppender(
    private val fileManager: LogFileManager,
    private val writeCallback: WriteCallback? = null
) : LogAppender {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val channel = Channel<LogMessage>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            for (logMessage in channel) {
                try {
                    fileManager.writeLine(logMessage.content, logMessage.level)
                    writeCallback?.onSuccess(logMessage.content)
                } catch (e: Exception) {
                    writeCallback?.onFailure(logMessage.content, e)
                }
            }
        }
    }

    override fun append(record: LogRecord, formattedMessage: String) {
        val logMessage = LogMessage(formattedMessage, record.level)
        channel.trySend(logMessage)
    }

    override fun flush() {
        fileManager.flush()
    }

    override fun close() {
        channel.close()
        fileManager.close()
    }
}
