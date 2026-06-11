package com.bjbyhd.screenreader_huawei.logger.api

/**
 * Logger Kotlin 扩展函数
 *
 * 为任意 Kotlin 类提供便捷的日志记录扩展，自动从类实例获取标签名，
 * 简化日志调用代码。利用 Kotlin 扩展函数 + inline 实现延迟计算。
 *
 * 使用方式：
 * ```kotlin
 * class GuaListViewModel : ViewModel() {
 *     fun load() {
 *         logD { "开始加载数据" }  // tag 自动为 "GuaListViewModel"
 *     }
 * }
 * ```
 */

inline fun Any.logV(message: () -> String) {
    CLog.v(this::class.java.simpleName, message)
}

inline fun Any.logD(message: () -> String) {
    CLog.d(this::class.java.simpleName, message)
}

inline fun Any.logI(message: () -> String) {
    CLog.i(this::class.java.simpleName, message)
}

inline fun Any.logW(throwable: Throwable? = null, message: () -> String) {
    CLog.w(this::class.java.simpleName, throwable, message)
}

inline fun Any.logE(throwable: Throwable? = null, message: () -> String) {
    CLog.e(this::class.java.simpleName, throwable, message)
}
