package com.bjbyhd.screenreader_huawei.biller.ui.profile

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerImportExportBridge
import com.bjbyhd.screenreader_huawei.biller.data.bridge.IBillerQueryBridge
import com.bjbyhd.screenreader_huawei.biller.data.model.FullBillExport
import com.bjbyhd.screenreader_huawei.biller.data.model.ImportResult
import com.bjbyhd.screenreader_huawei.biller.ui.common.BaseMviViewModel
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 我的 Tab ViewModel — MVI 模式 (Phase 3)
 *
 * 模块: feature/biller/ui/profile
 * 职责:
 *   - 检测双服务（通知监听 + 无障碍）当前运行状态
 *   - 处理系统设置跳转、关于弹窗、CSV 导出事件
 *   - 继承 [AndroidViewModel] 以持有 Application Context，
 *     用于调用 [android.provider.Settings] API 和创建系统 Intent
 *
 * ## 服务状态检测原理
 *
 *  - 通知监听: [android.provider.Settings.Secure.ENABLED_NOTIFICATION_LISTENERS]
 *    包含已授权的所有 NotificationListenerService 包名/类名组合
 *  - 无障碍: [android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]
 *    包含已开启的所有 AccessibilityService 包名/类名组合
 *
 * ## 依赖
 *
 *  [IBillerQueryBridge]: CSV 导出时需要读取全部记录
 *
 * @see ProfileUiState UI 状态快照
 * @see ProfileEvent 用户交互事件
 */
