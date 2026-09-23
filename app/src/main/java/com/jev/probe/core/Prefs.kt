package com.jev.probe.core

import android.content.Context

/**
 * App-private config store. Holds Jev and custom LLM credentials, model choices, the
 * relationship description used in Jev's state, and the conversation whitelist.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Hardening to EncryptedSharedPreferences is
 * a follow-up; on the user's own device app-private storage is the MVP bar.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("jev_assistant", Context.MODE_PRIVATE)

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    /** Jev always uses TypeSafe's official System One endpoint. */
    var jevKey: String
        get() = (sp.getString("jev_key", "") ?: "").ifBlank {
            sp.getString("decision_key", "")?.takeIf { it.isNotBlank() } ?: ""
        }
        set(v) = sp.edit().putString("jev_key", v.trim()).apply()

    /** Custom text-generation provider: openai, gemini, or claude. */
    var llmProtocol: String
        get() = sp.getString("llm_protocol", "openai") ?: "openai"
        set(v) = sp.edit().putString("llm_protocol", v).apply()

    var llmProviderName: String
        get() = sp.getString("llm_provider_name", "自定义供应商") ?: "自定义供应商"
        set(v) = sp.edit().putString("llm_provider_name", v.trim()).apply()

    var chatUrl: String
        get() = sp.getString("chat_url", "") ?: ""
        set(v) = sp.edit().putString("chat_url", v.trim()).apply()

    var chatKey: String
        get() = sp.getString("chat_key", "") ?: ""
        set(v) = sp.edit().putString("chat_key", v.trim()).apply()

    var availableModels: Set<String>
        get() = sp.getStringSet("available_models", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("available_models", v).apply()

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Optional self-reported MBTI types. They are communication hints, never diagnoses. */
    var myMbti: String
        get() = sp.getString(K_MY_MBTI, "") ?: ""
        set(v) = sp.edit().putString(K_MY_MBTI, normalizeMbti(v)).apply()

    var otherMbti: String
        get() = sp.getString(K_OTHER_MBTI, "") ?: ""
        set(v) = sp.edit().putString(K_OTHER_MBTI, normalizeMbti(v)).apply()

    fun relationshipContext(base: String = relationship): String {
        val types = buildList {
            if (myMbti.isNotBlank()) add("用户自述MBTI=$myMbti")
            if (otherMbti.isNotBlank()) add("对方自述/已知MBTI=$otherMbti")
        }
        if (types.isEmpty()) return base
        return base + "；" + types.joinToString("；") +
            "。MBTI只用于微调表达风格、信息密度和沟通偏好，不用于推断事实、意图或给人贴标签；聊天原文优先。"
    }

    private fun normalizeMbti(value: String): String = value.trim().uppercase()
        .takeIf { it in MBTI_TYPES } ?: ""

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** A configured text model is sufficient; Jev is an optional quality upgrade. */
    fun canAnalyze(): Boolean =
        chatKey.isNotBlank() && chatUrl.isNotBlank() && replyModel.isNotBlank()

    fun hasJev(): Boolean = jevKey.isNotBlank()

    companion object {
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_REL = "relationship"
        private const val K_MY_MBTI = "my_mbti"
        private const val K_OTHER_MBTI = "other_mbti"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        const val DEFAULT_REPLY_MODEL = ""
        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
        val MBTI_TYPES = setOf(
            "INTJ", "INTP", "ENTJ", "ENTP", "INFJ", "INFP", "ENFJ", "ENFP",
            "ISTJ", "ISFJ", "ESTJ", "ESFJ", "ISTP", "ISFP", "ESTP", "ESFP"
        )
    }
}
