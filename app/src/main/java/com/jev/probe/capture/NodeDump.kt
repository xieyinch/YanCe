package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Renders the current window's accessibility tree as compact, copyable text.
 *
 * Why this exists: every adapter in [ChatAppAdapter] has to be written against the real
 * node structure of its app — resource-ids, class names and contentDescription formats
 * cannot be guessed. This dump is produced by the *same disguised service* the adapters
 * use, so what it shows is exactly what an adapter would see, including apps that hide
 * their tree from a plain `uiautomator dump`.
 *
 * Output is one line per node:
 *   depth | class | id | text | desc | [l,t][r,b] | flags
 * where flags are E(ditable) C(lickable) S(crollable).
 */
object NodeDump {

    private const val MAX_NODES = 1200
    private const val MAX_FIELD = 56

    /** @param packageFilter only emit nodes belonging to this package; null = everything. */
    fun render(root: AccessibilityNodeInfo?, packageFilter: String? = null): String {
        if (root == null) {
            return "读不到当前窗口。\n" +
                "可能原因：无障碍服务未连接；或当前界面没有可读窗口（例如刚解锁）。\n" +
                "请先在系统设置里确认言策的无障碍服务已开启，再停在要诊断的界面重试。\n"
        }

        val screen = Rect()
        root.getBoundsInScreen(screen)
        val pkg = root.packageName?.toString() ?: "?"
        val out = StringBuilder()
        out.append("包名=").append(pkg).append('\n')
        out.append("屏幕=").append(screen.width()).append('x').append(screen.height()).append('\n')
        out.append("列=depth|class|id|text|desc|bounds|flags(E=可编辑 C=可点击 S=可滚动)\n")
        out.append("-".repeat(72)).append('\n')

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var seen = 0
        var emitted = 0
        var editable = 0
        var withId = 0
        var withText = 0
        var withDesc = 0

        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            seen++
            if (node.isEditable) editable++
            if (!node.viewIdResourceName.isNullOrBlank()) withId++
            if (!node.text.isNullOrBlank()) withText++
            if (!node.contentDescription.isNullOrBlank()) withDesc++

            val nodePkg = node.packageName?.toString()
            if (packageFilter == null || nodePkg == packageFilter) {
                out.append(describe(node, depth)).append('\n')
                emitted++
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }

        if (seen >= MAX_NODES) out.append("…（达到 ").append(MAX_NODES).append(" 节点上限，已截断）\n")
        out.append("-".repeat(72)).append('\n')
        out.append("统计：遍历=").append(seen)
            .append(" 输出=").append(emitted)
            .append(" 可编辑=").append(editable)
            .append(" 有id=").append(withId)
            .append(" 有text=").append(withText)
            .append(" 有desc=").append(withDesc).append('\n')
        return out.toString()
    }

    private fun describe(node: AccessibilityNodeInfo, depth: Int): String {
        val b = Rect()
        node.getBoundsInScreen(b)
        val flags = buildString {
            if (node.isEditable) append('E')
            if (node.isClickable) append('C')
            if (node.isScrollable) append('S')
        }
        return buildString {
            append(depth).append('|')
            append(field(node.className?.toString()?.substringAfterLast('.') ?: ""))
            append('|').append(field(node.viewIdResourceName ?: ""))
            append('|').append(field(node.text?.toString() ?: ""))
            append('|').append(field(node.contentDescription?.toString() ?: ""))
            append("|[").append(b.left).append(',').append(b.top).append("][")
                .append(b.right).append(',').append(b.bottom).append(']')
            append('|').append(flags)
        }
    }

    private fun field(value: String): String {
        val flat = value.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_FIELD) flat else flat.take(MAX_FIELD) + "…"
    }
}
