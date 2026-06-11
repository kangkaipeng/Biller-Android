package com.bjbyhd.screenreader_huawei.biller.parser

import android.view.accessibility.AccessibilityNodeInfo
import com.bjbyhd.screenreader_huawei.logger.api.CLog

/**
 * 无障碍节点树全量输出工具
 *
 * 职责: 将 [AccessibilityNodeInfo] 整棵树递归 dump 到 CLog，用于调试和分析。
 *       输出每个节点的 className / text / contentDescription / childCount。
 *
 * 使用: 在 Parser 的 match() 之前调用，记录原始输入数据。
 *
 * 性能注意: 递归遍历整棵树并记录全部文本，仅在调试阶段使用。
 *           生产环境可降低日志等级或关闭。
 */
object AccessibilityTreeDumper {

    private const val MAX_DEPTH = 60
    private const val MAX_TEXT_LEN = 80

    /**
     * 将 rootNode 的整棵子树输出到日志
     *
     * @param root 根节点（不会被 recycle）
     * @param tag  CLog 标签
     */
    fun dump(root: AccessibilityNodeInfo, tag: String) {
        val sb = StringBuilder()
        sb.appendLine("══════ Accessibility Tree Dump ══════")
        dumpNode(root, sb, depth = 0)
        sb.append("══════ End of Tree Dump ══════")
        CLog.i(tag) { sb.toString() }
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) {
            sb.appendLine("${"  ".repeat(depth)}⚠ MAX_DEPTH reached")
            return
        }

        val indent = "  ".repeat(depth)
        val cls = node.className?.toString() ?: "?"
        val text = node.text?.toString()?.trim()?.take(MAX_TEXT_LEN) ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.take(MAX_TEXT_LEN) ?: ""
        val childCount = node.childCount

        sb.append("$indent[$cls]")
        if (text.isNotEmpty()) sb.append(" text=\"$text\"")
        if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
        sb.append(" children=$childCount")
        sb.appendLine()

        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dumpNode(child, sb, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }
}
