package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.ui.YanCeUi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 255, 255, 255)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = "言"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(245, 23, 26, 58))
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "言策建议"; setTextColor(YanCeUi.NAVY); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var downTime = 0L; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false; downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("隐藏助手（本次）") { hide() })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#111827")); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.jev.probe.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high (clear of the
            // input box), regardless of which edge the bubble was snapped to.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
        }
        android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        ensureRoot(); bubble?.alpha = 0.55f
        setContent(listOf(
            stateView("助手已连接", if (title.isNullOrBlank()) "等待新消息，也可以手动分析" else "当前会话：$title", false),
            bigButton("分析当前对话") { onManualAnalyze?.invoke() }
        ))
    }

    fun showQueued(title: String?) {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(stateView("已识别到新消息", "正在准备分析${title?.let { " · $it" } ?: ""}", true)))
        if (!expanded) toggle()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(14, YanCeUi.NAVY)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    fun showLoading() {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(stateView("正在理解这段对话", "分析意图、风险与合适的回应…", true)))
        if (!expanded) toggle()
    }

    fun showError(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg),
            reAnalyzeBtn()))
        if (!expanded) toggle()
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(analysis: Analysis, onFill: (String) -> Unit) {
        lastFill = onFill
        val a = analysis
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null; expanded = false
        lastJudgment = null; lastFill = null
    }

    fun resetConversation(title: String?) {
        lastJudgment = null; lastFill = null
        if (isShowing()) showIdle(title)
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun stateView(title: String, subtitle: String, loading: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = card(14, Color.parseColor("#F7F8FA"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        if (loading) addView(ProgressBar(ctx).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(11) })
        else addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(YanCeUi.MINT) }
        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(line(title, "#171A3A", 14f, true))
            addView(line(subtitle, "#747B98", 12f))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()

        if (a.safetyLevel != "normal") {
            views.add(safetyBanner(a.safetyLevel, a.safetySignals, a.safetyAdvice))
        }

        a.emotionSupport?.let {
            views.add(line(it, "#171A3A", 14f, true))
            views.add(divider())
        }

        // Danger badge — the alarm signal, up top and color-coded.
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            views.add(dangerBadge(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            views.add(line("对方可能想表达：${INTENT[it.choice] ?: it.choice}", "#171A3A", 15f, true))
            views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }

        a.roundGoal?.let { views.add(tagLine("本轮目标", it)) }
        a.reciprocityState?.let { views.add(detailBlock("互惠状态", RECIPROCITY[it] ?: it)) }
        a.conflictType?.takeUnless { it == "none" }?.let { views.add(detailBlock("冲突类型", CONFLICT[it] ?: it)) }
        if (a.facts.isNotEmpty()) views.add(detailBlock("能确认", a.facts.joinToString("；")))
        a.inference?.let { views.add(detailBlock("暂时推测", it)) }
        a.unknown?.let { views.add(detailBlock("仍不知道", it)) }

        views.add(divider())
        views.add(line("候选回复（智能排序）", "#9CA3AF", 12f))
        if (generating) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt(), fill))
            }
            if (a.rankedReplies.isEmpty()) views.add(hint("（未生成候选回复）"))
        }
        a.nextStep?.let { views.add(detailBlock("下一步", it)) }
        a.stopCondition?.let { views.add(detailBlock("停止条件", it)) }
        if (!generating && listOf(a.positiveBranch, a.ambiguousBranch, a.negativeBranch).any { it != null }) {
            views.add(divider())
            views.add(line("对方接下来如果…", "#747B98", 12f, true))
            a.positiveBranch?.let { views.add(branchLine("积极", it, "#159A83")) }
            a.ambiguousBranch?.let { views.add(branchLine("含糊", it, "#D97706")) }
            a.negativeBranch?.let { views.add(branchLine("拒绝", it, "#D45665")) }
        }
        views.add(reAnalyzeBtn())

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int, onFill: (String) -> Unit): View {
        val top = rank == 1
        val cardBg = if (top) YanCeUi.MINT_SOFT else Color.parseColor("#F7F8FA")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = "建议 $rank · ${pct}%"; setTextColor(YanCeUi.SUCCESS); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#111827")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        btns.addView(pill("填入", true) { android.util.Log.d("JEVASSIST", "overlay: fill tapped"); onFill(text); if (expanded) toggle() })
        c.addView(btns)
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else YanCeUi.NAVY)
        background = card(18, if (primary) YanCeUi.NAVY else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#6B7280"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tagLine(label: String, value: String) = TextView(ctx).apply {
        text = "$label · $value"; textSize = 12f; setTextColor(YanCeUi.SUCCESS)
        setTypeface(typeface, Typeface.BOLD); background = card(16, YanCeUi.MINT_SOFT)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    private fun safetyBanner(level: String, signals: List<String>, advice: String?): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val danger = level == "danger"
        background = card(14, if (danger) Color.parseColor("#FFF0F1") else Color.parseColor("#FFF7E8"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(line(if (danger) "⚠ 检测到明确安全风险" else "⚠ 建议先确认安全", if (danger) "#B42335" else "#A15C00", 14f, true))
        if (signals.isNotEmpty()) addView(line("原文信号：${signals.joinToString("；")}", "#4B526F", 12f))
        advice?.let { addView(line(it, "#171A3A", 12f, true)) }
        if (danger) addView(line("如有现实危险，请优先离开现场并联系可信人员、110 或 120。", "#B42335", 12f, true))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun detailBlock(label: String, value: String) = TextView(ctx).apply {
        text = "$label｜$value"; textSize = 12f; setTextColor(Color.parseColor("#4B526F"))
        setPadding(0, dp(5), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun branchLine(label: String, value: String, color: String) = TextView(ctx).apply {
        text = "$label：$value"; textSize = 12f; setTextColor(Color.parseColor(color))
        setPadding(0, dp(5), 0, 0)
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
        private val RECIPROCITY = mapOf(
            "mutual" to "双方有来有回", "insufficient" to "证据还不够",
            "imbalanced" to "持续投入失衡", "rejected" to "对方已明确拒绝", "danger" to "存在安全风险")
        private val CONFLICT = mapOf(
            "misunderstanding" to "信息误解", "solvable" to "可解决问题",
            "persistent_difference" to "持续差异", "core_incompatibility" to "核心不兼容",
            "power_safety" to "权力或安全问题")
    }
}
