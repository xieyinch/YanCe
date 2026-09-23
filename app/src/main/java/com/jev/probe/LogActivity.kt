package com.jev.probe

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.AppLog

class LogActivity : AppCompatActivity() {
    private lateinit var content: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 28)
            setBackgroundColor(Color.parseColor("#F2F3F5"))
        }
        root.addView(TextView(this).apply {
            text = "运行日志"; textSize = 24f; setTextColor(Color.parseColor("#111827"))
        })
        root.addView(TextView(this).apply {
            text = "只记录运行阶段与错误，不记录聊天原文、API Key。"
            textSize = 12f; setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 8, 0, 12)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "刷新"; setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "清空"; setOnClickListener { AppLog.clear(); refresh() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        content = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#1F2937"))
            setTextIsSelectable(true); gravity = Gravity.START
            setPadding(0, 12, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        content.text = AppLog.read().ifBlank { "暂无日志" }
    }
}
