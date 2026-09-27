package com.jev.probe.jev

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which of the three API routes a failure came from. Used to build error text
 * the user can act on ("判断接口 HTTP 401：…" vs "回复接口 …").
 */
object Route {
    const val JUDGE = "判断接口"
    const val REPLY = "回复接口"
    const val VISION = "视觉接口"
}

/**
 * Carries the route, the HTTP status (null = transport failure) and the first
 * 120 chars of the response body so the settings page can show the real reason.
 */
class ApiException(
    val route: String,
    val status: Int?,
    val snippet: String
) : RuntimeException(buildMessage(route, status, snippet)) {

    companion object {
        fun buildMessage(route: String, status: Int?, snippet: String): String =
            if (status != null) "$route HTTP $status：${snippet.take(120)}"
            else "$route 请求失败：${snippet.take(120)}"
    }
}

/**
 * Shared POST-JSON helper: UTF-8 body, exponential backoff on 429/529, no retry
 * on other 4xx, and every failure normalized to [ApiException]. Keys are passed
 * in per call and never logged.
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 3

    /**
     * @param route one of [Route], used only for error text.
     * @param extraHeaders additional request headers (e.g. OpenRouter attribution).
     */
    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap(),
        proxy: java.net.Proxy? = null
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (if (proxy != null) URL(url).openConnection(proxy)
                        else URL(url).openConnection()) as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 40000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    last = ApiException(route, code, "服务繁忙，已重试")
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                // Branch on the status code FIRST. Reading the body must never be
                // able to lose it: errorStream is null on some failures (and on
                // some OEM stacks), and a read can throw on a truncated response —
                // either way this used to surface as a transport failure with no
                // status, which then got retried even for a 401.
                if (code !in 200..299) {
                    val errText = readBody(conn.errorStream)
                    throw ApiException(route, code, errText.ifBlank { "（响应体为空）" })
                }
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                return JSONObject(text)
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e  // client error: no retry
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                last = ApiException(route, null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(route, null, "请求失败")
    }

    /** Body text, or "" — a null stream or a read failure never costs us the status code. */
    private fun readBody(stream: java.io.InputStream?): String {
        stream ?: return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    /** OpenRouter wants attribution headers; other hosts reject unknown ones politely. */
    fun headersFor(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://jev-assistant.local", "X-Title" to "Jev Assistant")
        else emptyMap()

    /**
     * Per-platform thinking switches, per route — the two platforms spell the
     * toggle differently (docs checked 2026-09):
     *
     * - Bailian (DeepSeek V4 / Qwen hybrids): `"enable_thinking": true|false`.
     * - DeepSeek official (V4 family): `"thinking": {"type": "enabled|disabled"}`
     *   (defaults to enabled; reasoning_effort defaults to high).
     *
     * The judge route THINKS (accuracy of the 7 answers is the product); the
     * reply route does NOT (three ≤40-char lines — measured ~15s → ~1-2s).
     * Params are only ever sent to the platform that defines them.
     */
    fun applyJudgeRouteOptions(body: JSONObject, url: String) {
        if (isBailian(url)) body.put("enable_thinking", true)
        else if (isDeepSeek(url)) body.put("thinking", JSONObject().put("type", "enabled"))
    }

    fun applyReplyRouteOptions(body: JSONObject, url: String) {
        if (isBailian(url)) body.put("enable_thinking", false)
        else if (isDeepSeek(url)) body.put("thinking", JSONObject().put("type", "disabled"))
    }

    private fun isBailian(url: String): Boolean =
        url.contains("dashscope.aliyuncs.com", ignoreCase = true) ||
            url.contains("maas.aliyuncs.com", ignoreCase = true)

    private fun isDeepSeek(url: String): Boolean =
        url.contains("api.deepseek.com", ignoreCase = true)

    /**
     * Streaming chat-completions call: returns the full assistant message.
     *
     * With thinking on, the judgment route thinks for tens of seconds on real
     * conversations. Without streaming that whole wait is ONE silent read, and
     * the 40s read timeout killed the request mid-think (the reported "判断接口
     * 测试无响应 / AI分析失败"). With `stream:true` tokens flow continuously, so
     * the read timeout only bounds the gap between chunks — thinking time never
     * trips it. Providers that ignore `stream` and answer plain JSON are handled
     * too (see [parseChatResponse]).
     */
    fun postChat(url: String, key: String, body: JSONObject, route: String, proxy: java.net.Proxy? = null): String =
        postChatFull(url, key, body, route, proxy).content

    /** The assistant message plus its thinking pass, when the model emitted one. */
    class ChatStream(val content: String, val reasoning: String?)

    /**
     * Like [postChat], but streams INCREMENTALLY: [onDelta] fires as chunks
     * arrive — `"reasoning"` for the model's thinking pass (`reasoning_content`,
     * the field DeepSeek and Bailian both use; `reasoning` as the OpenRouter
     * spelling), `"content"` for the answer — so the UI can show the thinking
     * live instead of a black-box wait. The read timeout only bounds the gap
     * between chunks, so a long think never trips it.
     *
     * A stream that BREAKS mid-answer with content already in hand returns that
     * partial content instead of restarting: a retry re-pays the whole thinking
     * pass (the reported slow rounds), and a truncated answer fails visibly in
     * the parser either way.
     */
    fun postChatFull(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        proxy: java.net.Proxy? = null,
        onDelta: ((kind: String, chunk: String) -> Unit)? = null
    ): ChatStream {
        body.put("stream", true)
        var conn: HttpURLConnection? = null
        var last: ApiException? = null
        var attempt = 0
        while (attempt < 2) {
            val reasoning = StringBuilder()
            val content = StringBuilder()
            try {
                conn = (if (proxy != null) URL(url).openConnection(proxy)
                        else URL(url).openConnection()) as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    headersFor(url).forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw ApiException(route, code, readBody(conn.errorStream).ifBlank { "（响应体为空）" })
                }
                val sse = readStream(conn.inputStream, reasoning, content, onDelta)
                val out = if (sse) splitInlineThink(content)
                    else parsePlainChatResponse(content.toString())
                if (out.content.isBlank()) throw ApiException(route, code, "响应里没有消息内容")
                return out
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e
                last = e; attempt++
            } catch (e: IOException) {
                // Broken mid-stream with something already said: deliver it.
                // Restarting would re-pay the entire thinking pass.
                if (content.isNotBlank()) {
                    Log.w("HttpJson", "stream interrupted after ${content.length} chars; returning partial")
                    return splitInlineThink(content)
                }
                last = ApiException(route, null, describe(e)); attempt++
            } catch (e: Exception) {
                last = ApiException(route, null, describe(e)); attempt++
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(route, null, "请求失败")
    }

    /**
     * Read the response incrementally, line by line, feeding builders and
     * [onDelta] as chunks land. Returns true when the body was SSE
     * (`data:` lines); false for a plain (non-streamed) JSON completion.
     */
    private fun readStream(
        stream: java.io.InputStream,
        reasoning: StringBuilder,
        content: StringBuilder,
        onDelta: ((kind: String, chunk: String) -> Unit)?
    ): Boolean {
        var sse = false
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val t = line.trim()
                if (t.startsWith("data:")) {
                    sse = true
                    val payload = t.removePrefix("data:").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        runCatching {
                            val delta = JSONObject(payload).optJSONArray("choices")
                                ?.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching
                            val think = delta.optString("reasoning_content")
                                .ifEmpty { delta.optString("reasoning") }
                            if (think.isNotEmpty()) {
                                reasoning.append(think)
                                onDelta?.invoke("reasoning", think)
                            }
                            val piece = delta.optString("content")
                            if (piece.isNotEmpty()) {
                                content.append(piece)
                                onDelta?.invoke("content", piece)
                            }
                        }
                    }
                } else if (t.isNotEmpty()) {
                    // Not SSE — keep the raw text for the plain-JSON fallback.
                    content.append(line).append('\n')
                }
                line = reader.readLine()
            }
        }
        return sse
    }

    /** Some models inline their thinking as `<think>…</think>` inside content
     *  instead of a separate field — lift it out at completion time. */
    private fun splitInlineThink(content: StringBuilder): ChatStream {
        val s = content.toString().trim()
        if (s.startsWith("<think>")) {
            val end = s.indexOf("</think>")
            if (end > 0) {
                val think = s.substring("<think>".length, end).trim()
                return ChatStream(s.substring(end + "</think>".length).trim(), think.ifBlank { null })
            }
        }
        return ChatStream(s, null)
    }

    /** Plain (provider ignored `stream`) completion object → content + reasoning. */
    private fun parsePlainChatResponse(contentRaw: String): ChatStream {
        val o = runCatching { JSONObject(contentRaw) }.getOrNull()
        val msg = o?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val think = msg?.optString("reasoning_content")?.ifEmpty { msg.optString("reasoning") }
        return ChatStream(msg?.optString("content") ?: "", think?.ifBlank { null })
    }

    /** Human-readable transport failures (no key material ever appears here). */
    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> m
        }
    }
}
