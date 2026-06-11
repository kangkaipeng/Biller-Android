package com.bjbyhd.screenreader_huawei.logger.file

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bjbyhd.screenreader_huawei.logger.api.LogConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志文件导出工具
 *
 * 职责：
 *   - 获取指定时间范围内的日志文件
 *   - 将多个日志文件打包为 ZIP
 *   - 调起系统分享，方便用户一键反馈
 */
class LogFileExporter(
    private val context: Context,
    private val config: LogConfig
) {

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    fun getFilesInRange(
        fromMs: Long,
        toMs: Long = System.currentTimeMillis()
    ): List<File> {
        val dir = config.logDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            f.name.endsWith(".log") &&
            f.lastModified() in fromMs..toMs
        }?.sortedBy { it.lastModified() } ?: emptyList()
    }

    fun getAllFiles(): List<File> {
        val dir = config.logDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
    }

    fun exportZip(
        files: List<File>,
        outputDir: File = config.logDir
    ): Result<File> {
        return runCatching {
            outputDir.mkdirs()
            val zipName = "logs_export_${dateFormat.format(Date())}.zip"
            val zipFile = File(outputDir, zipName)

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEach { logFile ->
                    if (!logFile.exists()) return@forEach
                    zos.putNextEntry(ZipEntry(logFile.name))
                    FileInputStream(logFile).use { fis ->
                        fis.copyTo(zos, bufferSize = 8192)
                    }
                    zos.closeEntry()
                }
            }
            zipFile
        }
    }

    /**
     * 一键导出全部日志并分享
     *
     * 使用方式：
     *   - 在设置页面添加"分享日志"按钮
     *   - 需要在 AndroidManifest 中声明 FileProvider
     *     authority 格式为 "${applicationId}.logger.fileprovider"
     *
     * @param authority FileProvider authority，需在 AndroidManifest 中声明
     */
    fun shareAllLogs(
        authority: String = "${context.packageName}.logger.fileprovider"
    ) {
        val files = getAllFiles()
        if (files.isEmpty()) return

        val zipResult = exportZip(files)
        zipResult.onSuccess { zipFile ->
            share(zipFile, authority)
        }
    }

    fun share(
        zipFile: File,
        authority: String = "${context.packageName}.logger.fileprovider"
    ) {
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "应用日志导出 - ${zipFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志文件"))
    }
}
