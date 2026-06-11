package com.bjbyhd.screenreader_huawei.logger.file

import com.bjbyhd.screenreader_huawei.logger.api.LogConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 日志文件清理器
 *
 * 双重清理策略：
 *   1. 按时间清理：删除超过 maxRetainDays 天的日志文件
 *   2. 按数量清理：保留最新的 maxFileCount 个日志文件，删除最旧的
 *
 * 建议在 App 启动时（Logger.init 内部）调用一次 clean()。
 */
class LogFileCleaner(private val config: LogConfig) {

    fun clean() {
        val logDir = config.logDir
        if (!logDir.exists()) return

        val allFiles = logDir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return

        cleanByAge(allFiles)
        cleanByCount(allFiles)
    }

    private fun cleanByAge(files: MutableList<File>) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.maxRetainDays.toLong())
        val expired = files.filter { it.lastModified() < cutoff }
        expired.forEach { file ->
            file.delete()
            files.remove(file)
        }
    }

    private fun cleanByCount(files: MutableList<File>) {
        while (files.size > config.maxFileCount) {
            files.removeAt(0).delete()
        }
    }
}
