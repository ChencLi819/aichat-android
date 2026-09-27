package com.jev.probe.core.kb

import com.jev.probe.core.Msg

/**
 * Per-conversation context buffer ("本会话上下文").
 *
 * One entry per conversation title — the same identity the rest of the app uses
 * for a conversation (whitelist matching, contact filing) — so every chat
 * window keeps its own context and clearing one never touches another. The user
 * starts recording when they open a chat with someone and clears it when they
 * are done with that chat; nothing is recorded unless they ask for it.
 *
 * Counting starts at the moment the user starts recording: whatever was already
 * on screen at that instant is deliberately NOT added, because it already goes
 * out with every analysis as the on-screen window. Only messages that arrive
 * from that point on accumulate here, which is what makes this buffer worth
 * having — it keeps the thread continuous after lines scroll off the screen.
 *
 * In memory only, on purpose: the buffer represents the currently open chat
 * session, so it dies with the service/process exactly like the chat window it
 * belongs to. Nothing is written to disk, and no conversation's buffer is ever
 * mixed into another's.
 *
 * All methods are synchronized: the capture service appends from its worker
 * pool while the overlay menu and settings read from the main thread.
 */
object ContextSessions {

    /** One open conversation, counting from [injectedAt] onward. */
    class Session(val title: String) {
        val injectedAt: Long = System.currentTimeMillis()
        /** Recorded lines, oldest first. */
        val entries = ArrayList<Msg>()
        /** Dedupe keys of every line already accounted for (recorded or dropped). */
        val seen = HashSet<String>()
    }

    private val sessions = LinkedHashMap<String, Session>()

    /**
     * Start (or restart) recording for one conversation.
     *
     * @param onScreen the messages visible at this instant. They are marked as
     *        already seen but not stored, so the count begins here rather than
     *        reaching back into what was on screen before the user chose to start.
     */
    @Synchronized
    fun inject(title: String, onScreen: List<Msg>): Session {
        val s = Session(title)
        onScreen.forEach { m -> dedupeKey(m)?.let { s.seen.add(it) } }
        sessions[title] = s
        return s
    }

    /** Drop one conversation's context. Returns true when there was one. */
    @Synchronized
    fun clear(title: String): Boolean = sessions.remove(title) != null

    /** Drop every conversation's context. Returns how many were dropped. */
    @Synchronized
    fun clearAll(): Int {
        val n = sessions.size
        sessions.clear()
        return n
    }

    @Synchronized
    fun isActive(title: String?): Boolean = title != null && sessions.containsKey(title)

    @Synchronized
    fun titles(): List<String> = sessions.keys.toList()

    /** One line per open conversation: title, recorded lines, start time. */
    @Synchronized
    fun summary(): List<Triple<String, Int, Long>> =
        sessions.values.map { Triple(it.title, it.entries.size, it.injectedAt) }

    /**
     * Record newly captured messages into this conversation's buffer. No-op when
     * the conversation is not being recorded, so a chat the user never opted
     * into is never stored.
     *
     * Each capture re-reads the visible window, so a batch normally overlaps the
     * buffer's tail. The anchor rule: find the LAST line of the batch that is
     * already accounted for, and record only what comes strictly after it.
     * Genuinely new messages always arrive below the newest seen line; anything
     * at or above the anchor is the same window re-read — or OLDER lines the
     * user scrolled into view — and those must never enter "本次聊天"
     * (they were said before recording started; the reported "分析的是旧
     * 会话的上下文"). A batch that shares nothing with the buffer at all is
     * also history: mark it seen so it can never be recorded later.
     */
    @Synchronized
    fun append(title: String?, messages: List<Msg>, maxChars: Int) {
        val s = title?.let { sessions[it] } ?: return
        if (s.seen.isEmpty()) {
            // First capture of a session that started with an empty window:
            // there is no anchor to find, so this batch is the baseline.
            messages.forEach { m ->
                val key = dedupeKey(m) ?: return@forEach
                if (s.seen.add(key)) s.entries.add(Msg(m.side, m.text.trim()))
            }
            trim(s, maxChars)
            return
        }
        var anchor = -1
        for (i in messages.indices) {
            val key = dedupeKey(messages[i]) ?: continue
            if (key in s.seen) anchor = i
        }
        for (i in (anchor + 1) until messages.size) {
            val m = messages[i]
            val key = dedupeKey(m) ?: continue
            if (s.seen.add(key)) s.entries.add(Msg(m.side, m.text.trim()))
        }
        for (i in 0..anchor) dedupeKey(messages[i])?.let { s.seen.add(it) }
        if (anchor < 0) messages.forEach { m -> dedupeKey(m)?.let { s.seen.add(it) } }
        trim(s, maxChars)
    }

    /**
     * The text to inject into one analysis: everything recorded since the user
     * started, oldest first, trimmed to stay within [maxChars]. Null while
     * nothing has been recorded yet.
     */
    @Synchronized
    fun render(title: String?, maxChars: Int): String? {
        val s = title?.let { sessions[it] } ?: return null
        trim(s, maxChars)
        if (s.entries.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("以下是我和对方本次聊天的记录（从我开始记录那一刻起，越靠下越新）：\n")
        s.entries.forEach {
            sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
        }
        return sb.toString().trim()
    }

    /** Drop the oldest recorded lines until the buffer fits [maxChars]. */
    private fun trim(s: Session, maxChars: Int) {
        val cap = maxChars.coerceAtLeast(MIN_CHARS)
        var used = s.entries.sumOf { it.text.length + 3 }
        while (used > cap && s.entries.isNotEmpty()) {
            val dropped = s.entries.removeAt(0)
            used -= dropped.text.length + 3
        }
    }

    /** Identity of one line for dedupe; null for blank text. */
    private fun dedupeKey(m: Msg): String? {
        val text = m.text.trim()
        return if (text.isEmpty()) null else m.side + "\u0000" + text
    }

    /** A floor so a tiny configured budget cannot silently disable recording. */
    private const val MIN_CHARS = 200
}
