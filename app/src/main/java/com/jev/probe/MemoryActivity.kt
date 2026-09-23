package com.jev.probe

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.RelationshipMemory
import com.jev.probe.ui.YanCeUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryActivity : AppCompatActivity() {
    private lateinit var memory: RelationshipMemory
    private lateinit var list: LinearLayout
    private fun dp(v: Int) = YanCeUi.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory = RelationshipMemory(this)
        window.statusBarColor = YanCeUi.BG; window.navigationBarColor = YanCeUi.BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(28)); setBackgroundColor(YanCeUi.BG)
        }
        root.addView(YanCeUi.text(this, "‹   关系档案", 26f, YanCeUi.NAVY, true).apply { setOnClickListener { finish() } })
        root.addView(YanCeUi.text(this, "只保存精简事件，不保存完整聊天原文", 13f, YanCeUi.MUTED).apply {
            setPadding(dp(3), dp(8), 0, dp(16))
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button(if (memory.paused) "恢复记录" else "暂停记录") {
            memory.paused = !memory.paused; recreate()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        controls.addView(button("清空全部") { confirmClear() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(controls)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })
        setContentView(root); render()
    }

    private fun render() {
        list.removeAllViews()
        val profiles = memory.profiles()
        if (profiles.isEmpty()) {
            list.addView(YanCeUi.text(this, "暂无关系档案\n\n启用记忆后，完成一次关系分析就会在这里生成精简记录。", 14f, YanCeUi.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(dp(20), dp(80), dp(20), dp(20))
            }); return
        }
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        profiles.forEach { profile ->
            list.addView(YanCeUi.card(this).apply {
                addView(LinearLayout(this@MemoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(this@MemoryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(YanCeUi.text(this@MemoryActivity, profile.title, 16f, YanCeUi.TEXT, true))
                        addView(YanCeUi.text(this@MemoryActivity, "${profile.events.size} 条事件 · ${format.format(Date(profile.updatedAt))}", 12f, YanCeUi.MUTED).apply { setPadding(0, dp(5), 0, 0) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(YanCeUi.text(this@MemoryActivity, "删除", 13f, YanCeUi.DANGER, true).apply {
                        setPadding(dp(10), dp(8), dp(4), dp(8)); setOnClickListener { confirmForget(profile.title) }
                    })
                })
                profile.events.takeLast(5).forEach { event ->
                    addView(YanCeUi.text(this@MemoryActivity, "• $event", 12f, YanCeUi.MUTED).apply {
                        setPadding(0, dp(9), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
                    })
                }
            })
        }
    }

    private fun button(label: String, click: () -> Unit) = YanCeUi.text(this, label, 14f, YanCeUi.NAVY, true).apply {
        gravity = Gravity.CENTER; background = YanCeUi.bg(this@MemoryActivity, YanCeUi.SURFACE, 14, YanCeUi.LINE)
        setOnClickListener { click() }
    }

    private fun confirmForget(title: String) = AlertDialog.Builder(this)
        .setTitle("忘记这个联系人？").setMessage("将删除“$title”的全部精简事件，且无法恢复。")
        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> memory.forget(title); render() }.show()

    private fun confirmClear() = AlertDialog.Builder(this)
        .setTitle("清空全部关系档案？").setMessage("所有本地精简事件都会被永久删除。")
        .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
            memory.clear(); render(); Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }.show()
}
