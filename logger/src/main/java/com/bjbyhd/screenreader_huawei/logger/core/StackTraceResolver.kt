package com.bjbyhd.screenreader_huawei.logger.core

/**
 * 调用栈解析器 — 定位业务代码调用位置
 *
 * 核心原理：
 * 从当前线程的堆栈跟踪信息中，智能跳过 Logger 框架内部的方法调用帧，
 * 准确定位到实际发起日志调用的业务代码位置。
 *
 * 过滤策略：
 * 1. 跳过包名以 logger 开头的所有类（Logger 自身）
 * 2. 跳过包名以 "dalvik." 开头的类（Dalvik 虚拟机内部）
 * 3. 跳过 "java.lang.Thread" 类（获取堆栈的方法本身）
 * 4. 取第一个满足条件的帧作为业务调用方
 */
object StackTraceResolver {

    /** Logger 框架的包名前缀，用于识别并跳过 Logger 内部调用帧 */
    private const val LOGGER_PACKAGE = "com.bjbyhd.screenreader_huawei.logger"

    /**
     * 解析并返回业务调用方的堆栈元素
     *
     * 线程安全：此方法只访问当前线程的堆栈，无线程安全问题。
     *
     * @return 业务调用方的 StackTraceElement，包含类名、方法名、文件名、行号
     */
    fun resolve(): StackTraceElement {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace.firstOrNull { element ->
            !element.className.startsWith(LOGGER_PACKAGE) &&
            !element.className.startsWith("dalvik.") &&
            !element.className.startsWith("java.lang.Thread")
        } ?: stackTrace.first()
    }
}
