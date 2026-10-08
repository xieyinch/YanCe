package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * Per-app capture rules. An adapter turns one messaging app's open chat window
 * into a neutral [ChatSnapshot]; everything downstream (Jev judgment, overlay,
 * fill) is app-agnostic. [extract] returns null when the current window is not
 * that app's chat (e.g. its home/list screen), so the service shows nothing.
 *
 * The disguised accessibility service (registered as SelectToSpeakService) lets
 * us read the node tree of apps that obfuscate it for normal services (WeChat).
 * Feishu/Lark does not obfuscate, so its adapter reads plain resource-ids.
 */
interface ChatAppAdapter {
    val pkg: String

    /** Label shown next to this app's switch in settings. */
    val displayName: String

    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

/** Shared helpers. */
private fun looksLikeTimestamp(t: String): Boolean =
    Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
        Regex("""\d+月\d+日""").containsMatchIn(t) ||
        t == "昨天" || t == "今天"

/**
 * Conversation title in the top action bar: the topmost short, roughly centered
 * text above the first message bubble. Constrained so we never grab an in-chat
 * timestamp. Used by WeChat, and by QQ as a fallback when its title id is absent.
 */
private fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX) {
                if (b.top < bestTop) { bestTop = b.top; best = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return best
}

/** WeChat (com.tencent.mm). Message bubbles carry a stable id; sender side is
 *  the bubble's horizontal position (right = me, left = other). */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"
    override val displayName = "微信"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        var firstBubbleTop = Int.MAX_VALUE

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank() && !isWeChatSystemNotice(text)) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Triple(b.top, b.centerX(), text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty()) return null

        val title = findTitleInActionBar(root, firstBubbleTop, width, res)
        bubbles.sortBy { it.first }
        val msgs = bubbles.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs)
    }

    companion object {
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"
    }
}

/**
 * WeChat's own notices are rendered in the message list and match the bubble id, so they were
 * being read as if the other person had said them — the reported case being
 * 「对方账号安全性未知…」, WeChat's warning about an unverified account.
 *
 * ⚠️ **Stopgap.** This recognises them by WeChat's own wording, which a real message is very
 * unlikely to reproduce verbatim — but wording-based detection is brittle and incomplete, and it
 * cannot see notices WeChat has not used yet. The proper fix is structural (these notices carry
 * no avatar and are horizontally centred, unlike a real bubble), and that needs a node dump of a
 * chat actually showing one: 「运行日志」→ 开启「诊断悬浮球」→ 停在微信该聊天 → 点红色「诊」球.
 *
 * Deliberately narrow: 「…撤回了一条消息」and 「对方正在输入」 are NOT filtered, because a real
 * message could contain those words, and a false negative here silently drops what someone said.
 */
private val WECHAT_NOTICE_SNIPPETS = listOf(
    "账号安全性未知",
    "以下为打招呼消息",
    "对方开启了朋友验证",
    "消息已发出，但被对方拒收",
    "该消息已过期",
    "该内容已被发布者删除"
)

private fun isWeChatSystemNotice(text: String): Boolean =
    WECHAT_NOTICE_SNIPPETS.any { text.contains(it) }

/**
 * Mobile QQ (com.tencent.mobileqq). Nodes are NOT obfuscated (verified on QQ
 * 9.3.50 / Xiaomi 14, 1200x2670): message bodies are plain TextViews carrying
 * `id/mjn`, so collecting only that id already excludes timestamps, sender
 * nicknames (`id/mjq`) and the full-width system notice strips.
 *
 * The whole app lives under one SplashActivity (fragment architecture), so
 * "are we in a chat window" can only be answered by the tree itself — here, by
 * whether any `id/mjn` node exists. No bodies → null.
 *
 * Sender side: QQ pins the avatar to the outer edge of its own side (others on
 * the left at x≈156/1200 ≈ 13% of width, me on the right at width−156). A long
 * incoming message can push its center past mid-screen, so we compare which
 * edge of the bubble hugs its avatar column instead of using the center point.
 */
class QQAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mobileqq"
    override val displayName = "手机 QQ"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        // top, left, right, text
        val bubbles = ArrayList<Bubble>()
        var firstBubbleTop = Int.MAX_VALUE
        var title: String? = null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Bubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty()) return null

        if (title == null) title = findTitleInActionBar(root, firstBubbleTop, width, res)

        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Bubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
    }
}

