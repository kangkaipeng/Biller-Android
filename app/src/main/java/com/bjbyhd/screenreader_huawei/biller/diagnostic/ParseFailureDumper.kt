package com.bjbyhd.screenreader_huawei.biller.diagnostic

import android.content.Context
import com.bjbyhd.screenreader_huawei.logger.api.CLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解析失败诊断记录器 — 独立于日志系统
 *
 * 职责:
 *   - 当支付结果页门禁通过但后续解析失败时，将 texts 列表 + 完整树 dump 写入诊断文件
 *   - 文件位于 [filesDir/diagnostic/parse_failures.log]，不混入 CLog 日志目录
 *   - Profile 页提供导出入口，用户可通过系统分享面板导出文件
 *
 * 线程安全:
 *   - 时间戳 + 计数器在调用线程（可能是主线程）中捕获，保证顺序正确
 *   - 文件 I/O 通过 [scope] 调度到 Dispatchers.IO，不阻塞主线程
 *   - [writeMutex] 串行化写入，防止并发 dump 导致行交错
 *
 * 初始化: [BillerApplication.onCreate] 中调用 [init]
 *
 * ## 输出格式
 *
 * ```
 * ═══════════════════════════════════════════════════════════════
 *   Parse Failure #1  ·  2026-06-14 21:30:00.123  ·  Alipay
 *   Reason: 实付金额提取失败 (L1嵌入格式与L2拆分格式均未命中)
 * ═══════════════════════════════════════════════════════════════
 *
 *   -- Texts (12 items) --
 *   [ 0] 支付成功
 *   ...
 *
 *   -- Accessibility Tree --
 *   ...
 * ```
 *
 * 每条之间空一行。用 `adb pull` 或 Profile 页导出按钮获取文件。
 */
object ParseFailureDumper {

    private const val TAG = "DiagDump"

    /** 输出目录名，相对于 filesDir */
    private const val DIAG_DIR = "diagnostic"
    private const val DIAG_FILE = "parse_failures.log"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private var dumpFile: File? = null

    /** 计数器 — 每次 init 或清空文件后重置 */
    private var counter = 0

    /** 协程调度器 — 初始化时注入，用于将文件 I/O 调度到后台线程 */
    private var scope: CoroutineScope? = null

    /** 写入互斥锁 — 防止并发 dump 导致行交错 */
    private val writeMutex = Mutex()

    fun init(context: Context, scope: CoroutineScope) {
        this.scope = scope
        val dir = File(context.filesDir, DIAG_DIR)
        if (!dir.exists()) dir.mkdirs()
        dumpFile = File(dir, DIAG_FILE)
        // 文件存在时，统计已有条目数以延续编号；不存在则从 1 开始
        counter = if (dumpFile!!.exists()) {
            dumpFile!!.useLines { lines -> lines.count { it.startsWith("══════") && "Parse Failure" in it } }
        } else 0
        CLog.i(TAG) { "初始化完成, 已有 $counter 条诊断记录 (${dumpFile!!.absolutePath})" }
    }

    /**
     * 写入一条解析失败诊断记录。
     *
     * **保证调用线程不阻塞**: 时间戳与计数器在调用线程捕获后，
     * 文件 I/O 通过 [scope] 调度到后台线程执行。
     *
     * @param extractor 解析器标识: "Alipay" / "WeChat"
     * @param texts     DFS 收集到的全量文本列表
     * @param reason    失败原因，会显示在条目标题中
     * @param treeDump  完整无障碍树 dump 文本，由 [com.bjbyhd.screenreader_huawei.biller.parser.AccessibilityTreeDumper.dumpToString] 生成
     */
    fun dump(extractor: String, texts: List<String>, reason: String, treeDump: String) {
        val file = dumpFile
        if (file == null) {
            CLog.w(TAG) { "dump: 未初始化，跳过" }
            return
        }

        // ── 在调用线程捕获串行信息 ──
        counter++
        val seqNo = counter
        val now = timeFormat.format(Date())

        val s = scope
        if (s == null) {
            // scope 未注入时的 fallback: 同步写入 + warning
            CLog.w(TAG) { "scope 未初始化，fallback 同步写入 #$seqNo" }
            writeToFile(file, seqNo, now, extractor, texts, reason, treeDump)
            return
        }

        s.launch {
            writeMutex.withLock {
                writeToFile(file, seqNo, now, extractor, texts, reason, treeDump)
            }
        }
    }

    /**
     * 实际执行文件写入（在 Mutex 保护下串行调用）。
     *
     * @param file      目标文件
     * @param seqNo     预分配的序号
     * @param timestamp 预格式化的时间戳
     */
    private fun writeToFile(
        file: File,
        seqNo: Int,
        timestamp: String,
        extractor: String,
        texts: List<String>,
        reason: String,
        treeDump: String,
    ) {
        try {
            FileWriter(file, true).use { writer ->
                // ═══ 头区 ═══
                writer.appendLine("═══════════════════════════════════════════════════════════════")
                writer.append("  Parse Failure #$seqNo  ·  $timestamp  ·  $extractor")
                writer.appendLine()
                writer.appendLine("  Reason: $reason")
                writer.appendLine("═══════════════════════════════════════════════════════════════")
                writer.appendLine()

                // ═══ Texts 段 ═══
                writer.appendLine("  ── Texts (${texts.size} items) ──")
                for ((idx, text) in texts.withIndex()) {
                    val display = text.ifEmpty { "(empty)" }
                    writer.appendLine("  [${idx.toString().padStart(2)}] $display")
                }
                writer.appendLine()

                // ═══ Tree 段 ═══
                writer.appendLine("  ── Accessibility Tree ──")
                // treeDump 本身已包含缩进 + "══════ End of Tree Dump ══════"
                writer.append("$treeDump")
                writer.appendLine()

                writer.appendLine()
            }

            CLog.i(TAG) { "诊断记录已写入 #$seqNo: $reason" }
        } catch (e: Exception) {
            CLog.e(TAG, e) { "诊断记录写入失败 #$seqNo: ${e.message}" }
        }
    }

    /** 返回诊断文件，供 Profile 导出。若文件不存在返回 null。 */
    fun getFile(): File? {
        val file = dumpFile
        return if (file != null && file.exists() && file.length() > 0) file else null
    }
}
