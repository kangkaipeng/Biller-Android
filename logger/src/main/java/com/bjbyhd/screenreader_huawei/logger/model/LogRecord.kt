package com.bjbyhd.screenreader_huawei.logger.model

/**
 * 单条日志记录数据模型
 *
 * 职责：
 * 封装单条日志的完整信息，作为日志数据在系统内部传递的标准结构。
 * 包含日志内容、级别、时间、位置、异常等完整上下文信息。
 *
 * 设计要点：
 * - 使用 data class 自动生成 copy、equals、hashCode、toString 等方法
 * - 部分字段有默认值，简化创建过程
 * - 不可变设计，日志记录创建后不应被修改，保证数据一致性
 *
 * @property level 日志级别，表示日志的严重程度
 * @property tag 日志标签，用于分类和过滤，通常使用类名或模块名
 * @property message 日志消息内容，用户传入的原始日志文本
 * @property throwable 可选的异常对象，用于记录异常堆栈信息
 * @property timestamp 日志记录的时间戳（毫秒），默认为 System.currentTimeMillis()
 * @property fileName 调用日志方法的源文件名，由 StackTraceResolver 解析获取
 * @property methodName 调用日志方法的方法名，由 StackTraceResolver 解析获取
 * @property lineNumber 调用日志方法的代码行号，由 StackTraceResolver 解析获取
 * @property threadName 记录日志时的线程名称，默认为当前线程名
 * @property sessionId 会话标识符，用于关联同一应用生命周期内的所有日志
 */
data class LogRecord(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String = "",
    val methodName: String = "",
    val lineNumber: Int = -1,
    val threadName: String = Thread.currentThread().name,
    val sessionId: String = ""
)