/** Feishu / Lark (com.ss.android.lark). Nodes are not obfuscated. Plain-text
 *  message bodies render as bare TextViews inside the bubble, so we collect the
 *  message-area text views and drop the chrome (top tabs, title, sender name,
 *  timestamps, system notices, the input box). Sender side = horizontal
 *  position, same as WeChat. */
class FeishuAdapter : ChatAppAdapter {
    override val pkg = "com.ss.android.lark"
    override val displayName = "飞书"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.14).toInt()      // action bar + tab row
        val bottomBand = (height * 0.84).toInt()   // input box + keyboard

        var isChat = false
        var title: String? = null
        val items = ArrayList<Triple<Int, Int, String>>() // top, centerX, text

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName ?: ""
            if (id.endsWith(":id/message") || id.endsWith(":id/bubble_content_container")) isChat = true
            if (id.endsWith(":id/group_name")) node.text?.toString()?.let { if (title == null) title = it }

            val text = node.text?.toString()
            val cls = node.className?.toString()
            if (!text.isNullOrBlank() && cls == "android.widget.TextView" && !isChrome(id) && !looksLikeTimestamp(text)) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.top in (topBand + 1) until bottomBand) {
                    items.add(Triple(b.top, b.centerX(), text.trim()))
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isChat || items.isEmpty()) return null

        items.sortBy { it.first }
        val msgs = items.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs)
    }

    /** Non-message UI text to skip: title, sender name, time, system notices,
     *  the input EditText. Bodies have no id (bare TextView) so they pass. */
    private fun isChrome(id: String): Boolean =
        id.endsWith(":id/group_name") ||
            id.endsWith(":id/name_tv") ||
            id.endsWith(":id/date_tv") ||
            id.endsWith(":id/system_label") ||
            id.endsWith(":id/kb_rich_text_content") ||
            id.endsWith(":id/thread_title_tv") ||
            id.endsWith(":id/thread_subtitle_tv")
}

/** Trailing "8:11 上午" / "10:29 下午" / "8:11 AM" stamp X glues onto a message. */
private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")

/** X uses "。" as a field separator, so a message can end with a run of them. */
private val X_TRAILING_DOTS = Regex("""。+$""")

/**
 * Split one X DM row's contentDescription into (sender, body).
 *
 * "你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。"
 *      → ("你", "你这个说的就是那个虚拟人物，是吗？")
 * "你：他这个东西开源应该问题不大。。。Read。"
 *      → ("你", "他这个东西开源应该问题不大")
 * "All-In：附加的帖子。。"          → ("All-In", "附加的帖子")
 *
 * The sender is everything before the FIRST separator (full-width "：" in the
 * Chinese UI, ": " as a rough fallback elsewhere); the rest is the body plus
 * chrome — the read receipt, the timestamp, and the "。" gluing them on — which
 * is stripped from the tail in that order. Punctuation the user actually typed
 * ("是吗？") survives. Null when there is no separator or nothing is left.
 */
private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/**
 * X / Twitter (com.twitter.android) direct messages. Verified on X 12.25.2 /
 * Xiaomi 14 (1200x2670), Chinese system language.
 *
 * The DM thread is Compose UI: each message is a bare `android.view.View` with
 * NO resource-id, full screen width and empty text — the whole message lives in
 * contentDescription ("All-In：重新写了一个😂。10:29 下午。"). The date divider is a
 * TextView with no "：", so filtering on class + full width + a separator keeps
 * it out. An attachment row ("All-In：附加的帖子。。") nests the quoted post's own
 * TextViews; we only take the row View's own desc, never its children.
 *
 * Every screen runs under the same MainActivity, so "are we in a DM thread" can
 * only be answered by the tree: a thread has the message EditText, the DM list
 * does not. The list's rows look similar but read
 * "All-In, @all_in_2026, 你这个说的就是那…", so ", @" is an extra guard.
 *
 * Side comes from the sender label ("你" / "You"), not geometry — every row is
 * full width no matter who spoke.
 */
class XAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"
    override val displayName = "X / Twitter 私信"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<Row>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true

            val desc = node.contentDescription?.toString()
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(Row(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // No input box → this is the DM list (or some other X screen), not a chat.
        if (!hasInput || rows.isEmpty()) return null

        // X left-aligns the thread title (x≈300..443 of 1200), so widen the
        // shared helper's "roughly centered" band for this app.
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val sender: String, val text: String)
}

