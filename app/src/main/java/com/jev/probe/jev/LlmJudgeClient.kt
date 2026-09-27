package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The judgment route, on any OpenAI-compatible `/chat/completions` endpoint.
 *
 * v1.5 replaced the Jev `state` + `questions` -> `answers` protocol with one
 * ordinary chat call: the 7 calibrated questions are rendered into a system
 * prompt ([JudgePrompts.renderSystemPrompt]) and the model is asked to answer
 * them as a single JSON object, which [parseAnalysis] turns back into the same
 * [Analysis] the UI has always rendered. No Jev host, key or model is involved.
 *
 * Reads judgeBaseUrl / judgeKey / judgeModel from [Prefs].
 */
class LlmJudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions in one call. Errors are returned inside
     * [Analysis.error], never thrown.
     *
     * @param onDelta streaming progress (see [HttpJson.postChatFull]); the panel
     *        uses it to show the thinking pass live. Null = collect silently.
     * @param ctx knowledge context (contact, history, notes, session) — null or
     *        empty means the prompt carries only the on-screen conversation.
     */
    fun judge(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        onDelta: ((kind: String, chunk: String) -> Unit)? = null
    ): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val out = chat(
                JudgePrompts.renderSystemPrompt(),
                userMessage(snapshot, relationship, ctx),
                temperature = 0.0,
                onDelta = onDelta
            )
            val content = out.content
            val o = extractJsonObject(content)
                ?: throw IllegalStateException("模型没有返回 JSON：" + content.take(80))
            Analysis(
                trueIntent = parseChoice(o.optJSONObject("true_intent")),
                dangerLevel = parseScore(o.optJSONObject("danger_level")),
                sheNeeds = parseChoice(o.optJSONObject("she_needs")),
                shouldReplyNow = parseUnit(o, "should_reply_now"),
                bestAction = parseChoice(o.optJSONObject("best_action")),
                tensionResolved = parseUnit(o, "tension_resolved"),
                literalQuestion = parseUnit(o, "literal_question"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start,
                reasoning = out.reasoning?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** The shared user message: background + history + session + on-screen chat. */
    private fun userMessage(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?
    ): String = JudgePrompts.renderUserMessage(
        snapshot = snapshot,
        relationship = relationship,
        background = ctx?.background(relationship) ?: "",
        history = ctx?.history ?: emptyList(),
        window = prefs.analysisWindow
    )

    /**
     * Free-form "问 AI": answer the user's own question about this conversation.
     * Unlike [judge] this is generative, so it is capped by the user's output
     * limit and is allowed to be as long as they asked for.
     *
     * @return the answer plus the thinking pass; the answer starts with
     *         "问 AI 失败" on error — the panel shows whatever comes back, so a
     *         failure has to be text.
     */
    fun ask(
        question: String,
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        onDelta: ((kind: String, chunk: String) -> Unit)? = null
    ): ChatAiClient.AskResult = try {
        val out = chat(
            ASK_SYSTEM,
            userMessage(snapshot, relationship, ctx) + "\n\n# 我的问题\n" + question.trim(),
            temperature = 0.3,
            maxTokens = prefs.maxOutputTokens,
            onDelta = onDelta
        )
        ChatAiClient.AskResult(out.content.trim(), out.reasoning?.takeIf { it.isNotBlank() })
    } catch (e: Exception) {
        Log.w(TAG, "ask failed: ${e.message}")
        ChatAiClient.AskResult(
            "问 AI 失败：" + (e.message ?: e.javaClass.simpleName), null)
    }

    /** One chat-completions round trip; returns the full streamed message. */
    private fun chat(
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int? = null,
        onDelta: ((kind: String, chunk: String) -> Unit)? = null
    ): HttpJson.ChatStream {
        val url = prefs.judgeEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("messages", messages)
            .put("temperature", temperature)
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        // Explicitly request the thinking pass on hosts that default it off
        // (Bailian; DeepSeek official selects it via the model name instead).
        HttpJson.applyJudgeRouteOptions(body, url)
        // Streamed: with thinking on, the silent wait can exceed any sane read
        // timeout; streaming turns it into continuous chunk traffic.
        return HttpJson.postChatFull(url, prefs.judgeKey, body, Route.JUDGE,
            proxy = prefs.proxy(), onDelta = onDelta)
    }

    /**
     * The model's answer, as JSON. Tolerates the two shapes models actually
     * produce: a fenced ```json block, and prose wrapped around the object.
     */
    private fun extractJsonObject(raw: String): JSONObject? {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val fence = t.lastIndexOf("```")
            if (fence >= 0) t = t.substring(0, fence).trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(t.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val name = o.optString("choice").ifBlank { o.optString("answer") }
        if (name.isBlank()) return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(name, confidence(o), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val level = when {
            o.has("score") -> o.optDouble("score", 0.0)
            else -> o.optDouble("choice", 0.0)
        }
        return Score(level, confidence(o), MAX_DANGER_LEVEL)
    }

    /** A 0..1 answer; accepts `true`/`false` because models like to use them. */
    private fun parseUnit(o: JSONObject, key: String): Double? {
        if (!o.has(key)) return null
        return when (val v = o.opt(key)) {
            is Boolean -> if (v) 1.0 else 0.0
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
                ?: when (v.trim().lowercase()) {
                    "true", "yes", "是" -> 1.0
                    "false", "no", "否" -> 0.0
                    else -> null
                }
            else -> null
        }
    }

    /** `confidence` is optional; a missing one must not read as "0% sure". */
    private fun confidence(o: JSONObject): Double {
        val c = o.optDouble("confidence", Double.NaN)
        return if (c.isNaN()) DEFAULT_CONFIDENCE else c.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** "问 AI" persona: a blunt, practical advisor that reads the thread first. */
        private const val ASK_SYSTEM =
            "你是中文聊天参谋。你会先读到我和对方的聊天记录（可能还带背景和知识库），" +
                "然后回答我关于这段聊天的问题。\n" +
                "要求：先看聊天记录再下结论；说人话、给可执行的建议；" +
                "不确定的地方直说不确定，不要编造聊天里没有的事实；不要复述我的问题。"

        /** The 10 danger levels in JudgePrompts are scored 0..9. */
        private const val MAX_DANGER_LEVEL = 9

        /** Used when the model omits `confidence` (it is not part of its task). */
        private const val DEFAULT_CONFIDENCE = 0.5
    }
}
