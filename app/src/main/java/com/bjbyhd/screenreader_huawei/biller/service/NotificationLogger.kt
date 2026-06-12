package com.bjbyhd.screenreader_huawei.biller.service

import android.content.Context
import android.service.notification.StatusBarNotification
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 通知栏数据记录器
 *
 * 职责: 将所有命中目标包名的通知原始数据写入 Notification.log 文件。
 *       写入在单线程 Executor 中执行，避免主线程 I/O。
 */
object NotificationLogger {

    private const val TAG = "NotifyLog"

    private var logFile: File? = null
    private var scope: CoroutineScope? = null
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        logFile = File(context.filesDir, "Notification.log")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    fun log(sbn: StatusBarNotification) {
        val file = logFile ?: return
        val s = scope ?: return

        val title = sbn.notification.extras?.getString("android.title") ?: ""
        val text = sbn.notification.extras?.getString("android.text") ?: ""
        CLog.i(TAG) { "[通知] pkg=${sbn.packageName} | id=${sbn.id} | title=$title | text=$text" }

        s.launch {
            try {
                FileWriter(file, true).use { writer ->
                    writer.appendLine("══════ ${formatter.format(Instant.now().atZone(ZoneId.systemDefault()))} ══════")
                    writer.appendLine("packageName : ${sbn.packageName}")
                    writer.appendLine("id          : ${sbn.id}")
                    writer.appendLine("tag         : ${sbn.tag ?: "null"}")
                    writer.appendLine("key         : ${sbn.key ?: "null"}")
                    writer.appendLine("postTime    : ${sbn.postTime} (${formatter.format(Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()))})")
                    writer.appendLine("isClearable : ${sbn.isClearable}")
                    writer.appendLine("isOngoing   : ${sbn.isOngoing}")
                    writer.appendLine("groupKey    : ${sbn.groupKey ?: "null"}")

                    val n = sbn.notification
                    writer.appendLine("category    : ${n.category ?: "null"}")
                    writer.appendLine("tickerText  : ${n.tickerText ?: "null"}")

                    val extras = n.extras
                    if (extras != null && !extras.isEmpty) {
                        writer.appendLine("--- extras ---")
                        for (key in extras.keySet()) {
                            val value = extras.get(key)
                            writer.appendLine("  $key = $value")
                        }
                    } else {
                        writer.appendLine("extras: (empty)")
                    }

                    writer.appendLine()
                }
            } catch (e: Exception) {
                CLog.e(TAG, e) { "写入通知日志失败: ${e.message}" }
            }
        }
    }
}