/**
 * 小红书 / RedNote direct messages (`com.xingin.xhs`).
 *
 * **结构是实测的，不是猜的。** Calibrated against a node dump taken from the real app
 * (小红书 9.49.1, 1080x2376) through the in-app diagnostic ball:
 *
 * ```
 * 7 |ViewGroup                       [0,123][1080,255]     标题栏
 * 12|TextView   "莫西莫西"            [249,162][441,217]
 * 8 |FrameLayout                     [0,255][1080,423]     推广条：列表的【兄弟】节点，盖在列表上
 * 10|TextView   "加个关注，方便以后常聊" [0,255][768,387]
 * 10|TextView   "关注"                [768,279][948,363]
 * 8 |RecyclerView   CS               [0,257][1080,2076]    ← 消息列表
 * 9 |LinearLayout   C                [0,257][1080,382]     一行消息
 * 12|RelativeLayout 头像              [48,257][156,352]     ← 对方：贴左边缘
 * 15|TextView   "哈哈 玩不来"          [180,257][491,358]
 * ...
 * 12|RelativeLayout 头像              [924,796][1032,910]   ← 我：贴右边缘
 * 15|TextView   "看你帖子以为原呢…"    [180,796][900,970]
 * 10|TextView   "14:02"              [499,1798][581,1839]  时间分隔（正则排除）
 * 10|RecyclerView   S                [0,2076][1080,2172]   ← 快捷短语行：第二个 RecyclerView
 * 10|EditText   "发消息…"             [180,2226][786,2346]  ← 输入框
 * ```
 *
 * What that dump settles:
 *
 *  - **The tree is fully readable** (175 nodes, `有text=19 有desc=0`). 小红书 does *not* hide its
 *    nodes from this service the way WeChat 8.0.52+ does, and message bodies live in
 *    `TextView.text` — never in `contentDescription`.
 *  - Resource-ids really are all `com.xingin.xhs:id/0_resource_name_obfuscated` at runtime,
 *    exactly as the APK analysis predicted. Id-keyed lookup is impossible; this adapter never
 *    reads ids.
 *  - The message list is the **largest vertical scrollable** node; the quick-phrase row is a
 *    second, horizontally-shaped RecyclerView below it.
 *  - **The header promo strip overlaps the list's bounds but is a sibling**, drawn on top. So
 *    "text whose bounds fall inside the list rectangle" is not enough — 「关注」 would be read as
 *    a message. Only text that is a **descendant of the list node** counts as conversation.
 *  - Sides come from the **avatar**, pinned to the outer edge (x≈48 for the other person,
 *    x≈1032 for me). Long bubbles fill the same horizontal span on both sides, so bubble
 *    geometry alone cannot separate them — which is why the avatar, not the bubble, decides.
 *
 * Confirmed working on the device it was calibrated against — the recognised messages, their
 * order and their sides were all correct — so it is controlled by its own switch in settings,
 * like every other adapter.
 */
class XhsAdapter : ChatAppAdapter {
    override val pkg = "com.xingin.xhs"
    override val displayName = "小红书私信"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels

        // Pass 1: the composer proves we are inside a thread (the DM list, the feed and a profile
        // have none); the largest vertical scroller is the message list.
        var composerTop = -1
        var list: AccessibilityNodeInfo? = null
        var listArea = 0L
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 8000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString().orEmpty()
            val b = Rect()
            node.getBoundsInScreen(b)
            if (node.isEditable || cls.endsWith("EditText")) {
                if (composerTop < 0 || b.top < composerTop) composerTop = b.top
            } else if (node.isScrollable && b.width() > 0 && b.height() > b.width()) {
                val area = b.width().toLong() * b.height()
                if (area > listArea) { listArea = area; list = node }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (composerTop < 0) return null

        // Pass 2: collect message text from the list subtree. Walking from the list node — rather
        // than filtering the whole screen by the list's rectangle — is what keeps the promo strip
        // and the quick-phrase row out: both overlap or sit below the list but are not inside it.
        val container = list ?: root
        val topLimit = (height * 0.15).toInt()
        val hScrollers = ArrayList<IntArray>()
        val candidates = ArrayList<XhsLine>()
        val stack2 = ArrayDeque<AccessibilityNodeInfo>()
        stack2.addLast(container)
        var guard2 = 0
        while (stack2.isNotEmpty() && guard2 < 8000) {
            guard2++
            val node = stack2.removeLast()
            val cls = node.className?.toString().orEmpty()
            val b = Rect()
            node.getBoundsInScreen(b)
            if (list == null && node.isScrollable && b.width() > 0 && b.height() > 0 &&
                b.height() <= b.width()) {
                hScrollers.add(intArrayOf(b.left, b.top, b.right, b.bottom))  // quick-phrase row
            }
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && cls.endsWith("TextView") && !node.isEditable &&
                b.width() > 0 && b.height() > 0 && !looksLikeTimestamp(text)
            ) {
                candidates.add(XhsLine(Rect(b), text, node))
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack2.addLast(it) }
        }

