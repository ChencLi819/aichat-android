package com.jev.probe.core

import android.graphics.Rect

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(val side: String, val text: String)

/**
 * A bubble the node tree can locate but not read (Feishu draws its message text
 * itself). [rect] is in screen coordinates; [side] is what the tree could infer
 * around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * Capture chrome that is NOT a message: typing indicators, read receipts,
 * timestamps, recall notices, the input-box placeholder. These flicker while
 * the user watches the screen, and counting them as content made an
 * already-analysed snapshot look new — the reported "生成候选回复后经常
 * 自动重新生成". Filtered out of [ChatSnapshot.signature] / [ChatSnapshot.latestFrom],
 * but the lines still go to the model (harmless there).
 */
private val CHROME_CONTAINS = Regex("正在输入|撤回了一条消息|撤回了一条|重新编辑")
private val CHROME_EXACT = Regex(
    "^已读$|^未读$|^发送$|^按住.*说话$|^\\d{1,2}[:：]\\d{2}$|^(昨天|今天|前天|星期[一二三四五六日天])$"
)

internal fun isChromeLine(text: String): Boolean {
    val s = text.trim()
    if (s.isEmpty()) return true
    return CHROME_CONTAINS.containsMatchIn(s) || CHROME_EXACT.matches(s)
}

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue, and the one case
 * where [bubbleRects] may be populated.
 *
 * [note] is a caveat about how this snapshot was produced, shown verbatim in
 * the analysis panel (OCR captures cannot tell who said what).
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null
) {
    /** [messages] minus the capture chrome — what the conversation really is. */
    val real: List<Msg> by lazy { messages.filterNot { isChromeLine(it.text) } }

    val latestFrom: String? get() = real.lastOrNull()?.side

    /** Identity of the newest incoming message: "is there something new to answer". */
    fun lastIncoming(): String? = real.lastOrNull { it.side == "other" }?.text

    /** A stable signature of the last few REAL messages, to detect real changes. */
    fun signature(): String =
        real.takeLast(6).joinToString("|") { "${it.side}:${it.text}" }
}

/** The model's judgment result for one snapshot, plus the ranked candidate replies.
 *
 * [reasoning] is the model's thinking pass when the route streams with thinking
 * on — shown collapsibly on the panel so a long wait is inspectable. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null,
    val reasoning: String? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class RankedReply(val text: String, val prob: Double)
