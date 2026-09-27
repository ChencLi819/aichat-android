package com.jev.probe.overlay

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * A deliberately small markdown → Spannable renderer for the panel.
 *
 * The model answers in markdown (bold, lists, code, headers); the overlay is a
 * plain TextView in a system window, so a WebView is not an option here. This
 * covers what chat-advice answers actually use and nothing more:
 *
 * - `#`/`##`/`###` headers → bold, slightly larger
 * - `- ` / `* ` / `• ` bullets → "• "
 * - `1.` numbered lists → kept as-is
 * - `**bold**` → bold
 * - `` `code` `` and ``` fenced blocks → monospace, tinted background
 * - `>` quote lines → grey italic with a left indent feel
 *
 * Anything unrecognized passes through untouched, so a malformed answer is
 * still readable.
 */
internal object MdText {

    fun toSpannable(raw: String): CharSequence {
        val out = SpannableStringBuilder()
        var inFence = false
        val lines = raw.replace("\r\n", "\n").split('\n')
        for ((index, line) in lines.withIndex()) {
            if (index > 0) out.append('\n')
            val trimmed = line.trim()
            // Fenced code block: render the inner lines verbatim, monospace.
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                appendStyled(out, line, codeSpans(0, line.length))
                continue
            }
            when {
                trimmed.isEmpty() -> { /* blank separator line */ }
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(3)
                    val text = trimmed.dropWhile { it == '#' }.trim()
                    val start = out.length
                    appendStyled(out, text, inlineSpans(text))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1f + level * 0.1f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmed.startsWith(">") -> {
                    val text = trimmed.dropWhile { it == '>' || it == ' ' }
                    val start = out.length
                    out.append("▎")
                    appendStyled(out, text, inlineSpans(text))
                    out.setSpan(ForegroundColorSpan(0xFF6B7280.toInt()), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    val text = trimmed.substring(2).trim()
                    out.append("• ")
                    appendStyled(out, text, inlineSpans(text))
                }
                else -> appendStyled(out, line, inlineSpans(line))
            }
        }
        return out
    }

    /** Append [text] then shift every span in [spans] (built against 0) into place. */
    private fun appendStyled(out: SpannableStringBuilder, text: String, spans: List<SpanJob>) {
        val start = out.length
        out.append(text)
        spans.forEach { s ->
            val st = (start + s.start).coerceAtMost(out.length)
            val en = (start + s.end).coerceAtMost(out.length)
            if (en > st) out.setSpan(s.what, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** `**bold**` and `` `code` `` within one line. */
    private fun inlineSpans(text: String): List<SpanJob> {
        val spans = ArrayList<SpanJob>()
        val bold = Regex("\\*\\*(.+?)\\*\\*")
        for (m in bold.findAll(text)) {
            spans.add(SpanJob(m.range.first, m.range.last + 1, StyleSpan(Typeface.BOLD)))
        }
        val code = Regex("`([^`]+)`")
        for (m in code.findAll(text)) {
            spans.add(SpanJob(m.range.first, m.range.last + 1,
                TypefaceSpan("monospace")))
            spans.add(SpanJob(m.range.first, m.range.last + 1,
                BackgroundColorSpan(0xFFE5E7EB.toInt())))
        }
        return spans
    }

    /** Spans for a verbatim code line. */
    private fun codeSpans(start: Int, end: Int): List<SpanJob> = listOf(
        SpanJob(start, end, TypefaceSpan("monospace")),
        SpanJob(start, end, BackgroundColorSpan(0xFFE5E7EB.toInt()))
    )

    private class SpanJob(val start: Int, val end: Int, val what: Any)
}
