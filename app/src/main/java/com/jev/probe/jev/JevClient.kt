package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.AppLog
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Uses the official Jev endpoint for structured judgments and a user-configured
 * OpenAI-compatible, Gemini, or Claude provider to draft candidate replies.
 *
 * The key is passed in per call; it is never logged.
 */
class JevClient(
    private val decisionKey: String,
    private val replyModel: String,
    private val chatKey: String,
    private val chatUrl: String,
    private val chatProtocol: String = "openai",
    proxyUrl: String = "",
    proxyUsername: String = "",
    proxyPassword: String = "",
    userAgent: String = ""
) {

    private val http = buildHttp(proxyUrl, proxyUsername, proxyPassword, userAgent)

    private data class Coaching(
        val emotion: String? = null,
        val facts: List<String> = emptyList(),
        val inference: String? = null,
        val unknown: String? = null,
        val goal: String? = null,
        val nextStep: String? = null,
        val stopCondition: String? = null,
        val positive: String? = null,
        val ambiguous: String? = null,
        val negative: String? = null,
        val safetyLevel: String = "normal",
        val safetySignals: List<String> = emptyList(),
        val safetyAdvice: String? = null,
        val reciprocity: String? = null,
        val conflictType: String? = null
    )

    private var latestCoaching = Coaching()

    /** The 7 judgment questions only (fast, ~1s). No candidate generation. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        if (decisionKey.isBlank()) return judgeWithLlm(snapshot, relationship)
        val start = System.currentTimeMillis()
        try {
            val body = JSONObject()
                .put("model", decisionModel)
                .put("state", JevQuestions.buildState(snapshot, relationship))
                .put("questions", JevQuestions.judge())
            val answers = postJson(decisionsUrl, body, decisionKey).optJSONObject("answers") ?: JSONObject()
            return Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            AppLog.e("Jev判断", "请求失败", e)
            return Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e))
        }
    }

    /** Draft 3 candidate replies (generative model) then Jev-rank them. Slower. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        judgment: Analysis
    ): List<RankedReply> {
        if (decisionKey.isBlank()) return judgment.rankedReplies
        val candidates = generateCandidates(snapshot, relationship, judgment)
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val body = JSONObject()
            .put("model", decisionModel)
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val answers = postJson(decisionsUrl, body, decisionKey).optJSONObject("answers") ?: JSONObject()
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    fun enrich(judgment: Analysis, ranked: List<RankedReply>): Analysis = judgment.copy(
        rankedReplies = ranked,
        emotionSupport = latestCoaching.emotion,
        facts = latestCoaching.facts,
        inference = latestCoaching.inference,
        unknown = latestCoaching.unknown,
        roundGoal = latestCoaching.goal,
        nextStep = latestCoaching.nextStep,
        stopCondition = latestCoaching.stopCondition,
        positiveBranch = latestCoaching.positive,
        ambiguousBranch = latestCoaching.ambiguous,
        negativeBranch = latestCoaching.negative,
        safetyLevel = latestCoaching.safetyLevel,
        safetySignals = latestCoaching.safetySignals,
        safetyAdvice = latestCoaching.safetyAdvice,
        reciprocityState = latestCoaching.reciprocity,
        conflictType = latestCoaching.conflictType
    )

    /** Connectivity test that fails when either Jev or the text model fails. */
    fun analyzeStrict(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        return try {
            enrich(a, draftAndRank(snapshot, relationship, a))
        } catch (e: Exception) {
            a.copy(error = readableError(e))
        }
    }

    /** Full fallback when no Jev key is configured: one LLM call returns judgments and replies. */
    private fun judgeWithLlm(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val convo = snapshot.messages.takeLast(10).joinToString("\n") {
                (if (it.side == "me") "我" else "对方") + "：" + it.text
            }
            val system = "你是谨慎、清醒的中文关系沟通助手。先识别情绪和真实需求，严格区分事实、合理推测和未知；" +
                "优先考虑互惠、可靠性、边界与长期信任；不得编造事实，不得设计施压、纠缠或操控话术。" +
                "如果关系信息中提供MBTI，只将其作为表达风格和沟通偏好的弱参考，不能据此推断事实、情绪或真实意图；" +
                "完成结构化判断并生成回复，只输出一个JSON对象，不要Markdown。"
            val user = "关系：" + relationship + "\n\n最近对话：\n" + convo +
                "\n\n返回字段：true_intent只能是confirm_you_care、vent_anger、request_action、" +
                "seek_explanation、casual_chat、close_topic之一；danger_level为0到9数字；" +
                "need只能是apology、action、explanation、care、nothing之一；" +
                "best_action只能是check_history、apologize、give_commitment、explain、acknowledge、" +
                "say_less、make_plan之一；should_reply_now、tension_resolved、literal_question为布尔值；" +
                "emotion为对用户的1句情绪承接；facts为最多3条原文可确认事实；inference为1条暂定解释；" +
                "unknown为1条关键未知；goal只能是承接、降压、调侃、轻推、约见、澄清、收线之一；" +
                "next_step为现在能做的小动作；stop_condition为停止或改策略的条件；" +
                "safety_level只能是normal、caution、danger；只有原文出现暴力、胁迫、跟踪、限制自由、" +
                "性强迫、隐私威胁、经济控制或自伤伤人要挟等明确信号时才能提高；safety_signals列出原文证据；" +
                "safety_advice给低风险行动，danger时禁止推进、挽回或单独见面；" +
                "reciprocity只能是mutual、insufficient、imbalanced、rejected、danger之一；" +
                "conflict_type只能是none、misunderstanding、solvable、persistent_difference、core_incompatibility、power_safety之一；" +
                "branches包含positive、ambiguous、negative三种后续动作；replies为恰好3条可直接发送、策略不同的中文回复。格式：" +
                "{\"true_intent\":\"casual_chat\",\"danger_level\":0,\"need\":\"nothing\"," +
                "\"best_action\":\"acknowledge\",\"should_reply_now\":true," +
                "\"tension_resolved\":true,\"literal_question\":true,\"emotion\":\"...\",\"facts\":[\"...\"]," +
                "\"inference\":\"...\",\"unknown\":\"...\",\"goal\":\"承接\",\"next_step\":\"...\"," +
                "\"stop_condition\":\"...\",\"safety_level\":\"normal\",\"safety_signals\":[],\"safety_advice\":\"...\"," +
                "\"reciprocity\":\"insufficient\",\"conflict_type\":\"none\"," +
                "\"branches\":{\"positive\":\"...\",\"ambiguous\":\"...\",\"negative\":\"...\"}," +
                "\"replies\":[\"...\",\"...\",\"...\"]}"
            val root = parseObject(callChat(system, user))
            val replies = parseReplyArray(root.optJSONArray("replies"))
            latestCoaching = parseCoaching(root)
            AppLog.i("分析", "未配置Jev，已使用大语言模型独立分析")
            Analysis(
                trueIntent = fallbackChoice(root.optString("true_intent", "casual_chat")),
                dangerLevel = Score(root.optDouble("danger_level", 0.0).coerceIn(0.0, 9.0), 0.65, 9),
                sheNeeds = fallbackChoice(root.optString("need", "nothing")),
                shouldReplyNow = if (root.optBoolean("should_reply_now", true)) 1.0 else 0.0,
                bestAction = fallbackChoice(root.optString("best_action", "acknowledge")),
                tensionResolved = if (root.optBoolean("tension_resolved", false)) 1.0 else 0.0,
                literalQuestion = if (root.optBoolean("literal_question", false)) 1.0 else 0.0,
                rankedReplies = replies.mapIndexed { i, text ->
                    RankedReply(text, listOf(0.60, 0.30, 0.10).getOrElse(i) { 0.0 })
                },
                latencyMs = System.currentTimeMillis() - start,
                emotionSupport = latestCoaching.emotion,
                facts = latestCoaching.facts,
                inference = latestCoaching.inference,
                unknown = latestCoaching.unknown,
                roundGoal = latestCoaching.goal,
                nextStep = latestCoaching.nextStep,
                stopCondition = latestCoaching.stopCondition,
                positiveBranch = latestCoaching.positive,
                ambiguousBranch = latestCoaching.ambiguous,
                negativeBranch = latestCoaching.negative,
                safetyLevel = latestCoaching.safetyLevel,
                safetySignals = latestCoaching.safetySignals,
                safetyAdvice = latestCoaching.safetyAdvice,
                reciprocityState = latestCoaching.reciprocity,
                conflictType = latestCoaching.conflictType
            )
        } catch (e: Exception) {
            AppLog.e("大模型独立分析", "请求或JSON解析失败", e)
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e))
        }
    }

    private fun fallbackChoice(value: String) = Choice(value, 0.65, mapOf(value to 0.65))

    private fun parseObject(content: String): JSONObject {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型未返回JSON对象" }
        return JSONObject(content.substring(start, end + 1))
    }

    private fun parseReplyArray(array: JSONArray?): List<String> {
        require(array != null) { "模型未返回候选回复" }
        val out = (0 until array.length()).map { array.optString(it).trim() }
            .filter { it.isNotBlank() }.take(3).toMutableList()
        while (out.size < 3) out.add("我先想一下，等会认真回你。")
        return out
    }

    /** Ask a generative model for exactly 3 varied candidate replies (Chinese). */
    private fun generateCandidates(
        snapshot: ChatSnapshot,
        relationship: String,
        judgment: Analysis
    ): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val jevContext = buildJevContext(judgment)
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\nJev结构化判断：\n$jevContext\n\n" +
            "请以聊天原文为事实边界，并参考Jev判断给出3条候选回复。Jev判断只是辅助，" +
            "若它与原文明显冲突，应以原文为准。"
        val root = parseObject(callChat(relationshipCoachPrompt, user))
        latestCoaching = parseCoaching(root)
        return parseReplyArray(root.optJSONArray("replies"))
    }

    private fun callChat(system: String, user: String): String {
        require(replyModel.isNotBlank()) { "请先选择一个回复模型" }
        require(chatUrl.isNotBlank()) { "请填写大语言模型请求地址" }
        require(chatKey.isNotBlank()) { "请填写大语言模型 Key" }
        return when (chatProtocol) {
            "gemini" -> {
                val url = generationUrl("gemini", chatUrl, replyModel)
                val body = JSONObject().put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", system + "\n\n" + user)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.8))
                val resp = postJson(url, body, chatKey, "gemini")
                resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
            }
            "claude" -> {
                val body = JSONObject().put("model", replyModel).put("max_tokens", 700)
                    .put("system", system).put("messages", JSONArray().put(JSONObject()
                        .put("role", "user").put("content", user)))
                val resp = postJson(generationUrl("claude", chatUrl, replyModel), body, chatKey, "claude")
                extractText(resp)
            }
            else -> {
                val url = generationUrl("openai", chatUrl, replyModel)
                val body = if (url.endsWith("/responses")) {
                    JSONObject().put("model", replyModel).put("instructions", system).put("input", user)
                } else {
                    val messages = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                    JSONObject().put("model", replyModel).put("messages", messages)
                        .put("temperature", 0.8).put("stream", false)
                }
                val resp = postJson(url, body, chatKey, "openai")
                extractText(resp)
            }
        }
    }

    /** Accept the base URL, /v1 URL, or a complete generation endpoint. */
    private fun generationUrl(protocol: String, configured: String, model: String): String {
        val url = configured.trim().trimEnd('/')
        return when (protocol) {
            "gemini" -> when {
                url.contains("{model}") -> url.replace("{model}", model)
                url.contains(":generateContent") -> url
                url.endsWith("/v1beta") || url.endsWith("/v1") -> "$url/models/$model:generateContent"
                URL(url).path.isNullOrBlank() || URL(url).path == "/" -> "$url/v1beta/models/$model:generateContent"
                else -> "$url/models/$model:generateContent"
            }
            "claude" -> when {
                url.endsWith("/messages") -> url
                url.endsWith("/v1") -> "$url/messages"
                URL(url).path.isNullOrBlank() || URL(url).path == "/" -> "$url/v1/messages"
                else -> url
            }
            else -> when {
                url.endsWith("/chat/completions") || url.endsWith("/responses") -> url
                url.endsWith("/v1") -> "$url/chat/completions"
                url.endsWith("/models") -> url.removeSuffix("/models") + "/chat/completions"
                URL(url).path.isNullOrBlank() || URL(url).path == "/" -> "$url/v1/chat/completions"
                else -> url
            }
        }
    }

    /** Read common OpenAI-compatible, Responses API, Claude, and proxy wrappers. */
    private fun extractText(root: JSONObject): String {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("data")?.let { data -> extractText(data).takeIf { it.isNotBlank() }?.let { return it } }
        root.optJSONArray("content")?.let { content ->
            for (i in 0 until content.length()) {
                val text = content.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        root.optJSONArray("output")?.let { output ->
            for (i in 0 until output.length()) {
                val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val text = content.optJSONObject(j)?.optString("text").orEmpty()
                    if (text.isNotBlank()) return text
                }
            }
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        choice?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        val content = choice?.optJSONObject("message")?.opt("content")
        if (content is String && content.isNotBlank()) return content
        if (content is JSONArray) {
            for (i in 0 until content.length()) {
                val text = content.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        throw RuntimeException("供应商返回成功，但未找到文本内容")
    }

    private fun buildJevContext(a: Analysis): String = listOf(
        "真实意图=${a.trueIntent?.choice ?: "unknown"}，置信度=${formatConfidence(a.trueIntent?.confidence)}",
        "关系风险=${a.dangerLevel?.score?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "unknown"}/${a.dangerLevel?.maxLevel ?: 9}",
        "当前需求=${a.sheNeeds?.choice ?: "unknown"}，置信度=${formatConfidence(a.sheNeeds?.confidence)}",
        "建议动作=${a.bestAction?.choice ?: "unknown"}，置信度=${formatConfidence(a.bestAction?.confidence)}",
        "需要实质回复=${formatNoul(a.shouldReplyNow)}",
        "紧张已缓解=${formatNoul(a.tensionResolved)}",
        "纯字面表达=${formatNoul(a.literalQuestion)}"
    ).joinToString("\n")

    private fun formatConfidence(value: Double?): String = value?.let {
        String.format(java.util.Locale.US, "%.2f", it)
    } ?: "unknown"

    private fun formatNoul(value: Double?): String = when {
        value == null || value.isNaN() -> "unknown"
        value >= 0.65 -> "yes"
        value <= 0.35 -> "no"
        else -> "uncertain"
    }

    /**
     * A compact, original relationship-coaching workflow inspired by evidence-aware
     * communication practice. It intentionally does not embed third-party documents.
     */
    private val relationshipCoachPrompt: String
        get() = "你是谨慎、清醒、站在用户一边的中文关系沟通助手。先接住用户情绪，再完成判断。" +
            "判断时必须：1.先识别对方情绪和真正需求；2.严格区分聊天能确认的事实、合理推测和未知，" +
            "禁止读心；3.优先考虑互惠、可靠性、边界、现实可行性和长期信任；" +
            "4.明确拒绝、不适或持续缺乏回应时，不设计施压、纠缠、贬低、试探或操控话术；" +
            "5.信息不足时选择澄清或简短承接，不假装记得、不虚构理由；" +
            "6.避免替用户做重大决定或过度承诺；7.若提供MBTI，只用于微调回复的直接程度、信息密度、" +
            "情绪承接和行动表达，不得把类型当事实、诊断或刻板标签，聊天原文始终优先。" +
            "先做安全筛查：只有聊天原文明示暴力、限制自由、跟踪、性强迫、隐私威胁、经济控制、" +
            "自伤或伤人要挟时，safety_level才为caution或danger，并在safety_signals引用简短证据。" +
            "danger时回复只能用于停止升级、确认安全或寻求支持，不得推进关系、挽回、挑衅或建议单独摊牌。" +
            "再提取：emotion一句情绪承接；facts最多3条原文事实；inference一条暂定推测；unknown一条关键未知；" +
            "goal只选承接、降压、调侃、轻推、约见、澄清、收线之一；next_step一个小动作；stop_condition停止条件；" +
            "reciprocity只选mutual、insufficient、imbalanced、rejected、danger，证据不足不得判失衡；" +
            "conflict_type只选none、misunderstanding、solvable、persistent_difference、core_incompatibility、power_safety；" +
            "branches给出positive、ambiguous、negative三种回应下各一个后续动作。" +
            "再生成含且仅含3条可以直接发送的中文回复：" +
            "第一条稳妥共情并承接核心需求；第二条在事实充分时给具体行动，否则礼貌澄清；" +
            "第三条简短自然并保留双方空间。三条策略必须不同，每条不超过50字，像真人聊天。" +
            "只输出JSON对象，字段为emotion、facts、inference、unknown、goal、next_step、stop_condition、" +
            "safety_level、safety_signals、safety_advice、reciprocity、conflict_type、branches、replies。" +
            "不要输出Markdown。"

    private fun parseCoaching(root: JSONObject): Coaching {
        fun value(name: String): String? = root.optString(name).trim().takeIf { it.isNotBlank() }
        val factArray = root.optJSONArray("facts")
        val facts = if (factArray == null) emptyList() else (0 until factArray.length())
            .map { factArray.optString(it).trim() }.filter { it.isNotBlank() }.take(3)
        val branches = root.optJSONObject("branches")
        val signalArray = root.optJSONArray("safety_signals")
        val signals = if (signalArray == null) emptyList() else (0 until signalArray.length())
            .map { signalArray.optString(it).trim() }.filter { it.isNotBlank() }.take(3)
        val safeLevel = root.optString("safety_level", "normal").takeIf { it in setOf("normal", "caution", "danger") } ?: "normal"
        return Coaching(
            emotion = value("emotion"), facts = facts, inference = value("inference"),
            unknown = value("unknown"), goal = value("goal"), nextStep = value("next_step"),
            stopCondition = value("stop_condition"),
            positive = branches?.optString("positive")?.trim()?.takeIf { it.isNotBlank() },
            ambiguous = branches?.optString("ambiguous")?.trim()?.takeIf { it.isNotBlank() },
            negative = branches?.optString("negative")?.trim()?.takeIf { it.isNotBlank() },
            safetyLevel = safeLevel, safetySignals = signals, safetyAdvice = value("safety_advice"),
            reciprocity = root.optString("reciprocity").takeIf { it in setOf("mutual", "insufficient", "imbalanced", "rejected", "danger") },
            conflictType = root.optString("conflict_type").takeIf { it in setOf("none", "misunderstanding", "solvable", "persistent_difference", "core_incompatibility", "power_safety") }
        )
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    /** POST JSON with one retry chain for 429/529 (exponential backoff). */
    private fun postJson(urlStr: String, body: JSONObject, key: String, protocol: String = "jev"): JSONObject {
        require(key.isNotBlank()) { "请填写对应接口的密钥" }
        require(URL(urlStr).let { it.protocol == "https" && !it.host.isNullOrBlank() && it.toURI().userInfo == null }) { "请求地址必须使用有效的 HTTPS URL" }
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            try {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                val request = Request.Builder().url(urlStr)
                    .apply {
                        when (protocol) {
                            "gemini" -> header("x-goog-api-key", key)
                            "claude" -> {
                                header("x-api-key", key)
                                header("anthropic-version", "2023-06-01")
                            }
                            else -> header("Authorization", "Bearer $key")
                        }
                    }
                    .post(bytes.toRequestBody(JSON_MEDIA_TYPE, 0, bytes.size))
                    .build()
                http.newCall(request).execute().use { response ->
                    val code = response.code
                    val text = response.body?.string().orEmpty()
                    if (code == 429 || code == 529) {
                        attempt++
                        Thread.sleep(500L * (1L shl attempt))
                        return@use
                    }
                    if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                    return JSONObject(text)
                }
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e // client error: no retry
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            }
        }
        throw lastErr ?: RuntimeException("request failed")
    }

    private fun readableError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("HTTP 401") -> "密钥无效或未设置（401）"
            m.contains("HTTP 4") -> "请求被拒：$m"
            m.contains("timed out") || m.contains("timeout") -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") || m.contains("Failed to connect") -> "无法连接网络"
            else -> "分析失败：$m"
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val decisionsUrl = "https://api.typesafe.ai/v1/systemone"
        private const val decisionModel = "jev-latest"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private fun buildHttp(
            proxyUrl: String = "",
            proxyUsername: String = "",
            proxyPassword: String = "",
            userAgent: String = ""
        ): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("Accept-Language", Locale.getDefault().toLanguageTag())
                        .apply {
                            if (original.header("User-Agent") == null) {
                                header("User-Agent", userAgent.ifBlank { "RikkaHub-Android/2.5.3" })
                            }
                        }.build()
                    chain.proceed(request)
                }
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val contentType = request.header("Content-Type")
                    if (contentType != null && contentType.contains(";") &&
                        contentType.substringBefore(";").trim().equals("application/json", true)) {
                        chain.proceed(request.newBuilder().header("Content-Type", "application/json").build())
                    } else chain.proceed(request)
                }

            parseProxy(proxyUrl)?.let { proxy ->
                builder.proxy(proxy)
                if (proxyUsername.isNotBlank()) {
                    builder.proxyAuthenticator { _, response ->
                        val credential = Credentials.basic(proxyUsername, proxyPassword)
                        if (response.request.header("Proxy-Authorization") == credential) null
                        else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                    }
                }
            }
            return builder.build()
        }

        private fun parseProxy(value: String): Proxy? {
            if (value.isBlank()) return null // OkHttp uses Android's system ProxySelector.
            val uri = URI(value.trim())
            require(uri.host != null && uri.port in 1..65535) { "代理格式应为 http://主机:端口 或 socks5://主机:端口" }
            val type = if (uri.scheme.equals("socks5", true) || uri.scheme.equals("socks", true))
                Proxy.Type.SOCKS else Proxy.Type.HTTP
            return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
        }

        /** Fetch model identifiers exposed by the configured provider. */
        fun fetchModels(
            protocol: String,
            key: String,
            requestUrl: String,
            proxyUrl: String = "",
            proxyUsername: String = "",
            proxyPassword: String = "",
            userAgent: String = ""
        ): List<String> {
            require(key.isNotBlank()) { "请先填写大语言模型 Key" }
            require(requestUrl.startsWith("https://")) { "请求地址必须使用 HTTPS" }
            val normalized = requestUrl.trim().trimEnd('/')
            val http = buildHttp(proxyUrl, proxyUsername, proxyPassword, userAgent)
            val listUrl = when (protocol) {
                "gemini" -> when {
                    normalized.contains("/models/") -> normalized.substringBefore("/models/") + "/models"
                    normalized.endsWith("/v1beta") || normalized.endsWith("/v1") -> "$normalized/models"
                    URL(normalized).path.isNullOrBlank() || URL(normalized).path == "/" -> "$normalized/v1beta/models"
                    else -> normalized.substringBefore(":generateContent").substringBeforeLast("/models", normalized) + "/models"
                }
                "claude" -> when {
                    normalized.endsWith("/v1") -> "$normalized/models"
                    normalized.contains("/v1/") -> normalized.substringBefore("/v1/") + "/v1/models"
                    URL(normalized).path.isNullOrBlank() || URL(normalized).path == "/" -> "$normalized/v1/models"
                    else -> normalized.removeSuffix("/messages") + "/models"
                }
                else -> when {
                    normalized.endsWith("/v1") -> "$normalized/models"
                    normalized.endsWith("/chat/completions") -> normalized.removeSuffix("/chat/completions") + "/models"
                    normalized.endsWith("/responses") -> normalized.removeSuffix("/responses") + "/models"
                    URL(normalized).path.isNullOrBlank() || URL(normalized).path == "/" -> "$normalized/v1/models"
                    else -> normalized.removeSuffix("/models") + "/models"
                }
            }
            var lastError: Exception? = null
            AppLog.i("模型拉取", "请求 ${URL(listUrl).host}${URL(listUrl).path}，IPv4 优先 + HTTP/1.1")
            repeat(5) { attempt ->
                try {
                    val request = Request.Builder().url(listUrl)
                        .apply {
                            when (protocol) {
                                "gemini" -> header("x-goog-api-key", key)
                                "claude" -> {
                                    header("x-api-key", key)
                                    header("anthropic-version", "2023-06-01")
                                }
                                else -> header("Authorization", "Bearer $key")
                            }
                        }
                        .get().build()
                    http.newCall(request).execute().use { response ->
                        val code = response.code
                        val text = response.body?.string().orEmpty()
                        AppLog.i("模型拉取", "第 ${attempt + 1} 次返回 HTTP $code")
                        if (!response.isSuccessful) {
                            val error = RuntimeException("HTTP $code: ${text.take(160)}")
                            if (code in 400..499 && code != 408 && code != 429) throw error
                            lastError = error
                        } else {
                            val root = JSONObject(text)
                            val arr = root.optJSONArray(if (protocol == "gemini") "models" else "data") ?: JSONArray()
                            return (0 until arr.length()).mapNotNull { i ->
                                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                                if (protocol == "gemini") {
                                    val methods = item.optJSONArray("supportedGenerationMethods")
                                    if (methods != null && (0 until methods.length()).none { methods.optString(it) == "generateContent" })
                                        return@mapNotNull null
                                }
                                val raw = item.optString(if (protocol == "gemini") "name" else "id")
                                raw.removePrefix("models/").takeIf { it.isNotBlank() }
                            }
                            .distinct().sorted()
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (e.message?.startsWith("HTTP 4") == true &&
                        e.message?.startsWith("HTTP 408") != true &&
                        e.message?.startsWith("HTTP 429") != true) throw e
                }
                if (attempt < 4) Thread.sleep(1000L * (1L shl attempt))
            }
            val detail = lastError?.message ?: lastError?.javaClass?.simpleName ?: "未知网络错误"
            throw RuntimeException("连接连续重试 5 次仍失败：$detail")
        }
    }
}
