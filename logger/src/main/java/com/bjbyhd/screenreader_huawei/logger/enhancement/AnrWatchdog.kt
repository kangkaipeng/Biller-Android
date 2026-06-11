package com.bjbyhd.screenreader_huawei.logger.enhancement

import android.os.Handler
import android.os.Looper
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import com.bjbyhd.screenreader_huawei.logger.model.LogRecord
import com.bjbyhd.screenreader_huawei.logger.core.LogDispatcher

/**
 * ANR（Application Not Responding）检测 Watchdog
 *
 * 通过监控主线程的心跳响应情况，在检测到主线程可能阻塞时记录警告日志，
 * 帮助开发者发现和定位 ANR 问题。
 *
 * 检测原理：
 * 1. 创建一个后台守护线程（Watchdog 线程）
 * 2. 每隔 timeoutMs 时间向主线程 post 一个心跳任务
 * 3. 心跳任务被执行时更新最后心跳时间戳
 * 4. Watchdog 线程检查：如果时间差超过阈值，判定为可能 ANR
 *
 * @param dispatcher 日志调度器
 * @param sessionId 当前会话 ID
 * @param timeoutMs ANR 检测超时时间，默认 5000ms
 */
class AnrWatchdog(
    private val dispatcher: LogDispatcher,
    private val sessionId: String,
    private val timeoutMs: Long = 5000L
) : Thread("AnrWatchdog") {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastHeartbeatTime = System.currentTimeMillis()

    @Volatile
    private var running = true

    init {
        isDaemon = true
    }

    override fun run() {
        while (running) {
            postHeartbeat()
            Thread.sleep(timeoutMs)

            val elapsed = System.currentTimeMillis() - lastHeartbeatTime

            if (elapsed > timeoutMs) {
                val record = LogRecord(
                    level = LogLevel.WARN,
                    tag = "ANR-WATCHDOG",
                    message = "主线程可能阻塞，心跳超时 ${elapsed}ms（阈值 ${timeoutMs}ms）",
                    threadName = "main",
                    fileName = "AnrWatchdog.kt",
                    methodName = "run",
                    lineNumber = -1,
                    sessionId = sessionId
                )
                dispatcher.dispatch(record)
            }
        }
    }

    fun stopWatchdog() {
        running = false
        interrupt()
    }

    private fun postHeartbeat() {
        mainHandler.post {
            lastHeartbeatTime = System.currentTimeMillis()
        }
    }
}
