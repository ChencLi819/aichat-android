package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the judgment and reply clients so callers keep one entry
 * point. Both routes are plain OpenAI-compatible chat calls — nothing here
 * talks to a Jev endpoint.
 *
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class ChatAiClient(prefs: Prefs) {

    private val judgeClient = LlmJudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        onDelta: ((kind: String, chunk: String) -> Unit)? = null
    ): Analysis = judgeClient.judge(snapshot, relationship, ctx, onDelta)

    /**
     * 3 candidates, best first.
     *
     * The drafting call now scores its own candidates, so this is ONE request
     * where it used to be two (draft, then a separate ranking call). See
     * [ReplyClient.draft].
     */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> = replyClient.draft(snapshot, relationship, ctx)

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    /** Free-form "问 AI" answer plus its thinking pass (null when the model
     *  did not emit one). Never throws; a failed ask answers in [answer]. */
    class AskResult(val answer: String, val reasoning: String?)

    /** Free-form "问 AI" about this conversation. Never throws; see [LlmJudgeClient.ask]. */
    fun ask(
        question: String,
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        onDelta: ((kind: String, chunk: String) -> Unit)? = null
    ): AskResult = judgeClient.ask(question, snapshot, relationship, ctx, onDelta)
}
