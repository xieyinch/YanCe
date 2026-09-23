package com.jev.probe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.AppLog
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(header("设置"))

        // --- 接口 ---
        root.addView(section("模型供应商"))
        val card1 = card()
        card1.addView(label("JEV Key"))
        val jevKeyEdit = edit(prefs.jevKey, "只需填写 Key，接口与模型已内置", password = true)
        card1.addView(jevKeyEdit)
        card1.addView(text("JEV 可选：填写后负责判断与排序；留空时自动使用下方大语言模型完成全部分析。", 12f, sub).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        card1.addView(label("自定义供应商名称"))
        val providerNameEdit = edit(prefs.llmProviderName, "例如：我的 API")
        card1.addView(providerNameEdit)
        card1.addView(label("接口协议"))
        val protocolNames = listOf("OpenAI 兼容", "Google Gemini", "Anthropic Claude")
        val protocolIds = listOf("openai", "gemini", "claude")
        val protocol = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, protocolNames)
            setSelection(protocolIds.indexOf(prefs.llmProtocol).coerceAtLeast(0))
        }
        card1.addView(protocol)
        card1.addView(label("API Key"))
        val chatKeyEdit = edit(prefs.chatKey, "供应商 API Key", password = true)
        card1.addView(chatKeyEdit)
        card1.addView(label("生成回复请求地址"))
        val chatUrlEdit = edit(prefs.chatUrl, "https://example.com/v1/chat/completions")
        card1.addView(chatUrlEdit)
        card1.addView(text("Gemini 地址可用 {model} 作为模型占位符。", 12f, sub).apply {
            setPadding(0, dp(5), 0, 0)
        })

        var fetchedModels = emptyList<String>()
        val addedModels = prefs.availableModels.toMutableSet()
        card1.addView(label("当前使用模型"))
        val modelItems = addedModels.sorted().ifEmpty { listOf("（请先拉取并添加模型）") }.toMutableList()
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelItems)
        val modelSpinner = Spinner(this).apply { adapter = modelAdapter }
        card1.addView(modelSpinner)
        if (prefs.replyModel.isNotBlank()) {
            val index = addedModels.sorted().indexOf(prefs.replyModel)
            if (index >= 0) modelSpinner.setSelection(index)
        }
        val modelStatus = text("已添加 ${addedModels.size} 个模型", 12f, sub).apply {
            setPadding(0, dp(8), 0, 0)
        }
        card1.addView(modelStatus)
        card1.addView(secondaryBtn("拉取供应商模型") {
            val key = chatKeyEdit.text.toString().trim()
            val url = chatUrlEdit.text.toString().trim()
            if (key.isBlank() || !validEndpoint(url)) {
                modelStatus.text = "请先填写 API Key 和完整 HTTPS 请求地址"
                return@secondaryBtn
            }
            modelStatus.text = "正在拉取模型…"
            worker.execute {
                try {
                    val models = JevClient.fetchModels(protocolIds[protocol.selectedItemPosition], key, url)
                    main.post {
                        fetchedModels = models
                        modelStatus.text = if (models.isEmpty()) "供应商未返回可用模型" else "已拉取 ${models.size} 个模型，可一键添加"
                    }
                } catch (e: Exception) {
                    main.post { modelStatus.text = "拉取失败：${e.message ?: "未知错误"}" }
                }
            }
        })
        card1.addView(secondaryBtn("一键添加全部模型") {
            if (fetchedModels.isEmpty()) { modelStatus.text = "请先拉取模型"; return@secondaryBtn }
            addedModels.addAll(fetchedModels); prefs.availableModels = addedModels
            modelAdapter.clear(); modelAdapter.addAll(addedModels.sorted()); modelAdapter.notifyDataSetChanged()
            modelStatus.text = "已添加全部 ${addedModels.size} 个模型"
        })
        card1.addView(secondaryBtn("一键取消添加全部模型") {
            addedModels.clear(); prefs.availableModels = emptySet(); prefs.replyModel = ""
            modelAdapter.clear(); modelAdapter.add("（请先拉取并添加模型）"); modelAdapter.notifyDataSetChanged()
            modelStatus.text = "已取消添加全部模型"
        })
        root.addView(card1)

        // --- 分析 ---
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("分析模式"))
        val analysisModeNames = listOf("关系军师（推荐）", "通用助手（更简洁）")
        val analysisModeIds = listOf(Prefs.MODE_RELATIONSHIP, Prefs.MODE_GENERAL)
        val analysisMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, analysisModeNames)
            setSelection(analysisModeIds.indexOf(prefs.analysisMode).coerceAtLeast(0))
        }
        card2.addView(analysisMode)
        card2.addView(text("关系军师会先区分事实、推测与未知，再结合情绪、互惠和边界生成回复。", 12f, sub).apply {
            setPadding(0, dp(5), 0, dp(2))
        })
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        root.addView(card2)

        // --- 外观 ---
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // --- Actions ---
        val result = text("", 13f, sub).apply { setPadding(0, dp(12), 0, dp(4)) }
        root.addView(primaryBtn("保存") {
            val chatUrl = chatUrlEdit.text.toString().trim()
            if (!validEndpoint(chatUrl)) { result.text = "请求地址须为完整 HTTPS URL"; return@primaryBtn }
            if (addedModels.isEmpty()) { result.text = "请先拉取并添加至少一个模型"; return@primaryBtn }
            prefs.jevKey = jevKeyEdit.text.toString()
            prefs.llmProviderName = providerNameEdit.text.toString().ifBlank { "自定义供应商" }
            prefs.llmProtocol = protocolIds[protocol.selectedItemPosition]
            prefs.chatKey = chatKeyEdit.text.toString()
            prefs.chatUrl = chatUrl
            prefs.availableModels = addedModels
            prefs.replyModel = modelSpinner.selectedItem?.toString()?.takeUnless { it.startsWith("（") } ?: ""
            prefs.relationship = relEdit.text.toString().ifBlank { Prefs.DEFAULT_REL }
            prefs.analysisMode = analysisModeIds[analysisMode.selectedItemPosition]
            prefs.whitelist = wlEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })
        root.addView(secondaryBtn("连通测试") {
            val key = jevKeyEdit.text.toString().trim()
            val chatKey = chatKeyEdit.text.toString().trim()
            val model = modelSpinner.selectedItem?.toString()?.takeUnless { it.startsWith("（") } ?: ""
            val chatUrl = chatUrlEdit.text.toString().trim()
            if (chatKey.isBlank() || model.isBlank()) { result.text = "请填写大语言模型 Key 并选择模型"; return@secondaryBtn }
            if (!validEndpoint(chatUrl)) {
                result.text = "请求地址须为完整 HTTPS URL"; return@secondaryBtn
            }
            result.text = "测试中…"
            worker.execute {
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在"), Msg("other", "那你说说昨天答应我的事")))
                val a = JevClient(key, model, Prefs.JEV_URL, Prefs.JEV_MODEL, chatKey, chatUrl,
                    protocolIds[protocol.selectedItemPosition],
                    analysisModeIds[analysisMode.selectedItemPosition]).analyzeStrict(demo, relEdit.text.toString())
                main.post {
                    if (a.error != null) AppLog.e("连通测试", a.error)
                    else AppLog.i("连通测试", if (key.isBlank()) "大模型独立分析成功" else "Jev + 大模型分析成功")
                    result.text = if (a.error != null) "失败：" + a.error
                    else "成功（" + (if (key.isBlank()) "大模型独立分析" else "Jev增强") +
                        "）：意图=" + (a.trueIntent?.choice ?: "?") + "，候选=" + a.rankedReplies.size + " 条"
                }
            }
        })
        root.addView(result)

        root.addView(section("诊断"))
        val logCard = card()
        logCard.addView(text("遇到接口或解析问题时，可在运行日志中查看时间、处理阶段和错误原因。日志不会保存聊天原文或密钥。", 12f, sub))
        logCard.addView(secondaryBtn("查看运行日志") {
            startActivity(Intent(this, LogActivity::class.java))
        })
        root.addView(logCard)

        setContentView(scroll)
    }

    private fun validEndpoint(value: String): Boolean = try {
        val url = java.net.URL(value)
        url.protocol == "https" && url.host.isNotBlank() && url.toURI().userInfo == null
    } catch (_: Exception) { false }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    private fun secondaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(12), Color.WHITE, stroke = true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }
}
