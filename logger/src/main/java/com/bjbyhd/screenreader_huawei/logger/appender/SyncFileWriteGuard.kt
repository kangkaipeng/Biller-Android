package com.bjbyhd.screenreader_huawei.logger.appender

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * 同步文件写入守卫 — 测试专用实现
 *
 * 在 FileAppender.append 调用时，同步将日志写入指定的测试文件，
 * 确保方法返回后数据已落盘，便于测试断言验证。
 */
class SyncFileWriteGuard(private val outputFile: File) {

    init {
        outputFile.parentFile?.mkdirs()
    }

    /**
     * 同步写入日志到测试文件
     *
     * 使用 BufferedWriter + FileWriter 追加模式写入，
     * 每次写入后立即 flush，确保数据落盘。
     */
    @Synchronized
    fun guard(message: String) {
        try {
            BufferedWriter(FileWriter(outputFile, true)).use { writer ->
                writer.write(message)
                writer.newLine()
                writer.flush()
            }
        } catch (_: Exception) {
            // 测试环境静默处理，不影响主流程
        }
    }
}
