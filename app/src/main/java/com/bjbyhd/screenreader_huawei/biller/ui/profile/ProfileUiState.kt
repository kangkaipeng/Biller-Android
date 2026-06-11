package com.bjbyhd.screenreader_huawei.biller.ui.profile

import com.bjbyhd.screenreader_huawei.biller.data.model.ImportResult

/**
 * 我的 Tab MVI — UiState / Event / Effect 定义 (Phase 3, v5.3 全量导入导出)
 *
 * 模块: feature/biller/ui/profile
 * 职责: 定义"我的"页面的不可变 UI 状态和用户交互事件。
 *
 * ## 页面内容
 *
 *   1. 双服务状态指示（通知监听 + 无障碍）
 *   2. 跳转系统设置入口按钮
 *   3. CSV 全量导出（18 列完整字段）
 *   4. CSV 全量导入（通过系统文件选择器）
 *   5. 日志导出/清除
 *   6. 关于 BottomSheet 入口
 */

/**
 * 我的页 UI 状态快照
 *
 * @property notificationConnected   通知监听服务是否已授权并连接
 * @property accessibilityConnected  无障碍服务是否已开启并连接
 * @property isCheckingStatus        是否正在刷新服务状态
 * @property showAboutSheet          是否显示关于 BottomSheet
 * @property errorMessage            服务检测/操作的错误信息（展示后清除）
 * @property lastCheckTime           最后检查服务状态的时间戳（ms），null = 尚未检查
 * @property isExporting             CSV 全量导出是否正在进行中
 * @property isImporting             CSV 导入是否正在进行中
 * @property importResult            CSV 导入完成后的结果统计（展示后清除）
 * @property isExportingLogs         日志导出是否正在进行中
 * @property isClearingLogs          日志清除是否正在进行中
 */
data class ProfileUiState(
    val notificationConnected: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val isCheckingStatus: Boolean = false,
    val showAboutSheet: Boolean = false,
    val errorMessage: String? = null,
    val lastCheckTime: Long? = null,
    /** CSV 全量导出是否正在进行中 */
    val isExporting: Boolean = false,
    /** CSV 导入是否正在进行中 */
    val isImporting: Boolean = false,
    /** CSV 导入结果（展示后通过 DismissImportResult 清除） */
    val importResult: ImportResult? = null,
    /** 日志导出是否正在进行中 */
    val isExportingLogs: Boolean = false,
    /** 日志清除是否正在进行中 */
    val isClearingLogs: Boolean = false,
)

/**
 * 我的页用户交互事件
 */
sealed interface ProfileEvent {
    /** 重新检查双服务状态 */
    data object CheckServiceStatus : ProfileEvent
    /** 跳转系统通知使用权设置页 */
    data object OpenNotificationSettings : ProfileEvent
    /** 跳转系统无障碍服务设置页 */
    data object OpenAccessibilitySettings : ProfileEvent
    /** 打开关于 BottomSheet */
    data object ShowAboutSheet : ProfileEvent
    /** 关闭关于 BottomSheet */
    data object DismissAboutSheet : ProfileEvent
    /** 全量导出 CSV（18 列完整字段） */
    data object ExportCsv : ProfileEvent
    /** 通过系统文件选择器导入 CSV */
    data object ImportCsv : ProfileEvent
    /** 收到文件选择器返回的 URI 后执行导入 */
    data class ImportCsvFromUri(val uri: android.net.Uri) : ProfileEvent
    /** 清除导入结果提示 */
    data object DismissImportResult : ProfileEvent
    /** 导出日志 — 将全部 .log 文件打包为 ZIP 并通过系统分享 */
    data object ExportLogs : ProfileEvent
    /** 清除全部日志文件 — 删除所有 .log 文件并启动新会话 */
    data object ClearLogs : ProfileEvent
    /** 清除错误消息（Snackbar 展示后调用） */
    data object ClearError : ProfileEvent
}

/**
 * 一次性副作用 — 我的 Tab
 *
 * 通过 Effect Channel 发射，处理需要系统协作的一次性操作。
 */
sealed interface ProfileEffect {
    /** 分享 CSV 文件的 content:// URI */
    data class ShareCsv(val uri: android.net.Uri, val fileName: String) : ProfileEffect
    /** 分享日志 ZIP 文件的 content:// URI (v5.2) */
    data class ShareLogs(val uri: android.net.Uri, val fileName: String) : ProfileEffect
    /** 打开系统文件选择器选择 CSV 文件 (v5.3) */
    data object LaunchFilePicker : ProfileEffect
}
