package com.bjbyhd.screenreader_huawei.logger.model

/**
 * 日志级别枚举
 *
 * 职责：
 * 定义日志的严重程度等级，用于控制日志的输出范围和过滤策略。
 * 遵循 Android Log 系统的级别定义，同时扩展了 NONE 级别用于完全关闭日志。
 *
 * 设计要点：
 * - priority 值越大表示级别越高（越严重），便于使用数值比较进行过滤
 * - label 用于日志输出时的文本标识，保持与 Android Log 一致
 * - NONE 级别优先级设为极大值（99），确保高于所有正常级别
 *
 * @property priority 优先级数值，用于级别比较和过滤判断，值越大优先级越高
 * @property label 级别的文本标签，用于日志输出显示
 */
enum class LogLevel(val priority: Int, val label: String) {

    /** priority = 1，通常用于记录函数进入/退出、变量值等详细信息 */
    VERBOSE(1, "VERBOSE"),

    /** priority = 2，用于记录调试信息、中间计算结果等 */
    DEBUG(2, "DEBUG"),

    /** priority = 3，用于记录业务操作成功、状态变更等信息 */
    INFO(3, "INFO"),

    /** priority = 4，用于记录非致命错误、性能警告、潜在风险等 */
    WARN(4, "WARN"),

    /** priority = 5，用于记录异常信息、业务失败、系统错误等 */
    ERROR(5, "ERROR"),

    /**
     * 关闭级别 — 特殊级别，用于完全禁用日志输出
     * priority = 99，设为极大值确保高于所有正常级别
     */
    NONE(99, "NONE")
}
