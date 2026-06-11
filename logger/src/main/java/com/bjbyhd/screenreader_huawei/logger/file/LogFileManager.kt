package com.bjbyhd.screenreader_huawei.logger.file

import android.content.Context
import com.bjbyhd.screenreader_huawei.logger.api.LogConfig
import com.bjbyhd.screenreader_huawei.logger.model.LogLevel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * 日志文件生命周期管理器
 *
 * 职责：
 *   - App 冷启动时创建新 Session 文件
 *   - 写入日志行，超出大小限制时自动分片
 *   - 文件名格式：log_20260517_143052_session01.log
 *
 * 文件生命周期：
 *   1. App 启动 -> startNewSession() 创建新 Session 文件
 *   2. 日志写入 -> writeLine() 写入日志内容
 *   3. 文件分片 -> 当文件大小超过 maxFileSizeBytes 时自动创建新分片
 *   4. App 退出 -> close() 关闭文件写入器
 */
class LogFileManager(
    private val context: Context,
    private val config: LogConfig
) {

    private val prefs = context.getSharedPreferences("logger_prefs", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var currentFile: File? = null
    private var currentWriter: BufferedWriter? = null
    private var currentPartIndex: Int = 1

    @Volatile
    private var bufferedSize = 0

    private var flushTimer: Timer? = null

    val sessionId: String by lazy { buildSessionId() }

    init {
        config.logDir.mkdirs()
        startAutoFlushTimer()
    }

    fun startNewSession(): File {
        closeCurrentWriter()
        val file = createSessionFile(partIndex = 1)
        currentPartIndex = 1
        openWriter(file)
        return file
    }

    @Synchronized
    fun writeLine(line: String, level: LogLevel = LogLevel.INFO) {
        ensureWriter()
        val file = currentFile ?: return

        if (file.length() >= config.maxFileSizeBytes) {
            rollToNextPart()
        }

        currentWriter?.apply {
            write(line)
            newLine()
            bufferedSize += line.length + 1

            if (shouldFlush(level)) {
                flush()
            }
        }
    }

    private fun shouldFlush(level: LogLevel): Boolean {
        if (config.autoFlush) return true
        if (config.criticalLevelFlush && level == LogLevel.ERROR) return true
        if (config.bufferFlushThreshold > 0 && bufferedSize >= config.bufferFlushThreshold) return true
        return false
    }

    @Synchronized
    fun flush() {
        currentWriter?.flush()
        bufferedSize = 0
    }

    @Synchronized
    fun close() {
        stopAutoFlushTimer()
        closeCurrentWriter()
    }

    fun getCurrentFile(): File? = currentFile

    fun getLogFile(): File? = currentFile

    /**
     * 删除所有日志文件 (v5.2)
     *
     * 删除 [config.logDir] 目录下的所有 .log 文件。
     * 调用前会自动关闭当前写入器，避免文件锁冲突。
     * 删除后启动新会话，确保后续日志写入不受影响。
     *
     * ## 使用场景
     *
     *   - 用户在"我的"页面手动清除日志
     *   - 测试环境重置日志状态
     *
     * ## 线程安全
     *
     *   [closeCurrentWriter] 和 [startNewSession] 均为 [Synchronized]，
     *   调用方需确保不在 [writeLine] 并发时调用（UI 触发清除时正常的日志
     *   写入会被 Channel 缓冲，不会丢失）。
     *
     * @return 被删除的文件数量
     */
    @Synchronized
    fun deleteAllLogFiles(): Int {
        // 先关闭当前写入器，释放文件锁
        closeCurrentWriter()
        currentFile = null

        val logDir = config.logDir
        if (!logDir.exists()) return 0

        val logFiles = logDir.listFiles { f -> f.name.endsWith(".log") } ?: emptyArray()
        var deletedCount = 0
        for (file in logFiles) {
            if (file.delete()) {
                deletedCount++
            }
        }

        // 删除后立即启动新会话，日志系统不中断
        startNewSession()
        return deletedCount
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private fun buildSessionId(): String {
        val count = prefs.getInt("session_count", 0) + 1
        prefs.edit().putInt("session_count", count).apply()
        return "session%02d".format(count)
    }

    private fun createSessionFile(partIndex: Int): File {
        val timestamp = dateFormat.format(Date())
        val name = if (partIndex == 1) {
            "log_${timestamp}_${sessionId}.log"
        } else {
            "log_${timestamp}_${sessionId}_part${partIndex}.log"
        }
        return File(config.logDir, name).also { currentFile = it }
    }

    private fun rollToNextPart() {
        closeCurrentWriter()
        currentPartIndex++
        val file = createSessionFile(currentPartIndex)
        openWriter(file)
    }

    private fun openWriter(file: File) {
        currentWriter = BufferedWriter(FileWriter(file, true))
    }

    private fun closeCurrentWriter() {
        try { currentWriter?.flush(); currentWriter?.close() } catch (_: Exception) {}
        currentWriter = null
    }

    private fun ensureWriter() {
        if (currentWriter == null && currentFile != null) {
            openWriter(currentFile!!)
        }
    }

    private fun startAutoFlushTimer() {
        if (config.autoFlushInterval > 0 && flushTimer == null) {
            flushTimer = Timer("LogFlushTimer", true).apply {
                scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run() {
                            flush()
                        }
                    },
                    config.autoFlushInterval,
                    config.autoFlushInterval
                )
            }
        }
    }

    private fun stopAutoFlushTimer() {
        flushTimer?.cancel()
        flushTimer = null
    }
}
