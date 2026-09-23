package com.jev.probe

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs
import com.jev.probe.ui.YanCeUi

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private val a11yComponent = "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    private fun dp(v: Int) = YanCeUi.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = YanCeUi.BG
        window.navigationBarColor = YanCeUi.BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(YanCeUi.BG); isFillViewport = true; addView(root)
        })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        root.removeAllViews()
        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val model = prefs.canAnalyze()
        val ready = a11y && overlay && model

        root.addView(topBar())
        root.addView(hero(ready))
        root.addView(TextView(this).apply {
            text = if (prefs.enabled) "助手运行中 · 点击暂停" else "启动悬浮助手"
            textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (prefs.enabled) YanCeUi.NAVY else android.graphics.Color.WHITE)
            background = YanCeUi.bg(this@MainActivity, if (prefs.enabled) YanCeUi.MINT else YanCeUi.NAVY, 16)
            minHeight = dp(56); setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
            }
            setOnClickListener { prefs.enabled = !prefs.enabled; render() }
        })

        root.addView(YanCeUi.text(this, "使用准备", 13f, YanCeUi.MUTED, true).apply {
            setPadding(dp(4), dp(28), 0, dp(2))
        })
        val readiness = YanCeUi.card(this, 0)
        readiness.addView(statusRow(android.R.drawable.ic_menu_view, "无障碍服务", "用于读取当前聊天内容", a11y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        readiness.addView(YanCeUi.divider(this))
        readiness.addView(statusRow(android.R.drawable.ic_menu_manage, "模型服务", "提供意图分析与回复建议", model) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        readiness.addView(YanCeUi.divider(this))
        readiness.addView(statusRow(android.R.drawable.ic_menu_share, "悬浮窗权限", "在聊天应用上显示建议", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(readiness)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        tools.addView(toolButton(android.R.drawable.ic_menu_preferences, "模型与偏好") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { rightMargin = dp(6) })
        tools.addView(toolButton(android.R.drawable.ic_menu_recent_history, "运行日志") {
            startActivity(Intent(this, LogActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { leftMargin = dp(6) })
        root.addView(tools)
        root.addView(YanCeUi.text(this, "内容仅在分析时读取，回复始终由你手动发送", 12f, YanCeUi.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
        })
    }

    private fun topBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(YanCeUi.text(this@MainActivity, "言策", 29f, YanCeUi.NAVY, true))
            addView(YanCeUi.text(this@MainActivity, "更好的对话，从此开始", 13f, YanCeUi.MUTED).apply { setPadding(0, dp(5), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(android.R.drawable.ic_menu_preferences); setColorFilter(YanCeUi.NAVY)
            background = YanCeUi.bg(this@MainActivity, YanCeUi.SURFACE, 14, YanCeUi.LINE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun hero(ready: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(18), dp(30), dp(18), dp(8))
        addView(TextView(this@MainActivity).apply {
            text = if (ready) "↗" else "!"; textSize = 44f; gravity = Gravity.CENTER
            setTextColor(YanCeUi.NAVY); setTypeface(typeface, Typeface.BOLD)
            background = YanCeUi.bg(this@MainActivity, YanCeUi.MINT_SOFT, 34)
        }, LinearLayout.LayoutParams(dp(112), dp(112)))
        addView(YanCeUi.text(this@MainActivity, if (ready) "助手已就绪" else "还差几步即可使用", 27f, YanCeUi.NAVY, true).apply {
            setPadding(0, dp(22), 0, 0)
        })
        addView(YanCeUi.text(this@MainActivity,
            if (ready) "切换到聊天界面即可获取回复建议" else "完成下方项目后即可开始辅助对话", 14f, YanCeUi.MUTED
        ).apply { setPadding(0, dp(9), 0, 0) })
    }

    private fun statusRow(icon: Int, title: String, desc: String, ok: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(15), dp(14), dp(15)); setOnClickListener { click() }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon); setColorFilter(YanCeUi.SUCCESS)
            background = YanCeUi.bg(this@MainActivity, YanCeUi.MINT_SOFT, 14)
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(13) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(YanCeUi.text(this@MainActivity, title, 15f, YanCeUi.TEXT, true))
            addView(YanCeUi.text(this@MainActivity, desc, 12f, YanCeUi.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(YanCeUi.text(this@MainActivity, if (ok) "✓  已开启" else "去开启  ›", 13f,
            if (ok) YanCeUi.SUCCESS else YanCeUi.DANGER, true).apply {
            background = YanCeUi.bg(this@MainActivity, if (ok) YanCeUi.MINT_SOFT else 0xFFFFEEF0.toInt(), 18)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        })
    }

    private fun toolButton(icon: Int, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        background = YanCeUi.bg(this@MainActivity, YanCeUi.SURFACE, 18, YanCeUi.LINE); setOnClickListener { click() }
        addView(ImageView(this@MainActivity).apply { setImageResource(icon); setColorFilter(YanCeUi.NAVY) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(9) })
        addView(YanCeUi.text(this@MainActivity, label, 14f, YanCeUi.NAVY, true))
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(a11yComponent)
    }
}
