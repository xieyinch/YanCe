package com.jev.probe.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, local and user-controlled relationship memory.
 * Stores bounded summaries only; never stores full captured conversations.
 */
class RelationshipMemory(context: Context) {
    private val sp = context.getSharedPreferences("yance_relationship_memory", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(value) = sp.edit().putBoolean("paused", value).apply()

    data class Profile(val title: String, val updatedAt: Long, val events: List<String>)

    fun remember(title: String?, analysis: Analysis) {
        val safeTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (!enabled || paused || analysis.error != null) return

        val parts = ArrayList<String>()
        if (analysis.facts.isNotEmpty()) parts += "事实：" + analysis.facts.joinToString("；")
        analysis.roundGoal?.let { parts += "本轮目标：$it" }
        analysis.nextStep?.let { parts += "下一步：$it" }
        analysis.stopCondition?.let { parts += "停止条件：$it" }
        if (parts.isEmpty()) return

        val summary = parts.joinToString("｜").replace(Regex("\\s+"), " ").take(MAX_EVENT_CHARS)
        val root = readRoot()
        val key = keyFor(safeTitle)
        val profile = root.optJSONObject(key) ?: JSONObject().put("title", safeTitle).put("events", JSONArray())
        val old = profile.optJSONArray("events") ?: JSONArray()
        val updated = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("text", summary))
        val start = (old.length() - (MAX_EVENTS - 1)).coerceAtLeast(0)
        for (i in start until old.length()) updated.put(old.optJSONObject(i))
        profile.put("title", safeTitle.take(60)).put("updated_at", System.currentTimeMillis()).put("events", updated)
        root.put(key, profile)
        sp.edit().putString("profiles", root.toString()).apply()
    }

    fun contextFor(title: String?): String {
        val safeTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return ""
        if (!enabled) return ""
        val profile = readRoot().optJSONObject(keyFor(safeTitle)) ?: return ""
        val events = profile.optJSONArray("events") ?: return ""
        val lines = ((events.length() - 5).coerceAtLeast(0) until events.length())
            .mapNotNull { events.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
        if (lines.isEmpty()) return ""
        return "\n\n本地关系档案（历史摘要，仅作背景，若与当前原文冲突以当前原文为准）：\n" +
            lines.joinToString("\n") { "- $it" }
    }

    fun profiles(): List<Profile> {
        val root = readRoot()
        return root.keys().asSequence().mapNotNull { key ->
            val item = root.optJSONObject(key) ?: return@mapNotNull null
            val events = item.optJSONArray("events") ?: JSONArray()
            Profile(
                item.optString("title", "未知会话"), item.optLong("updated_at", 0L),
                (0 until events.length()).mapNotNull { events.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
            )
        }.sortedByDescending { it.updatedAt }.toList()
    }

    fun forget(title: String) {
        val root = readRoot(); root.remove(keyFor(title))
        sp.edit().putString("profiles", root.toString()).apply()
    }

    fun clear() = sp.edit().remove("profiles").apply()

    private fun readRoot(): JSONObject = runCatching {
        JSONObject(sp.getString("profiles", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun keyFor(title: String): String = Integer.toHexString(title.trim().lowercase().hashCode())

    companion object {
        private const val MAX_EVENTS = 12
        private const val MAX_EVENT_CHARS = 220
    }
}
