package com.jev.probe

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.AppLog
import com.jev.probe.ui.YanCeUi

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
}
