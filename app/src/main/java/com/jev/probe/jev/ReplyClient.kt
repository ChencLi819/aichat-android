package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 3 candidate replies in Chinese, each with the model's own score for
     * how well it fits.
     *
     * v1.5 folds the old separate ranking call into this one. Ranking used to be
     * a third round trip (judge, draft, then rank) that cost a full request just
     * to order three lines the drafting model had written a moment earlier — the
     * biggest single latency win available. The trade-off is that the ordering is
     * now the drafting model's own judgement rather than the judge model's; the
     * candidates themselves are unchanged.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<RankedReply> {
        val convo = snapshot.messages.takeLast(prefs.analysisWindow).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 个对象，" +
            "每个对象形如 {\"text\":\"候选回复\",\"score\":0.0}。" +
            "text 是回复正文，不超过 40 字，口语、自然、像真人在聊天软件里发消息；" +
            "score 是 0~1，表示这条作为下一条消息的合适程度，三条要有区分。" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "不要解释，不要输出 JSON 以外的任何内容。"
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复及其 score。"
        return parseThree(chat(sys, user, temperature = 0.8))
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", prefs.maxOutputTokens)
        // Streamed (see [HttpJson.postChat]) and with the thinking pass skipped
        // on this route (see applyReplyRouteOptions): three short casual lines
        // come back in ~1-2s instead of ~15s.
        HttpJson.applyReplyRouteOptions(body, url)
        return HttpJson.postChat(url, prefs.effectiveReplyKey(), body, Route.REPLY, proxy = prefs.proxy())
    }

    /**
     * The model's array → 3 scored candidates, best first.
     *
     * Tolerant on purpose: models return `{"text","score"}` objects as asked, but
     * also plain strings, and sometimes wrap the array in prose. A plain string
     * gets a neutral score, so the order then falls back to the order written —
     * which is why the prompt asks for the best first as well as scoring it.
     */
    private fun parseThree(content: String): List<RankedReply> {
        val out = ArrayList<RankedReply>()
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching {
                val arr = JSONArray(content.substring(start, end + 1))
                for (i in 0 until arr.length()) {
                    when (val el = arr.opt(i)) {
                        is JSONObject -> {
                            val t = el.optString("text").trim()
                            if (t.isNotEmpty()) out.add(RankedReply(t, el.optDouble("score", 0.0)))
                        }
                        is String -> if (el.isNotBlank()) out.add(RankedReply(el.trim(), 0.0))
                    }
                }
            }
        }
        if (out.isEmpty()) {
            // Fallback: one reply per line, numbered or bulleted.
            content.split("\n")
                .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
                .filter { it.isNotBlank() }
                .take(3)
                .forEach { out.add(RankedReply(it, 0.0)) }
        }
        while (out.size < 3) out.add(RankedReply("（稍等，我看下）", 0.0))
        // Stable, so equal scores keep the order the model wrote them in.
        return out.sortedByDescending { it.prob }.take(3)
    }
}