        // With a list node, membership in its subtree is the whole test. Without one we fall back
        // to a band above the composer, minus horizontal scrollers (only now fully collected).
        val lines = if (list != null) candidates else candidates.filter {
            inFallbackBand(
                top = it.bounds.top, bottom = it.bounds.bottom,
                centerX = it.bounds.centerX(), centerY = it.bounds.centerY(),
                composerTop = composerTop, topLimit = topLimit, hScrollers = hScrollers
            )
        }
        if (lines.isEmpty()) return null

        // A bubble wrapper and its inner text share a row; keep one of them.
        val deduped = lines.sortedBy { it.bounds.top }
            .distinctBy { "${it.bounds.top / 8}|${it.bounds.centerX() / 8}|${it.text}" }

        val title = findTitleInActionBar(root, deduped.first().bounds.top, width, res, 0.15, 0.85)
        val msgs = deduped.map { Msg(sideOf(it.node, it.bounds, width), it.text) }
        return ChatSnapshot(title, msgs)
    }

    /**
     * Which side sent this message.
     *
     * The avatar decides, not the bubble: 小红书 pins the avatar to the outer edge of whichever
     * side spoke (x≈48 on the left, x≈1032 on the right), whereas a long bubble spans the same
     * horizontal range on both sides — in the calibration dump both
     * 「我之前玩倩女幽魂那种的手游 你玩过吗」 and my 「看你帖子以为原呢…」 measure [180..900].
     * So we climb a few parents looking for a small, edge-hugging sibling on the same row.
     */
    private fun sideOf(node: AccessibilityNodeInfo, self: Rect, width: Int): String {
        var cur = node.parent
        var hops = 0
        // The calibration dump puts the avatar 4 levels up (text → bubble → content → avatar),
        // so 6 leaves headroom for an extra wrapper without reaching the list's own children —
        // and those could not match anyway, since they never overlap this row vertically.
        while (cur != null && hops < 6) {
            for (i in 0 until cur.childCount) {
                val child = cur.getChild(i) ?: continue
                val b = Rect()
                child.getBoundsInScreen(b)
                if (b.width() <= 0 || b.height() <= 0) continue
                if (b.width() > width * 0.30) continue                       // that is the bubble
                if (b.left == self.left && b.top == self.top &&
                    b.right == self.right && b.bottom == self.bottom) continue
                if (b.bottom <= self.top || b.top >= self.bottom) continue    // must share the row
                avatarSide(b.centerX(), width)?.let { return it }
            }
            cur = cur.parent
            hops++
        }
        return bubbleSide(self.centerX(), width)
    }

    private data class XhsLine(val bounds: Rect, val text: String, val node: AccessibilityNodeInfo)
}

/** The avatar column: pinned near one outer edge, so its centre is decisive on its own. */
private fun avatarSide(centerX: Int, width: Int): String? = when {
    centerX < width * 0.25 -> "other"
    centerX > width * 0.75 -> "me"
    else -> null
}

/** Fallback when no avatar was found: even short bubbles still hug their own side. */
private fun bubbleSide(centerX: Int, width: Int): String =
    if (centerX > width / 2) "me" else "other"

/**
 * The no-list fallback: a text node counts only if it sits above the composer, below [topLimit],
 * and outside every horizontal scroller. Plain integers rather than Android types, so this stays
 * unit-testable without a device.
 */
private fun inFallbackBand(
    top: Int,
    bottom: Int,
    centerX: Int,
    centerY: Int,
    composerTop: Int,
    topLimit: Int,
    hScrollers: List<IntArray>
): Boolean {
    if (bottom > composerTop) return false
    for (r in hScrollers) {
        if (centerX in r[0]..r[2] && centerY in r[1]..r[3]) return false
    }
    return top >= topLimit
}

/**
 * Every adapted chat app, in the order shown in settings.
 *
 * Each one is gated by its own switch ([Prefs.enabledAppsOrNull]): a package the user has not
 * enabled is never read, analysed or sent anywhere — the capture service refuses it before any
 * adapter runs.
 */
val CHAT_ADAPTERS: List<ChatAppAdapter> = listOf(
    WeChatAdapter(),
    QQAdapter(),
    XAdapter(),
    FeishuAdapter(),
    XhsAdapter()
)
