package com.bjbyhd.screenreader_huawei.logger.appender

import com.bjbyhd.screenreader_huawei.logger.model.LogRecord

/**
 * 日志输出器接口 — 策略模式的核心抽象
 *
 * 不同的输出目标（Logcat、文件、网络、数据库等）只需实现此接口，
 * 即可被 LogDispatcher 统一调度，实现日志输出的灵活扩展。
 */
interface LogAppender {

    /**
     * 追加一条日志记录
     *
     * 此方法由 LogDispatcher 在日志等级满足条件时调用。
     * 实现类应在此方法中完成实际的输出操作。
     * 对于文件写入等 I/O 操作，建议使用协程 + Channel 实现异步处理。
     *
     * @param record 原始日志记录，包含等级、标签、消息、异常、时间戳等元数据
     * @param formattedMessage 格式化后的日志消息字符串，由 LogFormatter 生成
     */
    fun append(record: LogRecord, formattedMessage: String)

    /** 刷新输出缓冲区 */
    fun flush()

    /** 关闭输出器并释放资源 */
    fun close()
}
