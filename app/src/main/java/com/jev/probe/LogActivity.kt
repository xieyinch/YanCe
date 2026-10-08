package com.jev.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.ChatCaptureService
import com.jev.probe.core.AppLog
import com.jev.probe.ui.YanCeUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {
    private lateinit var content: TextView
    private lateinit var count: TextView
    private fun dp(v: Int) = YanCeUi.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        window.statusBarColor = YanCeUi.BG
        window.navigationBarColor = YanCeUi.BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)); setBackgroundColor(YanCeUi.BG)
        }
        root.addView(YanCeUi.text(this, "‹   运行日志", 26f, YanCeUi.NAVY, true).apply { setOnClickListener { finish() } })
        root.addView(YanCeUi.text(this, "定位接口、模型与解析问题", 13f, YanCeUi.MUTED).apply { setPadding(dp(3), dp(8), 0, dp(20)) })

        val summary = YanCeUi.card(this).apply {
            addView(LinearLayout(this@LogActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@LogActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(YanCeUi.text(this@LogActivity, "隐私安全日志", 16f, YanCeUi.TEXT, true))
                    addView(YanCeUi.text(this@LogActivity, "不记录聊天原文或 API Key", 12f, YanCeUi.MUTED).apply { setPadding(0, dp(5), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                count = YanCeUi.text(this@LogActivity, "0 条", 13f, YanCeUi.SUCCESS, true).apply {
                    background = YanCeUi.bg(this@LogActivity, YanCeUi.MINT_SOFT, 18); setPadding(dp(12), dp(7), dp(12), dp(7))
                }
                addView(count)
            })
        }
        root.addView(summary)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)) }
        actions.addView(action("刷新") { refresh() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        actions.addView(action("清空日志") { AppLog.clear(); refresh() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(actions)

        // Node-tree capture: the only way to adapt a new chat app, since resource-ids and
        // contentDescription formats cannot be guessed. Uses the same accessibility service
        // the capture adapters use, so it shows exactly what an adapter would see.
        val diag = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
        diag.addView(action("抓取界面节点") { captureNodes() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        diag.addView(action("复制当前内容") { copyContent() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(diag)
        root.addView(YanCeUi.text(this,
            "适配新聊天应用：先在设置里开启「诊断悬浮球」，停在目标应用的聊天界面点那个红色「诊」球。" +
                "下面这个按钮只能抓到言策自己 —— 点它的瞬间前台就是言策。",
            12f, YanCeUi.MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        content = YanCeUi.text(this, "", 12f, YanCeUi.TEXT).apply {
            setTextIsSelectable(true); gravity = Gravity.START; setLineSpacing(dp(4).toFloat(), 1f)
            background = YanCeUi.bg(this@LogActivity, YanCeUi.SURFACE, 18, YanCeUi.LINE)
            setPadding(dp(16), dp(16), dp(16), dp(16)); typeface = Typeface.MONOSPACE
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun action(label: String, click: () -> Unit) = YanCeUi.text(this, label, 14f, YanCeUi.NAVY, true).apply {
        gravity = Gravity.CENTER; background = YanCeUi.bg(this@LogActivity, YanCeUi.SURFACE, 14, YanCeUi.LINE)
        setOnClickListener { click() }
    }

    private fun refresh() {
        val value = AppLog.read()
        val lines = value.lineSequence().count { it.isNotBlank() }
        count.text = "$lines 条"
        content.text = value.ifBlank { "暂无日志\n\n运行助手或执行连通测试后，记录会显示在这里。" }
    }

    /** Dump the foreground window's node tree, keep a copy on disk, and show it here. */
    private fun captureNodes() {
        val dump = ChatCaptureService.dumpForegroundText()
        val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        runCatching {
            val dir = File(filesDir, "dumps").apply { mkdirs() }
            File(dir, "nodes-$stamp.txt").writeText(dump, Charsets.UTF_8)
        }
        content.text = dump
        count.text = "节点树"
    }

    private fun copyContent() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("yance_nodes", content.text))
        Toast.makeText(this, "已复制，可直接粘贴发回", Toast.LENGTH_SHORT).show()
    }
}
