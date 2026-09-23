package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uses the official Jev endpoint for structured judgments and a user-configured
 * OpenAI-compatible, Gemini, or Claude provider to draft candidate replies.
 *
 * The key is passed in per call; it is never logged.
 */
class JevClient(
    private val decisionKey: String,
    private val replyModel: String,
    private val decisionsUrl: String = "https://api.typesafe.ai/v1/systemone",
    private val decisionModel: String = "jev-latest",
    private val chatKey: String,
    private val chatUrl: String,
    private val chatProtocol: String = "openai"
) {

    /** The 7 judgment questions only (fast, ~1s). No candidate generation. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
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
            return Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e))
        }
    }

    /** Draft 3 candidate replies (generative model) then Jev-rank them. Slower. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = generateCandidates(snapshot, relationship)
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val body = JSONObject()
            .put("model", decisionModel)
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val answers = postJson(decisionsUrl, body, decisionKey).optJSONObject("answers") ?: JSONObject()
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /** Convenience for the settings connectivity test: judge + replies, sequential. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    /** Connectivity test that fails when either Jev or the text model fails. */
    fun analyzeStrict(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        return try {
            a.copy(rankedReplies = draftAndRank(snapshot, relationship))
        } catch (e: Exception) {
            a.copy(error = readableError(e))
        }
    }

    /** Ask a generative model for exactly 3 varied candidate replies (Chinese). */
    private fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        require(replyModel.isNotBlank()) { "请先选择一个回复模型" }
        require(chatUrl.isNotBlank()) { "请填写大语言模型请求地址" }
        val content = when (chatProtocol) {
            "gemini" -> {
                val url = chatUrl.replace("{model}", replyModel)
                val body = JSONObject().put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "$sys\n\n$user")))))
                    .put("generationConfig", JSONObject().put("temperature", 0.8))
                val resp = postJson(url, body, chatKey, "gemini")
                resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
            }
            "claude" -> {
                val body = JSONObject().put("model", replyModel).put("max_tokens", 500)
                    .put("system", sys).put("messages", JSONArray().put(JSONObject()
                        .put("role", "user").put("content", user)))
                val resp = postJson(chatUrl, body, chatKey, "claude")
                resp.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: ""
            }
            else -> {
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
                val body = JSONObject().put("model", replyModel).put("messages", messages)
                    .put("temperature", 0.8)
                val resp = postJson(chatUrl, body, chatKey, "openai")
                resp.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: ""
            }
        }
        return parseThree(content)
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
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
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    when (protocol) {
                        "gemini" -> setRequestProperty("x-goog-api-key", key)
                        "claude" -> {
                            setRequestProperty("x-api-key", key)
                            setRequestProperty("anthropic-version", "2023-06-01")
                        }
                        else -> setRequestProperty("Authorization", "Bearer $key")
                    }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    attempt++
                    Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e // client error: no retry
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
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

        /** Fetch model identifiers exposed by the configured provider. */
        fun fetchModels(protocol: String, key: String, requestUrl: String): List<String> {
            require(key.isNotBlank()) { "请先填写大语言模型 Key" }
            require(requestUrl.startsWith("https://")) { "请求地址必须使用 HTTPS" }
            val listUrl = when (protocol) {
                "gemini" -> requestUrl.substringBefore("/models/").trimEnd('/') + "/models"
                "claude" -> requestUrl.substringBefore("/v1/") + "/v1/models"
                else -> requestUrl.substringBefore("/chat/completions").trimEnd('/') + "/models"
            }
            val conn = (URL(listUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 25000
                when (protocol) {
                    "gemini" -> setRequestProperty("x-goog-api-key", key)
                    "claude" -> {
                        setRequestProperty("x-api-key", key)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    else -> setRequestProperty("Authorization", "Bearer $key")
                }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
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
                    raw?.removePrefix("models/")?.takeIf { it.isNotBlank() }
                }.distinct().sorted()
            } finally { conn.disconnect() }
        }
    }
}
