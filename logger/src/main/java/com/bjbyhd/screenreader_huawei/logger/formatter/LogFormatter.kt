package com.bjbyhd.screenreader_huawei.logger.formatter

import com.bjbyhd.screenreader_huawei.logger.model.LogRecord

/**
 * 日志格式化接口 — 策略模式的核心抽象
 *
 * 通过实现此接口，可以自定义日志的输出格式：
 *   - JSON 格式：便于结构化解析
 *   - CSV 格式：便于导入 Excel 分析
 *   - 自定义文本格式：适应特定需求
 *
 * 线程安全：format() 方法可能被多线程调用，实现类应保证线程安全。
 */
interface LogFormatter {

    /**
     * 将日志记录格式化为字符串
     *
     * 实现要求：
     *   - 返回的字符串应包含日志的所有关键信息
     *   - 处理 null 或异常值，避免返回空字符串
     *   - 避免在 format() 中执行 I/O 操作
     *
     * @param record 日志记录对象
     * @return 格式化后的日志字符串
     */
    fun format(record: LogRecord): String
}