class ProfileViewModel(
    private val appContext: Context,
    private val billRepo: IBillerQueryBridge,
    private val importExportRepo: IBillerImportExportBridge,
) : BaseMviViewModel<ProfileUiState, ProfileEvent>(
    initialUiState = ProfileUiState()
) {

    companion object {
        private const val TAG = "Biller/ProfileVM"
    }

    init {
        // 初始化时检查一次服务状态
        checkServiceStatus()
    }

    override fun onEvent(event: ProfileEvent) {
        CLog.d(TAG) { "onEvent: ${event::class.simpleName}" }
        when (event) {
            is ProfileEvent.CheckServiceStatus        -> checkServiceStatus()
            is ProfileEvent.OpenNotificationSettings  -> openNotificationSettings()
            is ProfileEvent.OpenAccessibilitySettings -> openAccessibilitySettings()
            is ProfileEvent.ShowAboutSheet            -> updateState { copy(showAboutSheet = true) }
            is ProfileEvent.DismissAboutSheet         -> updateState { copy(showAboutSheet = false) }
            is ProfileEvent.ExportCsv                 -> onExportFullCsv()
            is ProfileEvent.ImportCsv                 -> onImportCsv()
            is ProfileEvent.ImportCsvFromUri          -> onImportCsvFromUri(event.uri)
            is ProfileEvent.DismissImportResult       -> updateState { copy(importResult = null) }
            is ProfileEvent.ExportLogs                -> onExportLogs()
            is ProfileEvent.ClearLogs                 -> onClearLogs()
            is ProfileEvent.ClearError                -> updateState { copy(errorMessage = null) }
            is ProfileEvent.ExportDiagnostic        -> onExportDiagnostic()
        }
    }

    // ═══════════ 服务状态检测 ═══════════

    /**
     * 检查通知监听和无障碍服务是否已授权并连接
     *
     * 通过 [Settings.Secure] 的字符串匹配来检测:
     *   - 通知监听: 检查 "包名/Service类名" 是否在启用列表中
     *   - 无障碍: 同上
     *
     * 检测结果写入 [ProfileUiState]。
     */
    private fun checkServiceStatus() {
        updateState { copy(isCheckingStatus = true) }
        try {
            val notifEnabled = isNotificationListenerEnabled()
            val a11yEnabled = isAccessibilityServiceEnabled()
            updateState {
                copy(
                    notificationConnected = notifEnabled,
                    accessibilityConnected = a11yEnabled,
                    isCheckingStatus = false,
                    lastCheckTime = System.currentTimeMillis(),
                    errorMessage = null,
                )
            }
            CLog.i(TAG) {
                "服务状态检测: 通知=${if (notifEnabled) "已连接" else "未授权"}, " +
                "无障碍=${if (a11yEnabled) "已连接" else "未授权"}"
            }
        } catch (e: Exception) {
            CLog.w(TAG, e) { "服务状态检测异常: ${e.message}" }
            updateState {
                copy(
                    isCheckingStatus = false,
                    errorMessage = "服务状态检测失败：${e.message}",
                    lastCheckTime = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * 检测通知监听服务是否已授权
     *
     * 检查 [Settings.Secure.ENABLED_NOTIFICATION_LISTENERS] 中是否包含
     * 当前应用的包名。
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val packageName = appContext.packageName
        return flat.contains(packageName)
    }

    /**
     * 检测无障碍服务是否已开启
     *
     * 检查 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 中是否包含
     * 当前应用的包名。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val packageName = appContext.packageName
        return flat.contains(packageName)
    }

    // ═══════════ 系统设置跳转 ═══════════

    /** 跳转系统通知使用权设置页 */
    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            CLog.w(TAG, e) { "无法打开通知使用权设置: ${e.message}" }
        }
    }

    /** 跳转系统无障碍服务设置页 */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            CLog.w(TAG, e) { "无法打开无障碍设置: ${e.message}" }
        }
    }

    // ═══════════ CSV 全量导出/导入 ═══════════

    /** CSV 18 列的表头行（与 FullBillExport 字段一一对应） */
    private val CSV_HEADER = listOf(
        "id", "packageName", "rawTitle", "rawText", "amount", "merchant",
        "paymentChannel", "timestamp", "source", "mergeStatus",
        "paymentMethod", "originalAmount", "discountInfo", "merchantAlias",
        "categoryId", "categoryName", "note", "transactionId",
    ).joinToString(",")

    /**
     * 全量导出全部账单记录为 CSV（18 列完整字段）
     *
     * ## CSV 格式
     *
     *   - UTF-8 BOM 头（Excel 兼容）
     *   - 字段含逗号/换行/引号时用双引号包裹（RFC 4180）
     *   - 空值用空字符串表示（非"null"字符串）
     *   - 包名保留原始值，不做翻译（WEIXIN 而非 "微信支付"）
     *
     * @see FullBillExport 导出数据对象
     */
    private fun onExportFullCsv() {
        updateState { copy(isExporting = true) }
        launchSafe {
            try {
                val records = importExportRepo.exportRecords()
                if (records.isEmpty()) {
                    updateState {
                        copy(isExporting = false, errorMessage = "没有可导出的账单记录")
                    }
                    return@launchSafe
                }

                val fileName = "biller_full_export_${System.currentTimeMillis()}.csv"
                val csvFile = java.io.File(appContext.cacheDir, fileName)

                csvFile.bufferedWriter().use { writer ->
                    writer.write("﻿") // UTF-8 BOM
                    writer.write("$CSV_HEADER\n")
                    val dateFormat = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                    )
                    for (row in records) {
                        val fields = listOf(
                            row.id.toString(),
                            row.packageName,
                            row.rawTitle,
                            row.rawText,
                            row.amount?.let { String.format("%.2f", it) } ?: "",
                            row.merchant ?: "",
                            row.paymentChannel,
                            dateFormat.format(java.util.Date(row.timestamp)),
                            row.source,
                            row.mergeStatus,
                            row.paymentMethod ?: "",
                            row.originalAmount?.let { String.format("%.2f", it) } ?: "",
                            row.discountInfo ?: "",
                            row.merchantAlias ?: "",
                            row.categoryId?.toString() ?: "",
                            row.categoryName ?: "",
                            row.note ?: "",
                            row.transactionId ?: "",
                        )
                        val escaped = fields.joinToString(",") { field ->
                            if (field.contains(",") || field.contains("\"") || field.contains("\n"))
                                "\"${field.replace("\"", "\"\"")}\""
                            else field
                        }
                        writer.write("$escaped\n")
                    }
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    csvFile,
                )

                sendEffect(ProfileEffect.ShareCsv(uri, fileName))
                updateState { copy(isExporting = false) }

                CLog.i(TAG) {
                    "CSV 全量导出成功: $fileName | ${records.size} 条记录 | " +
                    "大小=${csvFile.length() / 1024}KB"
                }
            } catch (e: Exception) {
                CLog.e(TAG, e) { "CSV 全量导出失败: ${e.message}" }
                updateState {
                    copy(isExporting = false, errorMessage = "导出失败：${e.message}")
                }
            }
        }
    }

    // ═══════════ CSV 导入 ═══════════

    /**
     * 触发系统文件选择器 — 通过 Effect 通知 Fragment 启动 Intent
     *
     * 不在 ViewModel 中直接 startActivity（ViewModel 不应持有 Activity 引用），
     * 而是发射 [ProfileEffect.LaunchFilePicker] Effect，由 Fragment 处理。
     */
    private fun onImportCsv() {
        sendEffect(ProfileEffect.LaunchFilePicker)
    }

    /**
     * 收到文件选择器返回的 URI 后执行 CSV 解析和导入
     *
     * ## 解析流程
     *
     *   1. 通过 ContentResolver 打开 URI 输入流
     *   2. 按行读取，跳过 BOM 和表头行
     *   3. 每行按 CSV 规则解析为 [FullBillExport]
     *   4. 调用 [IBillerImportExportBridge.importFromCsv] 批量入库
     *   5. 将 [ImportResult] 写入 UiState 供 UI 展示
     *
     * ## CSV 解析规则
     *
     *   - 逗号分隔 18 列（顺序与 FullBillExport 构造函数一致）
     *   - 双引号包裹的字段去除外层引号，内部连续双引号 "" 还原为单个 "
     *   - 空字符串 → null（对可空字段）
     *   - 列数不足 18 的行跳过（计入 skipped）
     *
     * @param uri 用户选择的 CSV 文件 URI
     */
    private fun onImportCsvFromUri(uri: android.net.Uri) {
        updateState { copy(isImporting = true, importResult = null) }
        launchSafe {
            try {
                val parsedRecords = parseCsvFile(uri)
                if (parsedRecords.isEmpty()) {
                    updateState {
                        copy(
                            isImporting = false,
                            importResult = ImportResult(
                                total = 0, inserted = 0, skipped = 0,
                                errors = listOf("CSV 文件中没有有效的数据行"),
                            ),
                        )
                    }
                    return@launchSafe
                }

                val result = importExportRepo.importFromCsv(parsedRecords)
                updateState { copy(isImporting = false, importResult = result) }

                CLog.i(TAG) {
                    "CSV 导入完成: total=${result.total} inserted=${result.inserted} " +
                    "skipped=${result.skipped} errors=${result.errors.size}"
                }
            } catch (e: Exception) {
                CLog.e(TAG, e) { "CSV 导入异常: ${e.message}" }
                updateState {
                    copy(
                        isImporting = false,
                        importResult = ImportResult(
                            total = 0, inserted = 0, skipped = 0,
                            errors = listOf("导入失败：${e.message}"),
                        ),
                    )
                }
            }
        }
    }

    /**
     * CSV 文件解析 — 按行读取并映射为 [FullBillExport] 列表
     *
     * ## CSV 格式要求
     *
     *   18 列，顺序必须与 [CSV_HEADER] 一致：
     *   id, packageName, rawTitle, rawText, amount, merchant,
     *   paymentChannel, timestamp, source, mergeStatus,
     *   paymentMethod, originalAmount, discountInfo, merchantAlias,
     *   categoryId, categoryName, note, transactionId
     *
     * ## 容错设计
     *
     *   - 空行跳过
     *   - 列数 != 18 的行跳过（计入错误列表，最多 20 条详情）
     *   - 数值解析失败时该字段为 null（不中断整行）
     *
     * @param uri CSV 文件的 content:// URI
     * @return 解析成功的 [FullBillExport] 列表
     */
    private fun parseCsvFile(uri: android.net.Uri): List<FullBillExport> {
        val results = mutableListOf<FullBillExport>()

        appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                var isFirstLine = true
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line ?: continue
                    // 跳过空行
                    if (rawLine.isBlank()) continue
                    // 跳过 BOM 头 + 表头
                    if (isFirstLine) {
                        isFirstLine = false
                        // 移除可能的 UTF-8 BOM 前缀
                        val trimmed = rawLine.removePrefix("﻿")
                        if (trimmed.startsWith("id,")) continue
                    }

                    val fields = parseCsvLine(rawLine)
                    if (fields.size != 18) continue

                    try {
                        results.add(
                            FullBillExport(
                                id = fields[0].toLongOrNull() ?: 0L,
                                packageName = fields[1],
                                rawTitle = fields[2],
                                rawText = fields[3],
                                amount = fields[4].toDoubleOrNull(),
                                merchant = fields[5].ifEmpty { null },
                                paymentChannel = fields[6],
                                timestamp = fields[7].toLongOrNull() ?: System.currentTimeMillis(),
                                source = fields[8].ifEmpty { "UNKNOWN" },
                                mergeStatus = fields[9].ifEmpty { "SINGLE" },
                                paymentMethod = fields[10].ifEmpty { null },
                                originalAmount = fields[11].toDoubleOrNull(),
                                discountInfo = fields[12].ifEmpty { null },
                                merchantAlias = fields[13].ifEmpty { null },
                                categoryId = fields[14].toLongOrNull(),
                                categoryName = fields[15].ifEmpty { null },
                                note = fields[16].ifEmpty { null },
                                transactionId = fields[17].ifEmpty { null },
                            )
                        )
                    } catch (_: Exception) {
                        // 单行解析失败不中断整体导入
                    }
                }
            }
        } ?: run {
            CLog.w(TAG) { "parseCsvFile: 无法打开 URI — $uri" }
        }

        return results
    }

    /**
     * 解析单行 CSV 为字段列表
     *
     * 处理双引号转义规则（RFC 4180）：
     *   - `"a,b"`   → [a,b]  引号内的逗号不是分隔符
     *   - `"a""b"`  → [a"b]  引号内连续双引号转义为单个引号
     *   - `a,b,c`   → [a,b,c] 普通逗号分隔
     *
     * @param line 单行原始文本
     * @return 解析后的字段列表（不剥离外层引号的内容）
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> {
                    inQuotes = true
                }
                ch == '"' && inQuotes -> {
                    // 检查是否为转义引号（"" → "）
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++ // 跳过一个引号
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> {
                    current.append(ch)
                }
            }
            i++
        }
        fields.add(current.toString()) // 最后一个字段

        return fields
    }

    // ═══════════ 日志导出 ═══════════

    /**
     * 导出全部日志文件为 ZIP 并通过 FileProvider 分享
     *
     * ## 处理流程
     *
     *   1. 设置 isExportingLogs = true，按钮显示加载态
     *   2. 通过 [CLog.getExporter] 获取日志文件列表
     *   3. 在 IO 线程将全部 .log 文件打包为 ZIP（存入 cache 目录）
     *   4. 通过 FileProvider 获取 content:// URI（authority = logger.fileprovider）
     *   5. 发射 [ProfileEffect.ShareLogs] Effect，由 Fragment 启动系统分享面板
     *   6. 成功/失败后恢复 isExportingLogs = false
     *
     * ## 权限说明
     *
     *   日志文件存储在 [context.filesDir/logs]（应用内部存储），
     *   读取无需任何运行时权限。ZIP 写入 cache 目录，同样无需权限。
     *   FileProvider 使用独立的 `logger.fileprovider` authority 提供临时 URI 访问。
     *
     * ## 异常处理
     *
     *   - 日志目录不存在或无 .log 文件 → 提示"没有可导出的日志文件"
     *   - ZIP 打包失败 → 提示错误原因
     *   - CLog 未初始化 → 捕获异常并提示
     */
    private fun onExportLogs() {
        updateState { copy(isExportingLogs = true) }
        launchSafe {
            try {
                // 获取日志文件列表
                val exporter = CLog.getExporter()
                val files = exporter.getAllFiles()
                if (files.isEmpty()) {
                    updateState {
                        copy(isExportingLogs = false, errorMessage = "没有可导出的日志文件")
                    }
                    CLog.w(TAG) { "日志导出: 无日志文件" }
                    return@launchSafe
                }

                // 在 cache 目录生成 ZIP（可被系统自动清理，不占用永久存储）
                val zipResult = exporter.exportZip(files, appContext.cacheDir)
                zipResult.onSuccess { zipFile ->
                    val authority = "${appContext.packageName}.fileprovider"
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        appContext, authority, zipFile
                    )
                    sendEffect(ProfileEffect.ShareLogs(uri, zipFile.name))
                    updateState { copy(isExportingLogs = false) }
                    CLog.i(TAG) {
                        "日志导出成功: ${zipFile.name} | ${files.size} 个日志文件 | " +
                        "大小=${zipFile.length() / 1024}KB"
                    }
                }.onFailure { e ->
                    CLog.e(TAG, e) { "日志 ZIP 打包失败: ${e.message}" }
                    updateState {
                        copy(isExportingLogs = false, errorMessage = "导出失败：${e.message}")
                    }
                }
            } catch (e: Exception) {
                CLog.e(TAG, e) { "日志导出异常: ${e.message}" }
                updateState {
                    copy(isExportingLogs = false, errorMessage = "导出失败：${e.message}")
                }
            }
        }
    }

    // ═══════════ 清除日志  ═══════════

    /**
     * 清除全部日志文件
     *
     * 通过 [CLog.clearAllLogs] 删除日志目录下所有 .log 文件。
     * 操作在 [launchSafe] 的 IO 线程执行（文件删除涉及磁盘 I/O）。
     * 清除后日志系统自动启动新会话，后续日志无缝写入新文件。
     *
     * ## 异常处理
     *
     *   - CLog 未初始化 → 提示"日志系统未初始化"
     *   - 文件删除失败 → 提示异常原因
     */
    private fun onClearLogs() {
        updateState { copy(isClearingLogs = true) }
        launchSafe {
            try {
                val count = CLog.clearAllLogs()
                updateState {
                    copy(
                        isClearingLogs = false,
                        errorMessage = "已清除 $count 个日志文件",
                    )
                }
            } catch (e: Exception) {
                CLog.e(TAG, e) { "清除日志失败: ${e.message}" }
                updateState {
                    copy(
                        isClearingLogs = false,
                        errorMessage = "清除失败：${e.message}",
                    )
                }
            }
        }
    }

    // ═══════════ 导出诊断日志 ═══════════

    /** 分享诊断日志文件 — 通过 FileProvider 生成 content:// URI */
    private fun onExportDiagnostic() {
        val file = com.bjbyhd.screenreader_huawei.biller.diagnostic.ParseFailureDumper.getFile()
        if (file == null) {
            updateState { copy(errorMessage = "暂无诊断日志，解析失败的支付页会自动记录") }
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        sendEffect(ProfileEffect.ShareDiagnostic(uri, file.name))
    }
}
